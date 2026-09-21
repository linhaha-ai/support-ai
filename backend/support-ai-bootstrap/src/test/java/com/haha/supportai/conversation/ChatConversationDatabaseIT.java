package com.haha.supportai.conversation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.haha.supportai.storage.PostgresFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ChatConversationDatabaseIT {

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    private static int firstMigrationCount;
    private static int repeatedMigrationCount;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateDatabase() {
        var migrations = PostgresFixture.migrations(DATABASE, false);
        firstMigrationCount = migrations.migrate().migrationsExecuted;
        repeatedMigrationCount = migrations.migrate().migrationsExecuted;
        jdbc = PostgresFixture.jdbc(DATABASE);
    }

    @Test
    void migratesConversationTablesIdempotentlyAndRetainsDescriptions() {
        assertThat(firstMigrationCount).isGreaterThanOrEqualTo(10);
        assertThat(repeatedMigrationCount).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version = '10'
                  AND success = TRUE
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_name IN ('chat_session', 'chat_session_kb', 'chat_run', 'chat_message')
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT obj_description('chat_session'::regclass)", String.class))
                .isEqualTo("会话表，保存用户创建的 RAG 会话及其消息序号分配状态");
        assertThat(jdbc.queryForObject("""
                SELECT col_description('chat_run'::regclass,
                    (SELECT attnum FROM pg_attribute
                     WHERE attrelid = 'chat_run'::regclass AND attname = 'client_request_id'))
                """, String.class)).isEqualTo("客户端幂等请求 UUID，会话内唯一");
        assertThat(jdbc.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE tablename IN ('chat_session', 'chat_session_kb', 'chat_run', 'chat_message')
                """, String.class)).contains(
                        "uq_chat_run_active_session",
                        "idx_chat_session_user_updated_at",
                        "idx_chat_session_kb_kb_session",
                        "idx_chat_run_session_created_at",
                        "idx_chat_message_run_id");
    }

    @Test
    void enforcesIdempotencyAndOneActiveRunPerSession() {
        ConversationFixture fixture = createFixture(jdbc);
        UUID requestId = UUID.randomUUID();
        insertRun(jdbc, UUID.randomUUID(), fixture.sessionId(), requestId, "QUEUED", "{}", null, "{}");

        assertThatThrownBy(() -> insertRun(jdbc, UUID.randomUUID(), fixture.sessionId(), requestId,
                "COMPLETED", "{}", null, "{}"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertRun(jdbc, UUID.randomUUID(), fixture.sessionId(), UUID.randomUUID(),
                "RUNNING", "{}", null, "{}"))
                .isInstanceOf(DataAccessException.class);

        jdbc.update("""
                UPDATE chat_run
                SET status = 'COMPLETED', finished_at = CURRENT_TIMESTAMP
                WHERE session_id = ?
                """, fixture.sessionId());
        insertRun(jdbc, UUID.randomUUID(), fixture.sessionId(), UUID.randomUUID(), "RUNNING", "{}", null, "{}");
    }

    @Test
    void concurrentTransactionsAllowOnlyOneQueuedRunPerSession() throws Exception {
        ConversationFixture fixture = createFixture(jdbc);
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Callable<Boolean>> tasks = List.of(
                    () -> insertQueuedRunInSeparateTransaction(fixture.sessionId(), UUID.randomUUID(), startBarrier),
                    () -> insertQueuedRunInSeparateTransaction(fixture.sessionId(), UUID.randomUUID(), startBarrier));
            List<Future<Boolean>> results = executor.invokeAll(tasks, 10, TimeUnit.SECONDS);

            assertThat(results).allMatch(result -> !result.isCancelled());
            assertThat(results.stream().filter(this::completedSuccessfully).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*)
                    FROM chat_run
                    WHERE session_id = ?
                      AND status IN ('QUEUED', 'RUNNING')
                    """, Integer.class, fixture.sessionId())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void enforcesMessageOrderingRoleAndRunSessionOwnership() {
        ConversationFixture fixture = createFixture(jdbc);
        UUID runId = UUID.randomUUID();
        insertRun(jdbc, runId, fixture.sessionId(), UUID.randomUUID(), "QUEUED", "{}", null, "{}");
        insertMessage(jdbc, UUID.randomUUID(), runId, fixture.sessionId(), 1, "USER", "问题", "COMPLETED");
        insertMessage(jdbc, UUID.randomUUID(), runId, fixture.sessionId(), 2, "ASSISTANT", "", "STREAMING");

        assertThatThrownBy(() -> insertMessage(jdbc, UUID.randomUUID(), runId, fixture.sessionId(), 3,
                "ASSISTANT", "重复角色", "STREAMING"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertMessage(jdbc, UUID.randomUUID(), runId, fixture.sessionId(), 1,
                "USER", "重复序号", "COMPLETED"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertMessage(jdbc, UUID.randomUUID(), runId, fixture.sessionId(), 3,
                "USER", "非法状态", "STREAMING"))
                .isInstanceOf(DataAccessException.class);

        ConversationFixture otherFixture = createFixture(jdbc);
        assertThatThrownBy(() -> insertMessage(jdbc, UUID.randomUUID(), runId, otherFixture.sessionId(), 1,
                "USER", "跨会话关联", "COMPLETED"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void acceptsOnlyJsonObjectsForRunSnapshotsAndMetrics() {
        ConversationFixture fixture = createFixture(jdbc);

        assertThatThrownBy(() -> insertRun(jdbc, UUID.randomUUID(), fixture.sessionId(), UUID.randomUUID(),
                "QUEUED", "[]", null, "{}"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertRun(jdbc, UUID.randomUUID(), fixture.sessionId(), UUID.randomUUID(),
                "QUEUED", "{}", "[]", "{}"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertRun(jdbc, UUID.randomUUID(), fixture.sessionId(), UUID.randomUUID(),
                "QUEUED", "{}", null, "[]"))
                .isInstanceOf(DataAccessException.class);
    }

    private boolean insertQueuedRunInSeparateTransaction(UUID sessionId, UUID clientRequestId,
            CyclicBarrier startBarrier) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            connection.setAutoCommit(false);
            try (var timeoutStatement = connection.createStatement()) {
                timeoutStatement.execute("SET LOCAL lock_timeout = '5s'");
                timeoutStatement.execute("SET LOCAL statement_timeout = '8s'");
            }
            startBarrier.await(5, TimeUnit.SECONDS);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO chat_run (
                        id, session_id, client_request_id, request_hash, status, config_snapshot, usage, metrics
                    ) VALUES (?, ?, ?, ?, 'QUEUED', CAST(? AS JSONB), CAST(? AS JSONB), CAST(? AS JSONB))
                    """)) {
                statement.setQueryTimeout(8);
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, sessionId);
                statement.setObject(3, clientRequestId);
                statement.setString(4, "a".repeat(64));
                statement.setString(5, "{}");
                statement.setNull(6, Types.VARCHAR);
                statement.setString(7, "{}");
                statement.executeUpdate();
                Thread.sleep(300);
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                if ("23505".equals(exception.getSQLState())) {
                    return false;
                }
                throw exception;
            }
        }
    }

    private boolean completedSuccessfully(Future<Boolean> result) {
        try {
            return result.get(1, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("并发运行约束测试未正常结束", exception);
        }
    }

    private ConversationFixture createFixture(JdbcTemplate jdbc) {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String username = "conversation" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, display_name, role, enabled)
                VALUES (?, ?, 'test-password-hash', '会话约束测试用户', 'USER', TRUE)
                """, userId, username);
        jdbc.update("""
                INSERT INTO knowledge_base (id, name, description, owner_id, status)
                VALUES (?, '会话约束测试知识库', '会话数据表外键测试', ?, 'ACTIVE')
                """, knowledgeBaseId, userId);
        jdbc.update("""
                INSERT INTO chat_session (id, user_id, title, mode, next_message_seq)
                VALUES (?, ?, '新会话', 'RAG', 1)
                """, sessionId, userId);
        jdbc.update("INSERT INTO chat_session_kb (session_id, kb_id) VALUES (?, ?)", sessionId, knowledgeBaseId);
        return new ConversationFixture(sessionId);
    }

    private void insertRun(JdbcTemplate jdbc, UUID runId, UUID sessionId, UUID requestId, String status,
            String configSnapshot, String usage, String metrics) {
        jdbc.update("""
                INSERT INTO chat_run (
                    id, session_id, client_request_id, request_hash, status, config_snapshot, usage, metrics,
                    finished_at
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), CAST(? AS JSONB),
                    CASE WHEN ? IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, runId, sessionId, requestId, "a".repeat(64), status, configSnapshot, usage, metrics, status);
    }

    private void insertMessage(JdbcTemplate jdbc, UUID messageId, UUID runId, UUID sessionId, long sequence,
            String role, String content, String status) {
        jdbc.update("""
                INSERT INTO chat_message (id, run_id, session_id, seq, role, content, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, messageId, runId, sessionId, sequence, role, content, status);
    }

    private record ConversationFixture(UUID sessionId) {
    }
}
