package com.haha.supportai.conversation;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.haha.supportai.conversation.entity.dto.CreateChatSessionRequest;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.dto.SubmitMessageCommand;
import com.haha.supportai.conversation.entity.vo.ChatSessionResponse;
import com.haha.supportai.conversation.exception.ChatIdempotencyConflictException;
import com.haha.supportai.conversation.exception.ChatRunInProgressException;
import com.haha.supportai.conversation.service.run.ChatRunService;
import com.haha.supportai.conversation.service.session.ChatSessionService;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
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
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key=", "spring.ai.ollama.base-url=http://127.0.0.1:1",
        "spring.ai.ollama.init.pull-model-strategy=never", "support.ai.ingestion.worker.enabled=false"
})
class ChatConversationServiceDatabaseIT {

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired
    ChatSessionService chatSessionService;

    @Autowired
    ChatRunService chatRunService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockBean
    ActiveAccountGuard activeAccountGuard;

    @Test
    void preparesIdempotentRunsAndRejectsDifferentConcurrentRequestsThroughProductionTransactions() throws Exception {
        Fixture fixture = createFixture();
        when(activeAccountGuard.currentUser()).thenReturn(fixture.currentUser());
        ChatSessionResponse session = chatSessionService.create(createSessionRequest(fixture.knowledgeBaseId()));
        UUID clientRequestId = UUID.randomUUID();
        SubmitMessageCommand firstCommand = new SubmitMessageCommand(clientRequestId, "如何排查订单支付失败？");

        List<PreparationAttempt> idempotentAttempts = prepareConcurrently(session.getSessionId(),
                List.of(firstCommand, new SubmitMessageCommand(clientRequestId, "如何排查订单支付失败？")));
        List<PreparedChatRunResponse> idempotentResponses = successfulResponses(idempotentAttempts);
        assertThat(idempotentResponses).hasSize(2);
        assertThat(idempotentResponses)
                .extracting(PreparedChatRunResponse::getRunId)
                .containsOnly(idempotentResponses.getFirst().getRunId());
        assertThat(idempotentResponses)
                .extracting(PreparedChatRunResponse::getUserMessageId)
                .containsOnly(idempotentResponses.getFirst().getUserMessageId());
        assertThat(idempotentResponses)
                .extracting(PreparedChatRunResponse::getAssistantMessageId)
                .containsOnly(idempotentResponses.getFirst().getAssistantMessageId());
        assertThat(idempotentResponses)
                .extracting(PreparedChatRunResponse::isReused)
                .containsExactlyInAnyOrder(false, true);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_run WHERE session_id = ?", Integer.class,
                session.getSessionId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM chat_message
                WHERE session_id = ?
                """, Integer.class, session.getSessionId())).isEqualTo(2);

        assertThatThrownBy(() -> chatRunService.prepare(session.getSessionId(),
                new SubmitMessageCommand(clientRequestId, "请提供另一套排查步骤")))
                .as("IDEMPOTENCY_CONFLICT")
                .isInstanceOf(ChatIdempotencyConflictException.class);

        jdbcTemplate.update("""
                UPDATE chat_run
                SET status = 'COMPLETED', finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, idempotentResponses.getFirst().getRunId());

        List<PreparationAttempt> activeRunAttempts = prepareConcurrently(session.getSessionId(), List.of(
                new SubmitMessageCommand(UUID.randomUUID(), "继续分析支付失败"),
                new SubmitMessageCommand(UUID.randomUUID(), "补充支付失败日志定位方法")));
        assertThat(successfulResponses(activeRunAttempts)).hasSize(1);
        assertThat(activeRunAttempts)
                .extracting(PreparationAttempt::failure)
                .filteredOn(failure -> failure != null)
                .singleElement()
                .as("RUN_IN_PROGRESS")
                .isInstanceOf(ChatRunInProgressException.class);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM chat_run
                WHERE session_id = ?
                  AND status IN ('QUEUED', 'RUNNING')
                """, Integer.class, session.getSessionId())).isEqualTo(1);
    }

    private Fixture createFixture() {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        String username = "f10-service-" + userId;
        jdbcTemplate.update("""
                -- app_user：会话服务事务测试使用的启用当前用户
                INSERT INTO app_user (id, username, password_hash, display_name, role, enabled)
                VALUES (?, ?, 'test-password-hash', 'F10 会话服务测试用户', 'USER', TRUE)
                """, userId, username);
        jdbcTemplate.update("""
                -- knowledge_base：会话创建时固定的启用知识库范围
                INSERT INTO knowledge_base (id, name, description, owner_id, status)
                VALUES (?, ?, '验证 F10 会话服务事务', ?, 'ACTIVE')
                """, knowledgeBaseId, "F10 服务知识库 " + knowledgeBaseId, userId);
        jdbcTemplate.update("""
                -- kb_member：当前用户拥有知识库读取权限，供真实 KnowledgeScopeAccess 校验
                INSERT INTO kb_member (kb_id, user_id, permission, granted_by)
                VALUES (?, ?, 'READ', ?)
                """, knowledgeBaseId, userId, userId);
        return new Fixture(knowledgeBaseId, new AuthenticatedUser(userId, username, "F10 会话服务测试用户",
                UserRoleEnum.USER));
    }

    private CreateChatSessionRequest createSessionRequest(UUID knowledgeBaseId) {
        CreateChatSessionRequest request = new CreateChatSessionRequest();
        request.setKbIds(List.of(knowledgeBaseId));
        request.setMode(ChatSessionModeEnum.RAG);
        return request;
    }

    private List<PreparationAttempt> prepareConcurrently(UUID sessionId, List<SubmitMessageCommand> commands)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(commands.size());
        ExecutorService executor = Executors.newFixedThreadPool(commands.size());
        try {
            List<Callable<PreparationAttempt>> tasks = commands.stream()
                    .map(command -> (Callable<PreparationAttempt>) () -> prepareAfterBarrier(sessionId, command, barrier))
                    .toList();
            List<Future<PreparationAttempt>> futures = executor.invokeAll(tasks, 15, TimeUnit.SECONDS);
            assertThat(futures).allMatch(future -> !future.isCancelled());
            return futures.stream().map(this::awaitAttempt).toList();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private PreparationAttempt prepareAfterBarrier(UUID sessionId, SubmitMessageCommand command, CyclicBarrier barrier)
            throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            return new PreparationAttempt(chatRunService.prepare(sessionId, command), null);
        } catch (RuntimeException exception) {
            return new PreparationAttempt(null, exception);
        }
    }

    private PreparationAttempt awaitAttempt(Future<PreparationAttempt> future) {
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("会话服务并发测试未正常结束", exception);
        }
    }

    private List<PreparedChatRunResponse> successfulResponses(List<PreparationAttempt> attempts) {
        return attempts.stream()
                .map(PreparationAttempt::response)
                .filter(response -> response != null)
                .toList();
    }

    private record Fixture(UUID knowledgeBaseId, AuthenticatedUser currentUser) {
    }

    private record PreparationAttempt(PreparedChatRunResponse response, RuntimeException failure) {
    }
}
