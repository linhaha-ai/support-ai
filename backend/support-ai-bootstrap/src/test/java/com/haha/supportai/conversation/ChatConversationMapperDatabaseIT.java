package com.haha.supportai.conversation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.conversation.entity.po.ChatMessage;
import com.haha.supportai.data.conversation.entity.po.ChatRun;
import com.haha.supportai.data.conversation.entity.po.ChatSession;
import com.haha.supportai.data.conversation.enums.ChatMessageRoleEnum;
import com.haha.supportai.data.conversation.enums.ChatMessageStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatRunRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionRepository;
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

    @Test
    @Transactional
    void roundTripsAuthorizedSessionRunAndMessagesThroughProductionMappers() {
        UUID userId = UUID.randomUUID();
        UUID firstKnowledgeBaseId = UUID.randomUUID();
        UUID secondKnowledgeBaseId = UUID.randomUUID();
        createAuthorizedKnowledgeBase(userId, firstKnowledgeBaseId, "会话映射知识库一", true);
        createAuthorizedKnowledgeBase(userId, secondKnowledgeBaseId, "会话映射知识库二", false);

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
        assertThat(jdbcTemplate.queryForObject("SELECT next_message_seq FROM chat_session WHERE id = ?",
                Long.class, session.getId())).isEqualTo(3L);
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
