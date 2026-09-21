package com.haha.supportai.conversation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.service.rag.RagRunPersistenceService;
import com.haha.supportai.data.conversation.entity.po.ChatMessage;
import com.haha.supportai.data.conversation.entity.po.ChatRun;
import com.haha.supportai.data.conversation.entity.po.ChatSession;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.ChatMessageRoleEnum;
import com.haha.supportai.data.conversation.enums.ChatMessageStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatRunRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionRepository;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import com.haha.supportai.knowledge.contract.KnowledgeScopeAccess;
import com.haha.supportai.storage.PostgresFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key=", "spring.ai.ollama.base-url=http://127.0.0.1:1",
        "spring.ai.ollama.init.pull-model-strategy=never", "support.ai.ingestion.worker.enabled=false"
})
class ChatConversationMapperDatabaseIT {

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    KnowledgeScopeAccess knowledgeScopeAccess;

    @Autowired
    ChatSessionRepository chatSessionRepository;

    @Autowired
    ChatSessionKnowledgeBaseRepository chatSessionKnowledgeBaseRepository;

    @Autowired
    ChatRunRepository chatRunRepository;

    @Autowired
    ChatMessageRepository chatMessageRepository;

    @Autowired
    MessageSourceRepository messageSourceRepository;

    @Autowired
    RagRunPersistenceService ragRunPersistenceService;

