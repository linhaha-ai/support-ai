package com.haha.supportai.conversation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.haha.supportai.conversation.entity.dto.ChatRunCancellationResult;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.exception.ChatRunCancelledException;
import com.haha.supportai.conversation.service.rag.RagRunPersistenceService;
import com.haha.supportai.conversation.service.run.ChatRunControlPersistenceService;
import com.haha.supportai.conversation.support.ChatRunConfigurationSnapshotFactory;
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

    @Autowired
    ChatRunControlPersistenceService chatRunControlPersistenceService;

    @Autowired
    ChatRunConfigurationSnapshotFactory chatRunConfigurationSnapshotFactory;

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
                ChatRunStatusEnum.QUEUED, chatRunConfigurationSnapshotFactory.create(), null, "{}", null, null,
                null, null, null, now, now);
        chatRunRepository.insert(run);
        ChatRun storedRun = chatRunRepository.findBySessionIdAndClientRequestId(session.getId(),
                        run.getClientRequestId())
                .orElseThrow();
        assertThat(storedRun.getStatus()).isEqualTo(ChatRunStatusEnum.QUEUED);
        assertThat(storedRun.getConfigSnapshot())
                .contains("\"mode\"")
                .contains("\"chat\"")
                .contains("\"prompt\"")
                .contains("\"retrieval\"")
                .contains("\"index\"");
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
        assertThat(chatMessageRepository.appendAssistantContent(run.getId(), assistantMessage.getId(), "领取前增量",
                now.plusSeconds(1))).isFalse();
        assertThat(chatRunRepository.markRunning(run.getId(), session.getId(), userMessage.getId(),
                assistantMessage.getId(), "错误问题", now.plusSeconds(1))).isFalse();
        assertThat(chatRunRepository.markRunning(run.getId(), session.getId(), userMessage.getId(),
                assistantMessage.getId(), userMessage.getContent(), now.plusSeconds(1))).isTrue();
        assertThat(chatRunRepository.markRunning(run.getId(), session.getId(), userMessage.getId(),
                assistantMessage.getId(), userMessage.getContent(), now.plusSeconds(1))).isFalse();
        assertThat(chatMessageRepository.appendAssistantContent(run.getId(), assistantMessage.getId(), "请核对",
                now.plusSeconds(2))).isTrue();
        assertThat(chatMessageRepository.appendAssistantContent(run.getId(), assistantMessage.getId(), "来源。[1]",
                now.plusSeconds(3))).isTrue();
        assertThat(chatMessageRepository.listByRunId(run.getId()))
                .filteredOn(message -> message.getRole() == ChatMessageRoleEnum.ASSISTANT)
                .singleElement()
                .extracting(ChatMessage::getContent)
                .isEqualTo("请核对来源。[1]");
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
                now.plusSeconds(4))).isFalse();
        assertThat(chatMessageRepository.completeAssistant(run.getId(), assistantMessage.getId(), "请核对来源。[1]",
                now.plusSeconds(4))).isTrue();
        assertThat(chatRunRepository.complete(run.getId(), null,
                "{\"evidenceCount\":2,\"citedCount\":1}", now.plusSeconds(4))).isTrue();
        assertThat(chatMessageRepository.appendAssistantContent(run.getId(), assistantMessage.getId(), "终态后增量",
                now.plusSeconds(5))).isFalse();
        assertThat(chatMessageRepository.failAssistant(run.getId(), assistantMessage.getId(),
                now.plusSeconds(5))).isFalse();
        assertThat(chatRunRepository.complete(run.getId(), null, "{}", now.plusSeconds(5))).isFalse();
        assertThat(chatRunRepository.fail(run.getId(), "LATE_FAILURE", "终态不可覆盖",
                now.plusSeconds(5))).isFalse();
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
    @Transactional
    void persistsPartialAssistantAndOptionalFailureTelemetryAtomically() {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        createAuthorizedKnowledgeBase(userId, knowledgeBaseId, "流式失败映射知识库", true);

        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T10:00:00Z");
        ChatSession session = new ChatSession(UUID.randomUUID(), userId, "流式失败会话", ChatSessionModeEnum.RAG,
                3, null, now, now);
        chatSessionRepository.insert(session);
        chatSessionKnowledgeBaseRepository.insert(session.getId(), List.of(knowledgeBaseId), now);

        ChatRun run = new ChatRun(UUID.randomUUID(), session.getId(), UUID.randomUUID(), "c".repeat(64),
                ChatRunStatusEnum.QUEUED, "{\"mode\":\"RAG\"}", null, "{\"phase\":\"prepared\"}", null,
                null, null, null, null, now, now);
        chatRunRepository.insert(run);
        ChatMessage userMessage = new ChatMessage(UUID.randomUUID(), run.getId(), session.getId(), 1,
                ChatMessageRoleEnum.USER, "如何验证流式失败？", ChatMessageStatusEnum.COMPLETED, now, now);
        ChatMessage assistantMessage = new ChatMessage(UUID.randomUUID(), run.getId(), session.getId(), 2,
                ChatMessageRoleEnum.ASSISTANT, "", ChatMessageStatusEnum.STREAMING, now, now);
        chatMessageRepository.insert(userMessage);
        chatMessageRepository.insert(assistantMessage);

        assertThat(chatRunRepository.markRunning(run.getId(), session.getId(), userMessage.getId(),
                assistantMessage.getId(), userMessage.getContent(), now.plusSeconds(1))).isTrue();
        assertThat(chatMessageRepository.appendAssistantContent(run.getId(), assistantMessage.getId(), "已展示",
                now.plusSeconds(2))).isTrue();
        assertThat(chatMessageRepository.appendAssistantContent(run.getId(), assistantMessage.getId(), "",
                now.plusSeconds(3))).isFalse();
        assertThat(chatMessageRepository.failAssistant(run.getId(), assistantMessage.getId(), "已展示部分回答",
                now.plusSeconds(4))).isTrue();
        assertThat(chatRunRepository.fail(run.getId(), "MODEL_STREAM_FAILED", "模型流式输出失败",
                "{\"outputTokens\":2}", null, now.plusSeconds(4))).isTrue();

        ChatRun failedRun = chatRunRepository.findBySessionIdAndClientRequestId(session.getId(),
                run.getClientRequestId()).orElseThrow();
        assertThat(failedRun.getStatus()).isEqualTo(ChatRunStatusEnum.FAILED);
        assertThat(failedRun.getUsage()).contains("outputTokens").contains("2");
        assertThat(failedRun.getMetrics()).contains("phase").contains("prepared");
        assertThat(failedRun.getErrorCode()).isEqualTo("MODEL_STREAM_FAILED");
        assertThat(failedRun.getErrorDetail()).isEqualTo("模型流式输出失败");
        assertThat(chatMessageRepository.listByRunId(run.getId()))
                .filteredOn(message -> message.getRole() == ChatMessageRoleEnum.ASSISTANT)
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.getStatus()).isEqualTo(ChatMessageStatusEnum.FAILED);
                    assertThat(message.getContent()).isEqualTo("已展示部分回答");
                });
        assertThat(chatMessageRepository.appendAssistantContent(run.getId(), assistantMessage.getId(), "失败后增量",
                now.plusSeconds(5))).isFalse();

        ChatRun unknownUsageRun = new ChatRun(UUID.randomUUID(), session.getId(), UUID.randomUUID(), "d".repeat(64),
                ChatRunStatusEnum.QUEUED, "{\"mode\":\"RAG\"}", null, "{\"phase\":\"queued\"}", null,
                null, null, null, null, now.plusSeconds(6), now.plusSeconds(6));
        chatRunRepository.insert(unknownUsageRun);
        assertThat(chatRunRepository.fail(unknownUsageRun.getId(), "QUEUE_FAILED", "排队阶段失败", null,
                "{\"failureStage\":\"queue\"}", now.plusSeconds(7))).isTrue();
        ChatRun storedUnknownUsageRun = chatRunRepository.findBySessionIdAndClientRequestId(session.getId(),
                unknownUsageRun.getClientRequestId()).orElseThrow();
        assertThat(storedUnknownUsageRun.getUsage()).isNull();
        assertThat(storedUnknownUsageRun.getMetrics()).contains("failureStage").contains("queue");
    }

    @Test
    void rejectsWholeEvidenceBatchAfterProductionRunClaimWhenAnySourceIsOutsideSessionScope() {
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
        PreparedChatRunResponse preparedRun = new PreparedChatRunResponse(run.getId(), session.getId(), userId,
                userMessage.getId(), assistantMessage.getId(), userMessage.getContent(), ChatRunStatusEnum.QUEUED,
                false);

        ragRunPersistenceService.start(preparedRun);
        assertThatThrownBy(() -> ragRunPersistenceService.registerEvidence(preparedRun,
                List.of(validSource, invalidSource)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("问答证据在登记前已失效");
        assertThat(messageSourceRepository.listByMessageId(assistantMessage.getId())).isEmpty();
        jdbcTemplate.update("DELETE FROM kb_member WHERE kb_id = ? AND user_id = ?",
                sessionKnowledgeBaseId, userId);
        assertThatThrownBy(() -> ragRunPersistenceService.registerEvidence(preparedRun, List.of(validSource)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("问答证据在登记前已失效");
        assertThat(messageSourceRepository.listByMessageId(assistantMessage.getId())).isEmpty();
        assertThat(chatRunRepository.findBySessionIdAndClientRequestId(session.getId(), run.getClientRequestId())
                .orElseThrow().getStatus()).isEqualTo(ChatRunStatusEnum.RUNNING);
        ragRunPersistenceService.fail(run.getId(), assistantMessage.getId(), "RAG_PERSISTENCE_FAILED",
                "问答运行状态保存失败");
        assertThat(chatRunRepository.findBySessionIdAndClientRequestId(session.getId(), run.getClientRequestId())
                .orElseThrow().getStatus()).isEqualTo(ChatRunStatusEnum.FAILED);
    }

    @Test
    void completionAndCancellationCompeteForExactlyOneDatabaseTerminalState() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        createAuthorizedKnowledgeBase(userId, knowledgeBaseId, "终态竞争知识库", true);
        OffsetDateTime now = OffsetDateTime.now().minusMinutes(1);
        RunFixture fixture = createQueuedRun(userId, knowledgeBaseId, "终态竞争会话", now);
        assertThat(chatRunRepository.markRunning(fixture.run().getId(), fixture.run().getSessionId(),
                fixture.userMessage().getId(), fixture.assistantMessage().getId(),
                fixture.userMessage().getContent(), now.plusSeconds(1))).isTrue();
        assertThat(chatMessageRepository.appendAssistantContent(fixture.run().getId(),
                fixture.assistantMessage().getId(), "部分回答", now.plusSeconds(2))).isTrue();

        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<RuntimeException> completion = CompletableFuture.supplyAsync(() -> {
            await(start);
            try {
                ragRunPersistenceService.complete(fixture.run().getId(), fixture.assistantMessage().getId(),
                        "完整回答", List.of(), null, "{\"totalMs\":10}");
                return null;
            } catch (RuntimeException exception) {
                return exception;
            }
        });
        CompletableFuture<ChatRunCancellationResult> cancellation = CompletableFuture.supplyAsync(() -> {
            await(start);
            return chatRunControlPersistenceService.cancelOwned(fixture.run().getId(), userId);
        });
        start.countDown();

        RuntimeException completionFailure = completion.get(10, TimeUnit.SECONDS);
        ChatRunCancellationResult cancellationResult = cancellation.get(10, TimeUnit.SECONDS);
        ChatRun storedRun = chatRunRepository.findById(fixture.run().getId()).orElseThrow();
        ChatMessage storedAssistant = assistantMessage(fixture.run().getId());

        assertThat(storedRun.getStatus()).isIn(ChatRunStatusEnum.COMPLETED, ChatRunStatusEnum.CANCELLED);
        if (storedRun.getStatus() == ChatRunStatusEnum.COMPLETED) {
            assertThat(completionFailure).isNull();
            assertThat(cancellationResult.isNewlyCancelled()).isFalse();
            assertThat(cancellationResult.getStatus()).isEqualTo(ChatRunStatusEnum.COMPLETED);
            assertThat(storedAssistant.getStatus()).isEqualTo(ChatMessageStatusEnum.COMPLETED);
            assertThat(storedAssistant.getContent()).isEqualTo("完整回答");
        } else {
            assertThat(completionFailure).isInstanceOf(ChatRunCancelledException.class);
            assertThat(cancellationResult.isNewlyCancelled()).isTrue();
            assertThat(cancellationResult.getStatus()).isEqualTo(ChatRunStatusEnum.CANCELLED);
            assertThat(storedAssistant.getStatus()).isEqualTo(ChatMessageStatusEnum.CANCELLED);
            assertThat(storedAssistant.getContent()).isEqualTo("部分回答");
        }
    }

    @Test
    void startupRecoveryOnlyInterruptsRunsCreatedBeforeBoundary() {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        createAuthorizedKnowledgeBase(userId, knowledgeBaseId, "启动恢复知识库", true);
        OffsetDateTime boundary = OffsetDateTime.parse("2000-01-02T00:00:00Z");
        RunFixture interrupted = createQueuedRun(userId, knowledgeBaseId, "启动前会话",
                boundary.minusDays(1));
        RunFixture retained = createQueuedRun(userId, knowledgeBaseId, "启动后会话",
                boundary.plusDays(1));

        assertThat(chatRunControlPersistenceService.recoverInterrupted(boundary)).isEqualTo(1);

        ChatRun interruptedRun = chatRunRepository.findById(interrupted.run().getId()).orElseThrow();
        assertThat(interruptedRun.getStatus()).isEqualTo(ChatRunStatusEnum.FAILED);
        assertThat(interruptedRun.getErrorCode()).isEqualTo("RUN_INTERRUPTED");
        assertThat(assistantMessage(interrupted.run().getId()).getStatus())
                .isEqualTo(ChatMessageStatusEnum.FAILED);
        assertThat(chatRunRepository.findById(retained.run().getId()).orElseThrow().getStatus())
                .isEqualTo(ChatRunStatusEnum.QUEUED);
        assertThat(assistantMessage(retained.run().getId()).getStatus())
                .isEqualTo(ChatMessageStatusEnum.STREAMING);
    }

    private RunFixture createQueuedRun(UUID userId, UUID knowledgeBaseId, String title, OffsetDateTime createdAt) {
        ChatSession session = new ChatSession(UUID.randomUUID(), userId, title, ChatSessionModeEnum.RAG,
                3, null, createdAt, createdAt);
        chatSessionRepository.insert(session);
        chatSessionKnowledgeBaseRepository.insert(session.getId(), List.of(knowledgeBaseId), createdAt);
        ChatRun run = new ChatRun(UUID.randomUUID(), session.getId(), UUID.randomUUID(), "e".repeat(64),
                ChatRunStatusEnum.QUEUED, "{\"mode\":\"RAG\"}", null, "{}", null, null, null,
                null, null, createdAt, createdAt);
        chatRunRepository.insert(run);
        ChatMessage userMessage = new ChatMessage(UUID.randomUUID(), run.getId(), session.getId(), 1,
                ChatMessageRoleEnum.USER, "验证运行终态", ChatMessageStatusEnum.COMPLETED, createdAt, createdAt);
        ChatMessage assistantMessage = new ChatMessage(UUID.randomUUID(), run.getId(), session.getId(), 2,
                ChatMessageRoleEnum.ASSISTANT, "", ChatMessageStatusEnum.STREAMING, createdAt, createdAt);
        chatMessageRepository.insert(userMessage);
        chatMessageRepository.insert(assistantMessage);
        return new RunFixture(run, userMessage, assistantMessage);
    }

    private ChatMessage assistantMessage(UUID runId) {
        return chatMessageRepository.listByRunId(runId).stream()
                .filter(message -> message.getRole() == ChatMessageRoleEnum.ASSISTANT)
                .findFirst()
                .orElseThrow();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发测试开始超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试等待被中断", exception);
        }
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

    private record RunFixture(ChatRun run, ChatMessage userMessage, ChatMessage assistantMessage) {
    }
}
