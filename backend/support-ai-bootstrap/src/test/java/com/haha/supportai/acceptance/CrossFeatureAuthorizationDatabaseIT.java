package com.haha.supportai.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.haha.supportai.assistant.agent.tool.constant.AssistantToolErrorCodeConstant;
import com.haha.supportai.assistant.agent.tool.entity.dto.AssistantToolContext;
import com.haha.supportai.assistant.agent.tool.entity.dto.GetTicketStatusToolRequest;
import com.haha.supportai.assistant.agent.tool.exception.AssistantToolException;
import com.haha.supportai.assistant.agent.tool.service.TicketStatusToolAdapter;
import com.haha.supportai.assistant.ticket.exception.TicketNotFoundException;
import com.haha.supportai.assistant.ticket.service.TicketQueryService;
import com.haha.supportai.conversation.entity.dto.CreateChatSessionRequest;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.dto.SubmitMessageCommand;
import com.haha.supportai.conversation.entity.vo.ChatMessagePageResponse;
import com.haha.supportai.conversation.entity.vo.ChatRunStatusResponse;
import com.haha.supportai.conversation.exception.ChatRunNotFoundException;
import com.haha.supportai.conversation.exception.ChatSessionNotFoundException;
import com.haha.supportai.conversation.service.rag.RagRunPersistenceService;
import com.haha.supportai.conversation.service.rag.RagAnswerService;
import com.haha.supportai.conversation.service.rag.ChatModelGateway;
import com.haha.supportai.conversation.service.run.ChatRunControlService;
import com.haha.supportai.conversation.service.run.ChatRunService;
import com.haha.supportai.conversation.service.session.ChatSessionService;
import com.haha.supportai.data.conversation.enums.ChatMessageRoleEnum;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.data.knowledge.enums.IngestionJobStatusEnum;
import com.haha.supportai.data.knowledge.repository.AuthorizedRetrievalRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.security.ActiveAccountGuard;
import com.haha.supportai.knowledge.document.entity.vo.DocumentUploadReceiptResponse;
import com.haha.supportai.knowledge.document.exception.DocumentNotFoundException;
import com.haha.supportai.knowledge.document.service.DocumentQueryService;
import com.haha.supportai.knowledge.document.service.DocumentUploadService;
import com.haha.supportai.knowledge.exception.KnowledgeBaseAccessDeniedException;
import com.haha.supportai.knowledge.ingestion.exception.IngestionJobNotFoundException;
import com.haha.supportai.knowledge.ingestion.service.IngestionJobControlService;
import com.haha.supportai.knowledge.ingestion.service.IngestionJobQueryService;
import com.haha.supportai.knowledge.service.document.DocumentDeletionService;
import com.haha.supportai.knowledge.service.source.DocumentSourceQueryService;
import com.haha.supportai.knowledge.service.retrieval.KnowledgeRetrievalService;
import com.haha.supportai.knowledge.entity.dto.KnowledgeRetrievalResult;
import com.haha.supportai.storage.PostgresFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key=", "spring.ai.ollama.base-url=http://127.0.0.1:1",
        "spring.ai.ollama.init.pull-model-strategy=never", "support.ai.ingestion.worker.enabled=false",
        "support.ai.document.cleanup.enabled=false"
})
class CrossFeatureAuthorizationDatabaseIT {