    @Test
    @Transactional
    void roundTripsAuthorizedSessionRunAndMessagesThroughProductionMappers() {
        UUID userId = UUID.randomUUID();
        UUID firstKnowledgeBaseId = UUID.randomUUID();
        UUID secondKnowledgeBaseId = UUID.randomUUID();
        UUID outOfScopeKnowledgeBaseId = UUID.randomUUID();
        createAuthorizedKnowledgeBase(userId, firstKnowledgeBaseId, "会话映射知识库一", true);
        createAuthorizedKnowledgeBase(userId, secondKnowledgeBaseId, "会话映射知识库二", false);
        createAuthorizedKnowledgeBase(userId, outOfScopeKnowledgeBaseId, "未加入会话的知识库", false);

        List<UUID> knowledgeBaseIds = List.of(firstKnowledgeBaseId, secondKnowledgeBaseId).stream()
                .sorted()
                .toList();
        assertThat(knowledgeScopeAccess.lockAllReadable(userId, knowledgeBaseIds)).isTrue();
        assertThat(knowledgeScopeAccess.lockAllReadable(userId, List.of(UUID.randomUUID()))).isFalse();

        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T08:00:00Z");
        ChatSession session = new ChatSession(UUID.randomUUID(), userId, "新会话", ChatSessionModeEnum.RAG,
                1, null, now, now);
        chatSessionRepository.insert(session);
        chatSessionKnowledgeBaseRepository.insert(session.getId(), knowledgeBaseIds, now);

        ChatSession lockedSession = chatSessionRepository.findOwnedByIdForUpdate(session.getId(), userId)
                .orElseThrow();
        assertThat(lockedSession.getMode()).isEqualTo(ChatSessionModeEnum.RAG);
        assertThat(chatSessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(session.getId()))
                .containsExactlyElementsOf(knowledgeBaseIds);
        assertThat(chatSessionRepository.advanceNextMessageSeq(session.getId(), 1)).isTrue();

        ChatRun run = new ChatRun(UUID.randomUUID(), session.getId(), UUID.randomUUID(), "a".repeat(64),
                ChatRunStatusEnum.QUEUED, "{\"mode\":\"RAG\"}", null, "{}", null, null, null, null,
                null, now, now);
        chatRunRepository.insert(run);
        ChatRun storedRun = chatRunRepository.findBySessionIdAndClientRequestId(session.getId(),
                        run.getClientRequestId())
                .orElseThrow();
        assertThat(storedRun.getStatus()).isEqualTo(ChatRunStatusEnum.QUEUED);
        assertThat(storedRun.getConfigSnapshot()).contains("\"mode\"");
        assertThat(chatRunRepository.findActiveBySessionId(session.getId()).orElseThrow().getId())
                .isEqualTo(run.getId());

        ChatMessage userMessage = new ChatMessage(UUID.randomUUID(), run.getId(), session.getId(), 1,
                ChatMessageRoleEnum.USER, "如何排查订单异常？", ChatMessageStatusEnum.COMPLETED, now, now);
        ChatMessage assistantMessage = new ChatMessage(UUID.randomUUID(), run.getId(), session.getId(), 2,
                ChatMessageRoleEnum.ASSISTANT, "", ChatMessageStatusEnum.STREAMING, now, now);
        chatMessageRepository.insert(userMessage);
        chatMessageRepository.insert(assistantMessage);

        assertThat(chatMessageRepository.listByRunId(run.getId()))
                .extracting(ChatMessage::getRole)
                .containsExactly(ChatMessageRoleEnum.USER, ChatMessageRoleEnum.ASSISTANT);
        UUID firstChunkId = createReadyDocumentChunk(userId, firstKnowledgeBaseId);
        UUID secondChunkId = createReadyDocumentChunk(userId, secondKnowledgeBaseId);
        UUID outOfScopeChunkId = createReadyDocumentChunk(userId, outOfScopeKnowledgeBaseId);
        MessageSource source = new MessageSource(UUID.randomUUID(), assistantMessage.getId(), null, 1, false,
                MessageSourceTypeEnum.DOCUMENT, firstKnowledgeBaseId, firstChunkId,
                "{\"section\":\"映射测试\"}", "{\"documentName\":\"映射测试文档\"}", now);
        MessageSource secondSource = new MessageSource(UUID.randomUUID(), assistantMessage.getId(), null, 2, false,
                MessageSourceTypeEnum.DOCUMENT, secondKnowledgeBaseId, secondChunkId,
                "{\"section\":\"批量映射测试\"}", "{\"documentName\":\"映射测试文档二\"}", now);
        assertThat(chatRunRepository.markRunning(run.getId(), session.getId(), userMessage.getId(),
                assistantMessage.getId(), "错误问题", now.plusSeconds(1))).isFalse();
        assertThat(chatRunRepository.markRunning(run.getId(), session.getId(), userMessage.getId(),
                assistantMessage.getId(), userMessage.getContent(), now.plusSeconds(1))).isTrue();
        assertThat(chatRunRepository.markRunning(run.getId(), session.getId(), userMessage.getId(),
                assistantMessage.getId(), userMessage.getContent(), now.plusSeconds(1))).isFalse();
        MessageSource mismatchedSource = new MessageSource(UUID.randomUUID(), assistantMessage.getId(), null, 1,
                false, MessageSourceTypeEnum.DOCUMENT, secondKnowledgeBaseId, firstChunkId,
                "{\"section\":\"伪造归属\"}", "{\"documentName\":\"映射测试文档\"}", now);
        MessageSource outOfScopeSource = new MessageSource(UUID.randomUUID(), assistantMessage.getId(), null, 1,
                false, MessageSourceTypeEnum.DOCUMENT, outOfScopeKnowledgeBaseId, outOfScopeChunkId,
                "{\"section\":\"范围外来源\"}", "{\"documentName\":\"范围外文档\"}", now);
        assertThat(messageSourceRepository.insertAll(run.getId(), List.of(mismatchedSource))).isZero();
        assertThat(messageSourceRepository.insertAll(run.getId(), List.of(outOfScopeSource))).isZero();
        assertThat(messageSourceRepository.insertAll(run.getId(), List.of(source, secondSource))).isEqualTo(2);
        assertThat(messageSourceRepository.listByMessageId(assistantMessage.getId()))
                .hasSize(2)
                .first()
                .satisfies(storedSource -> {
                    assertThat(storedSource.getCitationNo()).isEqualTo(1);
                    assertThat(storedSource.isCited()).isFalse();
                    assertThat(storedSource.getLocator()).contains("映射测试");
                });
        assertThat(messageSourceRepository.markCited(assistantMessage.getId(), List.of(1))).isEqualTo(1);
        assertThat(chatMessageRepository.completeAssistant(run.getId(), assistantMessage.getId(), "   ",
                now.plusSeconds(2))).isFalse();
        assertThat(chatMessageRepository.completeAssistant(run.getId(), assistantMessage.getId(), "请核对来源。[1]",
                now.plusSeconds(2))).isTrue();
        assertThat(chatRunRepository.complete(run.getId(), null,
                "{\"evidenceCount\":2,\"citedCount\":1}", now.plusSeconds(2))).isTrue();
        assertThat(chatMessageRepository.failAssistant(run.getId(), assistantMessage.getId(),
                now.plusSeconds(3))).isFalse();
        assertThat(chatRunRepository.complete(run.getId(), null, "{}", now.plusSeconds(3))).isFalse();
        assertThat(chatRunRepository.fail(run.getId(), "LATE_FAILURE", "终态不可覆盖",
                now.plusSeconds(3))).isFalse();
        assertThat(chatRunRepository.findBySessionIdAndClientRequestId(session.getId(), run.getClientRequestId())
                .orElseThrow().getStatus()).isEqualTo(ChatRunStatusEnum.COMPLETED);
        assertThat(chatMessageRepository.listByRunId(run.getId()))
                .filteredOn(message -> message.getRole() == ChatMessageRoleEnum.ASSISTANT)
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.getStatus()).isEqualTo(ChatMessageStatusEnum.COMPLETED);
                    assertThat(message.getContent()).isEqualTo("请核对来源。[1]");
                });
        assertThat(jdbcTemplate.queryForObject("SELECT next_message_seq FROM chat_session WHERE id = ?",
                Long.class, session.getId())).isEqualTo(3L);
    }

    @Test
    void rollsBackRunClaimAndValidSourceWhenAnyBatchSourceIsOutsideSessionScope() {
        UUID userId = UUID.randomUUID();
        UUID sessionKnowledgeBaseId = UUID.randomUUID();
        UUID outOfScopeKnowledgeBaseId = UUID.randomUUID();
        createAuthorizedKnowledgeBase(userId, sessionKnowledgeBaseId, "批量回滚会话知识库", true);
        createAuthorizedKnowledgeBase(userId, outOfScopeKnowledgeBaseId, "批量回滚范围外知识库", false);

        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T09:00:00Z");
        ChatSession session = new ChatSession(UUID.randomUUID(), userId, "批量回滚会话", ChatSessionModeEnum.RAG,
                3, null, now, now);
        chatSessionRepository.insert(session);
        chatSessionKnowledgeBaseRepository.insert(session.getId(), List.of(sessionKnowledgeBaseId), now);

        ChatRun run = new ChatRun(UUID.randomUUID(), session.getId(), UUID.randomUUID(), "b".repeat(64),
                ChatRunStatusEnum.QUEUED, "{\"mode\":\"RAG\"}", null, "{}", null, null, null, null,
                null, now, now);
        chatRunRepository.insert(run);
        ChatMessage userMessage = new ChatMessage(UUID.randomUUID(), run.getId(), session.getId(), 1,
                ChatMessageRoleEnum.USER, "验证来源批量回滚", ChatMessageStatusEnum.COMPLETED, now, now);
        ChatMessage assistantMessage = new ChatMessage(UUID.randomUUID(), run.getId(), session.getId(), 2,
                ChatMessageRoleEnum.ASSISTANT, "", ChatMessageStatusEnum.STREAMING, now, now);
        chatMessageRepository.insert(userMessage);
        chatMessageRepository.insert(assistantMessage);

        UUID validChunkId = createReadyDocumentChunk(userId, sessionKnowledgeBaseId);
        UUID invalidChunkId = createReadyDocumentChunk(userId, outOfScopeKnowledgeBaseId);
        MessageSource validSource = new MessageSource(UUID.randomUUID(), assistantMessage.getId(), null, 1, false,
                MessageSourceTypeEnum.DOCUMENT, sessionKnowledgeBaseId, validChunkId, "{}", "{}", now);
        MessageSource invalidSource = new MessageSource(UUID.randomUUID(), assistantMessage.getId(), null, 2, false,
                MessageSourceTypeEnum.DOCUMENT, outOfScopeKnowledgeBaseId, invalidChunkId, "{}", "{}", now);
        PreparedChatRunResponse preparedRun = new PreparedChatRunResponse(run.getId(), session.getId(),
                userMessage.getId(), assistantMessage.getId(), userMessage.getContent(), ChatRunStatusEnum.QUEUED,
                false);

        assertThatThrownBy(() -> ragRunPersistenceService.startWithEvidence(preparedRun,
                List.of(validSource, invalidSource)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("问答证据在登记前已失效");
        assertThat(messageSourceRepository.listByMessageId(assistantMessage.getId())).isEmpty();
        assertThat(chatRunRepository.findBySessionIdAndClientRequestId(session.getId(), run.getClientRequestId())
                .orElseThrow().getStatus()).isEqualTo(ChatRunStatusEnum.QUEUED);
    }

    private UUID createReadyDocumentChunk(UUID userId, UUID knowledgeBaseId) {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        jdbcTemplate.update("""
                -- document：为 message_source 生产 Mapper 测试建立来源文档。
                INSERT INTO document (id, kb_id, name, next_version_no, created_by)
                VALUES (?, ?, '映射测试文档', 2, ?)
                """, documentId, knowledgeBaseId, userId);
        jdbcTemplate.update("""
                -- document_version：为来源分块建立 READY 版本。
                INSERT INTO document_version (
                    id, document_id, version_no, storage_key, file_hash, file_size, media_type,
                    original_filename, status, parser_version, chunker_version, embedding_model,
                    dimensions, config_hash, config_snapshot, chunk_count
                ) VALUES (?, ?, 1, ?, ?, 4, 'text/plain', 'mapper-source.txt', 'READY',
                    'plain-text-v1', 'token-budget-v1', 'bge-m3', 1024, ?, CAST('{}' AS JSONB), 1)
                """, versionId, documentId, "test/" + versionId + ".txt",
                versionId.toString().replace("-", "").repeat(2),
                documentId.toString().replace("-", "").repeat(2));
        jdbcTemplate.update("UPDATE document SET active_version_id = ? WHERE id = ?", versionId, documentId);
        jdbcTemplate.update("""
                -- document_chunk：message_source 外键所需的稳定分块。
                INSERT INTO document_chunk (
                    id, version_id, chunk_index, section, start_offset, end_offset,
                    content_hash, char_count, token_count
                ) VALUES (?, ?, 0, '映射测试', 0, 4, ?, 4, 1)
                """, chunkId, versionId, chunkId.toString().replace("-", "").repeat(2));
        jdbcTemplate.update("""
                -- vector_store：来源登记事务必须再次确认模型实际读取的正文仍然存在。
                INSERT INTO vector_store (id, content, metadata, embedding)
                VALUES (?, '映射测试正文', CAST('{}' AS JSON), CAST(? AS vector))
                """, chunkId, "[" + "0,".repeat(1023) + "0]");
        return chunkId;
    }

    private void createAuthorizedKnowledgeBase(UUID userId, UUID knowledgeBaseId, String name,
            boolean createUser) {
        if (createUser) {
            jdbcTemplate.update("""
                    -- app_user：生产 Mapper 集成测试所需的启用用户
                    INSERT INTO app_user (id, username, password_hash, display_name, role, enabled)
                    VALUES (?, ?, 'test-password-hash', '会话映射测试用户', 'USER', TRUE)
                    """, userId, "conversation-mapper-" + userId);
        }
        jdbcTemplate.update("""
                -- knowledge_base：会话固定范围中的启用知识库
                INSERT INTO knowledge_base (id, name, description, owner_id, status)
                VALUES (?, ?, '验证 F10 生产 Mapper', ?, 'ACTIVE')
                """, knowledgeBaseId, name, userId);
        jdbcTemplate.update("""
                -- kb_member：当前用户对会话知识库的读取成员关系
                INSERT INTO kb_member (kb_id, user_id, permission, granted_by)
                VALUES (?, ?, 'READ', ?)
                """, knowledgeBaseId, userId, userId);
    }
}
