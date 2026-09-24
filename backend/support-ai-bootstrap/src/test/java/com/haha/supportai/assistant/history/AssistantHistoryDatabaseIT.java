package com.haha.supportai.assistant.history;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.haha.supportai.assistant.agent.entity.dto.AssistantAgentRunResult;
import com.haha.supportai.assistant.agent.entity.dto.AssistantConversationContext;
import com.haha.supportai.assistant.agent.exception.AssistantAgentBudgetException;
import com.haha.supportai.assistant.agent.service.AssistantAgentRunner;
import com.haha.supportai.assistant.agent.service.AssistantHistoryService;
import com.haha.supportai.assistant.agent.service.SupportAssistantAnswerService;
import com.haha.supportai.assistant.agent.tool.entity.dto.AssistantToolContext;
import com.haha.supportai.assistant.agent.tool.entity.dto.GetTicketStatusToolRequest;
import com.haha.supportai.assistant.agent.tool.service.TicketStatusToolAdapter;
import com.haha.supportai.assistant.agent.tool.service.ToolCallPersistenceService;
import com.haha.supportai.conversation.entity.dto.CreateChatSessionRequest;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.dto.SubmitMessageCommand;
import com.haha.supportai.conversation.exception.AssistantGenerationException;
import com.haha.supportai.conversation.service.assistant.AssistantStreamObserver;
import com.haha.supportai.conversation.service.rag.RagRunPersistenceService;
import com.haha.supportai.conversation.service.run.ChatRunControlService;
import com.haha.supportai.conversation.service.run.ChatRunService;
import com.haha.supportai.conversation.service.session.ChatSessionService;
import com.haha.supportai.conversation.support.StoredMessageSourceResponseFactory;
import com.haha.supportai.data.assistant.repository.AssistantHistoryRepository;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.security.ActiveAccountGuard;
import com.haha.supportai.storage.PostgresFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key=", "spring.ai.ollama.base-url=http://127.0.0.1:1",
        "spring.ai.ollama.init.pull-model-strategy=never", "support.ai.ingestion.worker.enabled=false"
})
class AssistantHistoryDatabaseIT {

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ChatSessionService sessions;
    @Autowired
    ChatRunService runs;
    @Autowired
    ChatRunControlService runControl;
    @Autowired
    SupportAssistantAnswerService answers;
    @Autowired
    AssistantHistoryService historyService;
    @Autowired
    AssistantHistoryRepository historyRepository;
    @Autowired
    MessageSourceRepository sources;
    @Autowired
    TicketStatusToolAdapter tickets;
    @Autowired
    ToolCallPersistenceService toolPersistence;
    @Autowired
    RagRunPersistenceService runPersistence;
    @Autowired
    StoredMessageSourceResponseFactory sourceResponses;

    @MockBean
    ActiveAccountGuard account;
    @MockBean
    AssistantAgentRunner agent;