    private static final Path STORAGE_ROOT = Path.of("target", "f29-authorization-storage")
            .toAbsolutePath().normalize();
    private static final String DOCUMENT_CONTENT = "F29 专属处理规则：仅授权成员可以读取。";
    private static final String ASSISTANT_ANSWER = "F29 私有工单处于处理中。";

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("support.ai.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired
    DocumentUploadService documentUploadService;
    @Autowired
    DocumentQueryService documentQueryService;
    @Autowired
    DocumentDeletionService documentDeletionService;
    @Autowired
    IngestionJobQueryService ingestionJobQueryService;
    @Autowired
    IngestionJobControlService ingestionJobControlService;
    @Autowired
    DocumentSourceQueryService documentSourceQueryService;
    @Autowired
    AuthorizedRetrievalRepository authorizedRetrievalRepository;
    @Autowired
    TicketQueryService ticketQueryService;
    @Autowired
    ChatSessionService chatSessionService;
    @Autowired
    ChatRunService chatRunService;
    @Autowired
    ChatRunControlService chatRunControlService;
    @Autowired
    RagRunPersistenceService runPersistenceService;
    @Autowired
    TicketStatusToolAdapter ticketStatusToolAdapter;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockBean
    ActiveAccountGuard activeAccountGuard;

    @MockBean
    EmbeddingModel embeddingModel;

    @MockBean
    ChatModelGateway chatModelGateway;

    @SpyBean
    KnowledgeRetrievalService knowledgeRetrievalService;

    @Autowired
    RagAnswerService ragAnswerService;

    @ParameterizedTest(name = "{0} 检索后真实撤权阻断入模与来源写入")
    @ValueSource(strings = {"DEV-019", "DEV-039"})
    void revocationAfterRetrievalPreventsModelCallAndSourcePersistence(String caseId) {
        AuthorizationFixture fixture = createFixture();
        PublishedDocument document = null;
        try {
            useUser(fixture.writer());
            document = publish(documentUploadService.upload(fixture.writerKnowledgeBaseId(),
                    upload("m7-revocation.md", DOCUMENT_CONTENT), "M7 检索时序验收 " + caseId));
            CreateChatSessionRequest request = new CreateChatSessionRequest();
            request.setMode(ChatSessionModeEnum.RAG);
            request.setKbIds(List.of(fixture.writerKnowledgeBaseId()));
            UUID sessionId = chatSessionService.create(request).getSessionId();
            String question = caseId + " 的专属处理规则是什么？";
            PreparedChatRunResponse prepared = chatRunService.prepare(sessionId,
                    new SubmitMessageCommand(UUID.randomUUID(), question));
            UUID chunkId = document.chunkId();
            when(embeddingModel.embed(question)).thenReturn(queryVector(document.dimensions()));

            // 在真实检索返回之后、服务复核之前同步撤权，避免用休眠猜测竞态窗口。
            doAnswer(invocation -> {
                KnowledgeRetrievalResult retrieved = (KnowledgeRetrievalResult) invocation.callRealMethod();
                assertThat(retrieved.getMatches()).extracting(evidence -> evidence.getChunkId())
                        .containsExactly(chunkId);
                assertThat(jdbcTemplate.queryForObject("""
                        -- message_source：暂停点必须尚未登记本轮模型来源。
                        SELECT count(*) FROM message_source WHERE message_id = ?
                        """, Long.class, prepared.getAssistantMessageId())).isZero();
                revokeWriter(fixture);
                return retrieved;
            }).when(knowledgeRetrievalService).retrieveReadableWithinSessionDetailed(
                    fixture.writer().getId(), sessionId, question, 6);

            var result = ragAnswerService.generate(prepared);

            verify(knowledgeRetrievalService).revalidateReadableWithinSession(
                    fixture.writer().getId(), sessionId, List.of(chunkId));
            verifyNoInteractions(chatModelGateway);
            assertThat(result.isNoEvidence()).isTrue();
            assertThat(result.getContent()).contains("资料不足").doesNotContain(DOCUMENT_CONTENT);
            assertThat(result.getCitedSourceNumbers()).isEmpty();
            assertThat(jdbcTemplate.queryForObject("""
                    -- message_source：真实服务收口之后仍没有新增来源。
                    SELECT count(*) FROM message_source WHERE message_id = ?
                    """, Long.class, prepared.getAssistantMessageId())).isZero();
            assertThat(jdbcTemplate.queryForObject("""
                    -- chat_run：零证据路径正常收口，不遗留活动运行。
                    SELECT status FROM chat_run WHERE id = ?
                    """, String.class, prepared.getRunId())).isEqualTo("COMPLETED");
            assertThat(jdbcTemplate.queryForObject("""
                    -- chat_message：只持久化安全拒答，不写入已撤权资料正文。
                    SELECT content FROM chat_message WHERE id = ?
                    """, String.class, prepared.getAssistantMessageId())).isEqualTo(result.getContent());
        } finally {
            deleteFixture(fixture, document);
        }
    }

    @Test
    void authorizationChangesStayConsistentAcrossDocumentsConversationsAndTools() {
        AuthorizationFixture fixture = createFixture();
        PublishedDocument document = null;
        try {
            useUser(fixture.writer());
            DocumentUploadReceiptResponse receipt = documentUploadService.upload(fixture.writerKnowledgeBaseId(),
                    upload("f29-private.md", DOCUMENT_CONTENT), "F29 私有处理规则");
            assertThat(receipt.getStatus()).isEqualTo(IngestionJobStatusEnum.QUEUED);
            document = publish(receipt);
            ConversationFixture conversation = createCompletedConversation(fixture);

            assertWriterCanReadEveryEntry(fixture, document, conversation);

            useUser(fixture.otherUser());
            assertThat(ticketQueryService.getByTicketNo(fixture.otherTicketNo()).getKnowledgeBaseId())
                    .isEqualTo(fixture.otherKnowledgeBaseId());
            assertForeignIdentityCannotRead(fixture, document, conversation, fixture.otherUser());

            useUser(fixture.administrator());
            assertForeignIdentityCannotRead(fixture, document, conversation, fixture.administrator());

            changePermission(fixture, "READ");
            useUser(fixture.writer());
            assertReadOnlyMemberCanReadButCannotMutate(fixture, document, conversation);

            revokeWriter(fixture);
            assertOwnerContentIsHidden(fixture, document, conversation);

            restoreWriter(fixture);
            disableKnowledgeBase(fixture);
            assertOwnerContentIsHidden(fixture, document, conversation);
        } finally {
            deleteFixture(fixture, document);
        }
    }

    private PublishedDocument publish(DocumentUploadReceiptResponse receipt) {
        Map<String, Object> processing = jdbcTemplate.queryForMap("""
                -- document_version：读取上传时固化的存储键和嵌入配置，用于构造同配置的已发布分块。
                SELECT storage_key, embedding_model, dimensions, config_hash,
                       config_snapshot -> 'embedding' ->> 'provider' AS embedding_provider
                FROM document_version
                WHERE id = ?
                """, receipt.getVersionId());
        UUID chunkId = UUID.randomUUID();
        int dimensions = ((Number) processing.get("dimensions")).intValue();
        String storageKey = (String) processing.get("storage_key");
        String embeddingModel = (String) processing.get("embedding_model");
        String configHash = (String) processing.get("config_hash");
        String embeddingProvider = (String) processing.get("embedding_provider");

        jdbcTemplate.update("""
                -- document_chunk：登记 F29 已发布版本的唯一文本分块及原文定位。
                INSERT INTO document_chunk (
                    id, version_id, chunk_index, section, page_no, start_offset, end_offset,
                    content_hash, char_count, token_count
                ) VALUES (?, ?, 0, 'F29 授权规则', 1, 0, ?, ?, ?, 12)
                """, chunkId, receipt.getVersionId(), DOCUMENT_CONTENT.length(), "c".repeat(64),
                DOCUMENT_CONTENT.length());
        jdbcTemplate.update("""
                -- vector_store：写入与文档分块同主键的 1024 维测试向量和受保护正文。
                INSERT INTO vector_store (id, content, metadata, embedding)
                VALUES (?, ?, CAST('{"stage":"F29"}' AS JSON), CAST(? AS vector))
                """, chunkId, DOCUMENT_CONTENT, vectorLiteral(dimensions));
        jdbcTemplate.update("""
                -- document_version：将测试版本切换为 READY，并记录已发布分块数。
                UPDATE document_version
                SET status = 'READY', chunk_count = 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, receipt.getVersionId());
        jdbcTemplate.update("""
                -- document：原子测试夹具只发布本版本，清除待处理指针并设置当前版本。
                UPDATE document
                SET active_version_id = ?, pending_version_id = NULL, revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, receipt.getVersionId(), receipt.getDocumentId());
        jdbcTemplate.update("""
                -- ingestion_job：把对应任务收敛为已完成发布状态，保留真实任务唯一约束。
                UPDATE ingestion_job
                SET status = 'SUCCEEDED', stage = 'PUBLISH', attempt = 1,
                    completed_chunks = 1, total_chunks = 1,
                    started_at = CURRENT_TIMESTAMP, finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, receipt.getJobId());
        return new PublishedDocument(receipt.getDocumentId(), receipt.getVersionId(), receipt.getJobId(), chunkId,
                storageKey, embeddingProvider, embeddingModel, dimensions, configHash);
    }

    private ConversationFixture createCompletedConversation(AuthorizationFixture fixture) {
        CreateChatSessionRequest sessionRequest = new CreateChatSessionRequest();
        sessionRequest.setMode(ChatSessionModeEnum.ASSISTANT);
        sessionRequest.setKbIds(List.of(fixture.writerKnowledgeBaseId()));
        UUID sessionId = chatSessionService.create(sessionRequest).getSessionId();
        PreparedChatRunResponse prepared = chatRunService.prepare(sessionId,
                new SubmitMessageCommand(UUID.randomUUID(), "查询工单 " + fixture.writerTicketNo()));
        runPersistenceService.start(prepared);
        var ticketResult = ticketStatusToolAdapter.execute(AssistantToolContext.from(prepared),
                new GetTicketStatusToolRequest(fixture.writerTicketNo()));
        runPersistenceService.complete(prepared.getRunId(), prepared.getAssistantMessageId(), ASSISTANT_ANSWER,
                List.of(1), "{}");
        return new ConversationFixture(sessionId, prepared.getRunId(), prepared.getAssistantMessageId(),
                ticketResult.getSourceHandle());
    }

    private void assertWriterCanReadEveryEntry(AuthorizationFixture fixture, PublishedDocument document,
            ConversationFixture conversation) {
        assertThat(documentQueryService.get(document.documentId()).getKnowledgeBaseId())
                .isEqualTo(fixture.writerKnowledgeBaseId());
        assertThat(documentQueryService.getContent(document.documentId(), document.versionId()).getContent())
                .isEqualTo(DOCUMENT_CONTENT);
        assertThat(documentQueryService.listChunks(document.documentId(), document.versionId(), 20, null).getItems())
                .singleElement().satisfies(chunk -> assertThat(chunk.getContent()).isEqualTo(DOCUMENT_CONTENT));
        assertThat(ingestionJobQueryService.get(document.jobId()).getStatus())
                .isEqualTo(IngestionJobStatusEnum.SUCCEEDED);
        assertThat(documentSourceQueryService.getReadableSource(document.chunkId()).getContent())
                .isEqualTo(DOCUMENT_CONTENT);
        assertThat(ticketQueryService.getByTicketNo(fixture.writerTicketNo()).getKnowledgeBaseId())
                .isEqualTo(fixture.writerKnowledgeBaseId());
        assertWritableRetrieval(document, fixture.writer(), true);
        assertReadableSessionRetrieval(document, fixture.writer(), conversation, true);

        ChatMessagePageResponse history = chatSessionService.listMessages(conversation.sessionId(), 20, null);
        assertThat(history.isContentReadable()).isTrue();
        assertThat(history.getItems()).filteredOn(message -> message.getRole() == ChatMessageRoleEnum.ASSISTANT)
                .singleElement().satisfies(message -> {
                    assertThat(message.getContent()).isEqualTo(ASSISTANT_ANSWER);
                    assertThat(message.getSources()).hasSize(1);
                });
        ChatRunStatusResponse run = chatRunControlService.getStatus(conversation.runId());
        assertThat(run.isContentReadable()).isTrue();
        assertThat(run.getAnswer()).isEqualTo(ASSISTANT_ANSWER);
        assertThat(run.getSources()).hasSize(1);
    }

    private void assertForeignIdentityCannotRead(AuthorizationFixture fixture, PublishedDocument document,
            ConversationFixture conversation, AuthenticatedUser identity) {
        assertDirectEntriesHidden(fixture, document);
        assertWritableRetrieval(document, identity, false);
        assertReadableSessionRetrieval(document, identity, conversation, false);
        assertThatThrownBy(() -> chatSessionService.listMessages(conversation.sessionId(), 20, null))
                .isInstanceOf(ChatSessionNotFoundException.class);
        assertThatThrownBy(() -> chatRunControlService.getStatus(conversation.runId()))
                .isInstanceOf(ChatRunNotFoundException.class);
        assertThatThrownBy(() -> ticketStatusToolAdapter.execute(
                new AssistantToolContext(conversation.runId(), conversation.sessionId(), identity.getId(),
                        conversation.assistantMessageId()),
                new GetTicketStatusToolRequest(fixture.writerTicketNo())))
                .isInstanceOfSatisfying(AssistantToolException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AssistantToolErrorCodeConstant.TOOL_CONTEXT_INVALID));
    }

    private void assertReadOnlyMemberCanReadButCannotMutate(AuthorizationFixture fixture,
            PublishedDocument document, ConversationFixture conversation) {
        assertThat(documentQueryService.getContent(document.documentId(), document.versionId()).getContent())
                .isEqualTo(DOCUMENT_CONTENT);
        assertThat(documentQueryService.listChunks(document.documentId(), document.versionId(), 20, null).getItems())
                .singleElement().satisfies(chunk -> assertThat(chunk.getContent()).isEqualTo(DOCUMENT_CONTENT));
        assertThat(ingestionJobQueryService.get(document.jobId()).isCancellable()).isFalse();
        assertThat(documentSourceQueryService.getReadableSource(document.chunkId()).getContent())
                .isEqualTo(DOCUMENT_CONTENT);
        assertThat(ticketQueryService.getByTicketNo(fixture.writerTicketNo()).getTicketNo())
                .isEqualTo(fixture.writerTicketNo());
        assertWritableRetrieval(document, fixture.writer(), false);
        assertReadableSessionRetrieval(document, fixture.writer(), conversation, true);
        assertThat(chatSessionService.listMessages(conversation.sessionId(), 20, null).isContentReadable()).isTrue();
        assertThat(chatRunControlService.getStatus(conversation.runId()).isContentReadable()).isTrue();

        assertThatThrownBy(() -> documentUploadService.upload(fixture.writerKnowledgeBaseId(),
                upload("denied.md", "不得写入"), "无权写入"))
                .isInstanceOf(KnowledgeBaseAccessDeniedException.class);
        assertThatThrownBy(() -> documentDeletionService.delete(document.documentId()))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> ingestionJobControlService.cancel(document.jobId()))
                .isInstanceOf(KnowledgeBaseAccessDeniedException.class);
    }

    private void assertOwnerContentIsHidden(AuthorizationFixture fixture, PublishedDocument document,
            ConversationFixture conversation) {
        useUser(fixture.writer());
        assertDirectEntriesHidden(fixture, document);
        assertWritableRetrieval(document, fixture.writer(), false);
        assertReadableSessionRetrieval(document, fixture.writer(), conversation, false);

        ChatMessagePageResponse history = chatSessionService.listMessages(conversation.sessionId(), 20, null);
        assertThat(history.isContentReadable()).isFalse();
        assertThat(history.getItems()).allSatisfy(message -> {
            assertThat(message.getContent()).isNull();
            assertThat(message.getSources()).isEmpty();
        });
        ChatRunStatusResponse run = chatRunControlService.getStatus(conversation.runId());
        assertThat(run.isContentReadable()).isFalse();
        assertThat(run.getAnswer()).isNull();
        assertThat(run.getSources()).isEmpty();
    }

    private void assertDirectEntriesHidden(AuthorizationFixture fixture, PublishedDocument document) {
        assertThatThrownBy(() -> documentQueryService.get(document.documentId()))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> documentQueryService.getContent(document.documentId(), document.versionId()))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> documentQueryService.listChunks(
                document.documentId(), document.versionId(), 20, null))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> ingestionJobQueryService.get(document.jobId()))
                .isInstanceOf(IngestionJobNotFoundException.class);
        assertThatThrownBy(() -> documentSourceQueryService.getReadableSource(document.chunkId()))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> ticketQueryService.getByTicketNo(fixture.writerTicketNo()))
                .isInstanceOf(TicketNotFoundException.class);
    }

    private void assertWritableRetrieval(PublishedDocument document, AuthenticatedUser identity, boolean expected) {
        var hits = authorizedRetrievalRepository.searchWritable(identity.getId(),
                knowledgeBaseId(document.documentId()), queryVector(document.dimensions()), 5,
                document.embeddingProvider(), document.embeddingModel(), document.dimensions(),
                document.configHash());
        if (expected) {
            assertThat(hits).singleElement().satisfies(hit -> {
                assertThat(hit.getChunkId()).isEqualTo(document.chunkId());
                assertThat(hit.getContent()).isEqualTo(DOCUMENT_CONTENT);
            });
        } else {
            assertThat(hits).isEmpty();
        }
    }

    private void assertReadableSessionRetrieval(PublishedDocument document, AuthenticatedUser identity,
            ConversationFixture conversation, boolean expected) {
        var hits = authorizedRetrievalRepository.searchReadableWithinSession(identity.getId(),
                conversation.sessionId(), queryVector(document.dimensions()), 5, document.embeddingProvider(),
                document.embeddingModel(), document.dimensions(), document.configHash());
        if (expected) {
            assertThat(hits).singleElement()
                    .satisfies(hit -> assertThat(hit.getChunkId()).isEqualTo(document.chunkId()));
        } else {
            assertThat(hits).isEmpty();
        }
    }

    private UUID knowledgeBaseId(UUID documentId) {
        return jdbcTemplate.queryForObject("""
                -- document：按文档标识读取所属知识库，仅用于参数化授权检索断言。
                SELECT kb_id FROM document WHERE id = ?
                """, UUID.class, documentId);
    }

    private AuthorizationFixture createFixture() {
        UUID writerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID administratorId = UUID.randomUUID();
        UUID writerKnowledgeBaseId = UUID.randomUUID();
        UUID otherKnowledgeBaseId = UUID.randomUUID();
        UUID writerTicketId = UUID.randomUUID();
        UUID otherTicketId = UUID.randomUUID();
        String writerTicketNo = ticketNo("F29A", writerTicketId);
        String otherTicketNo = ticketNo("F29B", otherTicketId);

        jdbcTemplate.update("""
                -- app_user：创建两个普通用户和一个无成员关系管理员，覆盖 F29 身份矩阵。
                INSERT INTO app_user (id, username, password_hash, display_name, role, enabled)
                VALUES
                    (?, ?, 'test-hash', 'F29 写入用户', 'USER', TRUE),
                    (?, ?, 'test-hash', 'F29 其他用户', 'USER', TRUE),
                    (?, ?, 'test-hash', 'F29 无成员管理员', 'ADMIN', TRUE)
                """, writerId, "f29-writer-" + writerId, otherUserId, "f29-other-" + otherUserId,
                administratorId, "f29-admin-" + administratorId);
        jdbcTemplate.update("""
                -- knowledge_base：创建两个启用知识库；管理员仅为负责人，不自动获得资料读取权。
                INSERT INTO knowledge_base (id, name, description, owner_id, status, revision)
                VALUES
                    (?, ?, 'F29 写入用户专属范围', ?, 'ACTIVE', 0),
                    (?, ?, 'F29 其他用户专属范围', ?, 'ACTIVE', 0)
                """, writerKnowledgeBaseId, "F29-A-" + writerKnowledgeBaseId, administratorId,
                otherKnowledgeBaseId, "F29-B-" + otherKnowledgeBaseId, administratorId);
        jdbcTemplate.update("""
                -- kb_member：每个普通用户只加入自己的知识库，初始权限均为 WRITE。
                INSERT INTO kb_member (kb_id, user_id, permission, granted_by)
                VALUES (?, ?, 'WRITE', ?), (?, ?, 'WRITE', ?)
                """, writerKnowledgeBaseId, writerId, administratorId,
                otherKnowledgeBaseId, otherUserId, administratorId);
        jdbcTemplate.update("""
                -- ticket：为两个隔离知识库各建一张工单，供直接查询和助手工具入口复核。
                INSERT INTO ticket (id, ticket_no, kb_id, title, description, status, owner_name)
                VALUES
                    (?, ?, ?, 'F29 私有工单', '仅 A 用户可见', 'IN_PROGRESS', '支持工程师 A'),
                    (?, ?, ?, 'F29 其他工单', '仅 B 用户可见', 'OPEN', NULL)
                """, writerTicketId, writerTicketNo, writerKnowledgeBaseId,
                otherTicketId, otherTicketNo, otherKnowledgeBaseId);

        return new AuthorizationFixture(
                new AuthenticatedUser(writerId, "f29-writer", "F29 写入用户", UserRoleEnum.USER),
                new AuthenticatedUser(otherUserId, "f29-other", "F29 其他用户", UserRoleEnum.USER),
                new AuthenticatedUser(administratorId, "f29-admin", "F29 无成员管理员", UserRoleEnum.ADMIN),
                writerKnowledgeBaseId, otherKnowledgeBaseId, writerTicketNo, otherTicketNo);
    }

    private void changePermission(AuthorizationFixture fixture, String permission) {
        jdbcTemplate.update("""
                -- kb_member：实时调整 A 用户权限，验证 READ 与 WRITE 边界立即生效。
                UPDATE kb_member SET permission = ? WHERE kb_id = ? AND user_id = ?
                """, permission, fixture.writerKnowledgeBaseId(), fixture.writer().getId());
    }

    private void revokeWriter(AuthorizationFixture fixture) {
        jdbcTemplate.update("""
                -- kb_member：撤销 A 用户成员关系，验证全部资料正文和来源立即失效。
                DELETE FROM kb_member WHERE kb_id = ? AND user_id = ?
                """, fixture.writerKnowledgeBaseId(), fixture.writer().getId());
    }

    private void restoreWriter(AuthorizationFixture fixture) {
        jdbcTemplate.update("""
                -- kb_member：恢复 A 用户 WRITE 成员关系，以单独验证知识库停用状态。
                INSERT INTO kb_member (kb_id, user_id, permission, granted_by)
                VALUES (?, ?, 'WRITE', ?)
                """, fixture.writerKnowledgeBaseId(), fixture.writer().getId(), fixture.administrator().getId());
    }

    private void disableKnowledgeBase(AuthorizationFixture fixture) {
        jdbcTemplate.update("""
                -- knowledge_base：停用 A 知识库，验证成员关系仍在时也不能读取任何正文。
                UPDATE knowledge_base SET status = 'DISABLED', revision = revision + 1 WHERE id = ?
                """, fixture.writerKnowledgeBaseId());
    }

    private void deleteFixture(AuthorizationFixture fixture, PublishedDocument document) {
        jdbcTemplate.update("""
                -- message_source：先清理测试用户会话的来源，解除工单和工具调用外键。
                DELETE FROM message_source
                WHERE message_id IN (
                    SELECT message.id FROM chat_message message
                    INNER JOIN chat_session session ON session.id = message.session_id
                    WHERE session.user_id IN (?, ?)
                )
                """, fixture.writer().getId(), fixture.otherUser().getId());
        jdbcTemplate.update("""
                -- tool_call：清理测试用户会话内的全部工具审计记录。
                DELETE FROM tool_call
                WHERE run_id IN (
                    SELECT run.id FROM chat_run run
                    INNER JOIN chat_session session ON session.id = run.session_id
                    WHERE session.user_id IN (?, ?)
                )
                """, fixture.writer().getId(), fixture.otherUser().getId());
        jdbcTemplate.update("""
                -- chat_message、chat_run、chat_session_kb、chat_session：按外键顺序清理 F29 会话链路。
                DELETE FROM chat_message WHERE session_id IN (
                    SELECT id FROM chat_session WHERE user_id IN (?, ?)
                )
                """, fixture.writer().getId(), fixture.otherUser().getId());
        jdbcTemplate.update("""
                -- chat_run：删除已无消息引用的 F29 运行记录。
                DELETE FROM chat_run WHERE session_id IN (
                    SELECT id FROM chat_session WHERE user_id IN (?, ?)
                )
                """, fixture.writer().getId(), fixture.otherUser().getId());
        jdbcTemplate.update("""
                -- chat_session_kb：删除 F29 会话与知识库的固定范围关系。
                DELETE FROM chat_session_kb WHERE session_id IN (
                    SELECT id FROM chat_session WHERE user_id IN (?, ?)
                )
                """, fixture.writer().getId(), fixture.otherUser().getId());
        jdbcTemplate.update("""
                -- chat_session：删除 F29 两名普通用户创建的测试会话。
                DELETE FROM chat_session WHERE user_id IN (?, ?)
                """, fixture.writer().getId(), fixture.otherUser().getId());
        jdbcTemplate.update("""
                -- vector_store：删除 F29 文档分块对应的向量正文。
                DELETE FROM vector_store WHERE id IN (
                    SELECT chunk.id FROM document_chunk chunk
                    INNER JOIN document_version version ON version.id = chunk.version_id
                    INNER JOIN document ON document.id = version.document_id
                    WHERE document.kb_id IN (?, ?)
                )
                """, fixture.writerKnowledgeBaseId(), fixture.otherKnowledgeBaseId());
        jdbcTemplate.update("""
                -- document_chunk：删除 F29 知识库内全部测试分块定位。
                DELETE FROM document_chunk WHERE version_id IN (
                    SELECT version.id FROM document_version version
                    INNER JOIN document ON document.id = version.document_id
                    WHERE document.kb_id IN (?, ?)
                )
                """, fixture.writerKnowledgeBaseId(), fixture.otherKnowledgeBaseId());
        jdbcTemplate.update("""
                -- ingestion_job：删除 F29 文档版本对应的持久化处理任务。
                DELETE FROM ingestion_job WHERE version_id IN (
                    SELECT version.id FROM document_version version
                    INNER JOIN document ON document.id = version.document_id
                    WHERE document.kb_id IN (?, ?)
                )
                """, fixture.writerKnowledgeBaseId(), fixture.otherKnowledgeBaseId());
        jdbcTemplate.update("""
                -- document：清空版本指针后才能按复合外键顺序删除版本。
                UPDATE document SET active_version_id = NULL, pending_version_id = NULL
                WHERE kb_id IN (?, ?)
                """, fixture.writerKnowledgeBaseId(), fixture.otherKnowledgeBaseId());
        jdbcTemplate.update("""
                -- document_version：删除 F29 知识库内全部文档版本。
                DELETE FROM document_version WHERE document_id IN (
                    SELECT id FROM document WHERE kb_id IN (?, ?)
                )
                """, fixture.writerKnowledgeBaseId(), fixture.otherKnowledgeBaseId());
        jdbcTemplate.update("""
                -- document：删除 F29 知识库内的逻辑文档。
                DELETE FROM document WHERE kb_id IN (?, ?)
                """, fixture.writerKnowledgeBaseId(), fixture.otherKnowledgeBaseId());
        jdbcTemplate.update("""
                -- ticket：删除 F29 两个隔离知识库的工单夹具。
                DELETE FROM ticket WHERE kb_id IN (?, ?)
                """, fixture.writerKnowledgeBaseId(), fixture.otherKnowledgeBaseId());
        jdbcTemplate.update("""
                -- kb_member、knowledge_base：先删除成员关系，再删除 F29 知识库。
                DELETE FROM kb_member WHERE kb_id IN (?, ?)
                """, fixture.writerKnowledgeBaseId(), fixture.otherKnowledgeBaseId());
        jdbcTemplate.update("""
                -- knowledge_base：删除 F29 两个临时授权范围。
                DELETE FROM knowledge_base WHERE id IN (?, ?)
                """, fixture.writerKnowledgeBaseId(), fixture.otherKnowledgeBaseId());
        jdbcTemplate.update("""
                -- app_user：最后删除两个普通用户和无成员管理员。
                DELETE FROM app_user WHERE id IN (?, ?, ?)
                """, fixture.writer().getId(), fixture.otherUser().getId(), fixture.administrator().getId());
        if (document != null) {
            try {
                Files.deleteIfExists(STORAGE_ROOT.resolve(document.storageKey()));
            } catch (IOException exception) {
                throw new AssertionError("未能清理 F29 测试原文文件", exception);
            }
        }
    }

    private void useUser(AuthenticatedUser user) {
        when(activeAccountGuard.currentUser()).thenReturn(user);
        when(activeAccountGuard.currentUserForUpdate()).thenReturn(user);
    }

    private MockMultipartFile upload(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/markdown",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private float[] queryVector(int dimensions) {
        float[] vector = new float[dimensions];
        vector[0] = 1.0F;
        return vector;
    }

    private String vectorLiteral(int dimensions) {
        return "[1," + "0,".repeat(dimensions - 2) + "0]";
    }

    private String ticketNo(String prefix, UUID id) {
        return prefix + "-" + id.toString().substring(0, 8).toUpperCase();
    }

    private record AuthorizationFixture(AuthenticatedUser writer, AuthenticatedUser otherUser,
                                        AuthenticatedUser administrator, UUID writerKnowledgeBaseId,
                                        UUID otherKnowledgeBaseId, String writerTicketNo, String otherTicketNo) {
    }

    private record PublishedDocument(UUID documentId, UUID versionId, UUID jobId, UUID chunkId, String storageKey,
                                     String embeddingProvider, String embeddingModel, int dimensions,
                                     String configHash) {
    }

    private record ConversationFixture(UUID sessionId, UUID runId, UUID assistantMessageId, UUID sourceHandle) {
    }
}