    @Test
    void threeTurnsRetainRootAuditAndRequeryChangedTicketForImplicitCurrentQuestion() {
        Fixture fixture = fixture();
        List<AssistantConversationContext> observed = scriptTicketAnswers();
        PreparedChatRunResponse first = prepare(fixture, "查询工单 " + fixture.ticketNo() + " 当前状态");
        var firstAnswer = answers.generateStreaming(first, AssistantStreamObserver.NONE);
        MessageSource root = sources.listByMessageId(first.getAssistantMessageId()).getFirst();
        assertThat(firstAnswer.getSources().getFirst().getTicketStatus()).isEqualTo("OPEN");

        jdbc.update("""
                -- ticket：模拟外部业务更新，助手必须在后续当前状态追问中重新查询。
                UPDATE ticket SET status = 'RESOLVED', updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, fixture.ticketId());
        PreparedChatRunResponse second = prepare(fixture, "上次查询的工单是什么状态？");
        var secondAnswer = answers.generateStreaming(second, AssistantStreamObserver.NONE);
        assertThat(secondAnswer.getSources()).singleElement().satisfies(source -> {
            assertThat(source.getTicketStatus()).isEqualTo("OPEN");
            assertThat(source.isInherited()).isTrue();
            assertThat(source.getToolCallId()).isEqualTo(root.getToolCallId());
            assertThat(source.getOriginSourceId()).isEqualTo(root.getId());
            assertThat(source.isCurrent()).isFalse();
        });
        assertThat(callCount(second.getRunId())).isZero();

        PreparedChatRunResponse third = prepare(fixture, "现在呢？");
        var thirdAnswer = answers.generateStreaming(third, AssistantStreamObserver.NONE);
        assertThat(thirdAnswer.getSources()).singleElement().satisfies(source -> {
            assertThat(source.getTicketStatus()).isEqualTo("RESOLVED");
            assertThat(source.isInherited()).isFalse();
            assertThat(source.getToolCallId()).isNotEqualTo(root.getToolCallId());
        });
        assertThat(callCount(third.getRunId())).isEqualTo(1);
        assertThat(observed.get(2).getTurns()).hasSize(2);
        assertThat(sources.listByMessageId(third.getAssistantMessageId()))
                .filteredOn(source -> source.getOriginSourceId() != null).singleElement()
                .satisfies(source -> assertThat(source.getOriginSourceId()).isEqualTo(root.getId()));
        assertThat(runControl.getStatus(second.getRunId()).getSources().getFirst().isInherited()).isTrue();
    }

    @Test
    void revocationHidesHistorySnapshotsAndPreventsNextModelCall() {
        Fixture fixture = fixture();
        List<AssistantConversationContext> observed = scriptTicketAnswers();
        PreparedChatRunResponse first = prepare(fixture, "工单 " + fixture.ticketNo() + " 当前状态");
        answers.generateStreaming(first, AssistantStreamObserver.NONE);
        MessageSource root = sources.listByMessageId(first.getAssistantMessageId()).getFirst();
        PreparedChatRunResponse next = prepare(fixture, "现在呢？");
        jdbc.update("""
                -- kb_member：撤销工单所属知识库权限，旧回答不得绕过授权进入模型。
                DELETE FROM kb_member WHERE kb_id = ? AND user_id = ?
                """, fixture.kbId(), fixture.user().getId());

        assertThatThrownBy(() -> answers.generateStreaming(next, AssistantStreamObserver.NONE))
                .isInstanceOfSatisfying(AssistantGenerationException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("AGENT_SOURCE_REVOKED"));
        assertThat(observed).hasSize(1);
        assertThat(sourceResponses.create(fixture.user().getId(), root)).isEmpty();
        assertThat(sessions.listMessages(fixture.sessionId(), 20, null).isContentReadable()).isFalse();
        assertThat(runControl.getStatus(first.getRunId()).getSources()).isEmpty();
    }

    @Test
    void rejectsCrossSessionAndCrossUserRootsAndRollsBackMixedBatch() {
        Fixture fixture = fixture();
        scriptTicketAnswers();
        PreparedChatRunResponse first = prepare(fixture, "工单 " + fixture.ticketNo() + " 当前状态");
        answers.generateStreaming(first, AssistantStreamObserver.NONE);
        MessageSource ownRoot = sources.listByMessageId(first.getAssistantMessageId()).getFirst();
        Fixture other = fixture();
        PreparedChatRunResponse otherFirst = prepare(other, "工单 " + other.ticketNo() + " 当前状态");
        answers.generateStreaming(otherFirst, AssistantStreamObserver.NONE);
        MessageSource foreignRoot = sources.listByMessageId(otherFirst.getAssistantMessageId()).getFirst();
        when(account.currentUser()).thenReturn(fixture.user());
        PreparedChatRunResponse next = prepare(fixture, "解释先前结果");
        runPersistence.start(next);
        assertThat(historyRepository.isUsable(ownRoot.getId(), fixture.sessionId(), other.user().getId())).isFalse();

        MessageSource own = inherited(next, ownRoot);
        MessageSource foreign = inherited(next, foreignRoot);
        assertThatThrownBy(() -> historyService.register(next,
                new AssistantConversationContext(List.of(), List.of(own, foreign))))
                .isInstanceOf(AssistantAgentBudgetException.class);
        assertThat(sources.listByMessageId(next.getAssistantMessageId())).isEmpty();

        UUID anotherSession = createSession(fixture.kbId());
        PreparedChatRunResponse crossSession = runs.prepare(anotherSession,
                new SubmitMessageCommand(UUID.randomUUID(), "解释先前结果"));
        runPersistence.start(crossSession);
        assertThat(historyRepository.inherit(ownRoot.getId(), anotherSession, fixture.user().getId(),
                crossSession.getRunId(), crossSession.getAssistantMessageId(), UUID.randomUUID(), 1)).isFalse();
    }

    @Test
    void excludesWholeMixedTurnWhenDocumentIsReplacedOrDeletedEvenIfUncited() {
        Fixture fixture = fixture();
        UUID documentId = insertDocument(fixture);
        UUID chunkId = jdbc.queryForObject("""
                -- document_chunk、document_version：读取混合历史测试的当前分块。
                SELECT chunk.id FROM document_chunk chunk JOIN document_version version ON version.id = chunk.version_id
                WHERE version.document_id = ?
                """, UUID.class, documentId);
        when(agent.run(any(), any(), anySet(), any())).thenAnswer(invocation -> {
            PreparedChatRunResponse prepared = invocation.getArgument(0);
            AssistantToolContext context = AssistantToolContext.from(prepared);
            var ticket = tickets.execute(context, new GetTicketStatusToolRequest(fixture.ticketNo()));
            var call = toolPersistence.start(context, "searchKnowledge", Map.of("query", "工单处理规范"));
            MessageSource document = new MessageSource(UUID.randomUUID(), prepared.getAssistantMessageId(), null,
                    0, false, MessageSourceTypeEnum.DOCUMENT, fixture.kbId(), chunkId, null, call.getId(),
                    "{}", "{}", OffsetDateTime.now());
            toolPersistence.succeed(context, call.getId(), Map.of("sources", List.of(
                    Map.of("sourceHandle", document.getId(), "content", "原处理规范"))), List.of(document));
            return new AssistantAgentRunResult("处理中[[source:" + ticket.getSourceHandle() + "]]",
                    3, 2, 500, Set.of("getTicketStatus", "searchKnowledge"));
        });
        PreparedChatRunResponse first = prepare(fixture, "工单 " + fixture.ticketNo() + " 当前状态与规范");
        answers.generateStreaming(first, AssistantStreamObserver.NONE);
        PreparedChatRunResponse next = prepare(fixture, "按上次规范解释");
        assertThat(historyService.load(next).getSources()).hasSize(2);
        jdbc.update("""
                -- document：移除 active 模拟旧版本已不再作为当前事实。
                UPDATE document SET active_version_id = NULL WHERE id = ?
                """, documentId);
        assertThat(historyService.load(next).getTurns()).isEmpty();
        jdbc.update("""
                -- document：标记删除后，同一轮未引用的文档也不能通过工单历史重新注入。
                UPDATE document SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?
                """, documentId);
        assertThat(historyService.load(next).getSources()).isEmpty();
    }

    private List<AssistantConversationContext> scriptTicketAnswers() {
        List<AssistantConversationContext> observed = new ArrayList<>();
        when(agent.run(any(), any(), anySet(), any())).thenAnswer(invocation -> {
            PreparedChatRunResponse prepared = invocation.getArgument(0);
            AssistantConversationContext context = invocation.getArgument(1);
            Set<String> required = invocation.getArgument(2);
            observed.add(context);
            if (required.isEmpty()) {
                return new AssistantAgentRunResult("先前查询结果[[source:" + context.getSources().getFirst().getId()
                        + "]]", 1, 0, 300, Set.of());
            }
            var result = tickets.execute(AssistantToolContext.from(prepared),
                    new GetTicketStatusToolRequest(required.iterator().next()));
            return new AssistantAgentRunResult("本轮查询结果 " + result.getStatus() + "[[source:"
                    + result.getSourceHandle() + "]]", 2, 1, 400, Set.of("getTicketStatus"));
        });
        return observed;
    }

    private Fixture fixture() {
        UUID userId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        String ticketNo = "TK-" + ticketId.toString().substring(0, 8).toUpperCase();
        String username = "f28-" + userId;
        jdbc.update("""
                -- app_user：F28 临时数据库中的独立测试用户。
                INSERT INTO app_user (id, username, password_hash, display_name, role, enabled)
                VALUES (?, ?, 'test-hash', 'F28 用户', 'USER', TRUE)
                """, userId, username);
        jdbc.update("""
                -- knowledge_base：每个场景独立的授权业务范围。
                INSERT INTO knowledge_base (id, name, owner_id, status) VALUES (?, 'F28 知识库', ?, 'ACTIVE')
                """, kbId, userId);
        jdbc.update("""
                -- kb_member：授予测试用户 READ 成员权，不依赖管理员特权。
                INSERT INTO kb_member (kb_id, user_id, permission, granted_by) VALUES (?, ?, 'READ', ?)
                """, kbId, userId, userId);
        jdbc.update("""
                -- ticket：可在测试中变更的真实业务表状态。
                INSERT INTO ticket (id, ticket_no, kb_id, title, status) VALUES (?, ?, ?, 'F28 工单', 'OPEN')
                """, ticketId, ticketNo, kbId);
        AuthenticatedUser user = new AuthenticatedUser(userId, username, "F28 用户", UserRoleEnum.USER);
        when(account.currentUser()).thenReturn(user);
        return new Fixture(user, kbId, ticketId, ticketNo, createSession(kbId));
    }

    private UUID createSession(UUID kbId) {
        CreateChatSessionRequest request = new CreateChatSessionRequest();
        request.setMode(ChatSessionModeEnum.ASSISTANT);
        request.setKbIds(List.of(kbId));
        return sessions.create(request).getSessionId();
    }

    private PreparedChatRunResponse prepare(Fixture fixture, String question) {
        when(account.currentUser()).thenReturn(fixture.user());
        return runs.prepare(fixture.sessionId(), new SubmitMessageCommand(UUID.randomUUID(), question));
    }

    private int callCount(UUID runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tool_call WHERE run_id = ?", Integer.class, runId);
    }

    private MessageSource inherited(PreparedChatRunResponse prepared, MessageSource root) {
        return new MessageSource(UUID.randomUUID(), prepared.getAssistantMessageId(), root.getId(), 0, false,
                root.getSourceType(), root.getKnowledgeBaseId(), root.getChunkId(), root.getTicketId(),
                root.getToolCallId(), root.getLocator(), root.getSnapshot(), OffsetDateTime.now());
    }

    private UUID insertDocument(Fixture fixture) {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        jdbc.update("""
                -- document：混合来源测试的文档，active 在版本创建后设置。
                INSERT INTO document (id, kb_id, name, created_by, next_version_no) VALUES (?, ?, '处理规范', ?, 2)
                """, documentId, fixture.kbId(), fixture.user().getId());
        jdbc.update("""
                -- document_version：已发布测试版本，固化 1024 维模型配置。
                INSERT INTO document_version (id, document_id, version_no, storage_key, file_hash, file_size,
                    media_type, original_filename, status, parser_version, chunker_version, embedding_model,
                    dimensions, config_hash, config_snapshot, chunk_count)
                VALUES (?, ?, 1, ?, ?, 12, 'text/markdown', 'f28.md', 'READY', 'plain-text-v1', 'token-budget-v1',
                    'bge-m3', 1024, ?, '{"embedding":{"provider":"ollama","model":"bge-m3","dimensions":1024}}', 1)
                """, versionId, documentId, "f28/" + versionId + ".md", "a".repeat(64), "b".repeat(64));
        jdbc.update("""
                -- document_chunk：混合历史来源的稳定分块定位。
                INSERT INTO document_chunk (id, version_id, chunk_index, start_offset, end_offset,
                    content_hash, char_count, token_count) VALUES (?, ?, 0, 0, 5, ?, 5, 5)
                """, chunkId, versionId, "c".repeat(64));
        jdbc.update("""
                -- vector_store：仅在临时数据库写入确定性测试向量，不调用外部模型。
                INSERT INTO vector_store (id, content, metadata, embedding) VALUES (?, '原处理规范', '{}', CAST(? AS vector))
                """, chunkId, "[1," + "0,".repeat(1022) + "0]");
        jdbc.update("UPDATE document SET active_version_id = ? WHERE id = ?", versionId, documentId);
        return documentId;
    }

    private record Fixture(AuthenticatedUser user, UUID kbId, UUID ticketId, String ticketNo, UUID sessionId) {
    }
}
