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
        assertThat(firstMigrationCount).isGreaterThanOrEqualTo(13);
        assertThat(repeatedMigrationCount).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version IN ('10', '11', '13')
                  AND success = TRUE
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_name IN (
                    'chat_session', 'chat_session_kb', 'chat_run', 'chat_message', 'message_source', 'tool_call'
                )
                """, Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT obj_description('chat_session'::regclass)", String.class))
                .isEqualTo("会话表，保存用户创建的 RAG 会话及其消息序号分配状态");
        assertThat(jdbc.queryForObject("""
                SELECT col_description('chat_run'::regclass,
                    (SELECT attnum FROM pg_attribute
                     WHERE attrelid = 'chat_run'::regclass AND attname = 'client_request_id'))
                """, String.class)).isEqualTo("客户端幂等请求 UUID，会话内唯一");
        assertThat(jdbc.queryForObject("SELECT obj_description('message_source'::regclass)", String.class))
                .isEqualTo("消息来源表，保存实际进入模型的文档证据、工单结果及最终引用状态");
        assertThat(jdbc.queryForObject("SELECT obj_description('tool_call'::regclass)", String.class))
                .isEqualTo("受控工具调用审计表，保存每次只读工具执行的参数、受限结果和终态");
        assertThat(jdbc.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE tablename IN (
                    'chat_session', 'chat_session_kb', 'chat_run', 'chat_message', 'message_source', 'tool_call'
                )
                """, String.class)).contains(
                        "uq_chat_run_active_session",
                        "idx_chat_session_user_updated_at",
                        "idx_chat_session_kb_kb_session",
                        "idx_chat_run_session_created_at",
                        "idx_chat_message_run_id",
                        "idx_message_source_chunk_id",
                        "idx_message_source_kb_id",
                        "idx_message_source_origin_source_id",
                        "idx_message_source_ticket_id",
                        "idx_message_source_tool_call_id",
                        "idx_tool_call_run_id");
    }

    @Test
    void enforcesDocumentSourceCitationTypeAndJsonConstraints() {
        ConversationFixture fixture = createFixture(jdbc);
        UUID runId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        UUID chunkId = createReadyDocumentChunk(fixture);
        insertRun(jdbc, runId, fixture.sessionId(), UUID.randomUUID(), "RUNNING", "{}", null, "{}");
        insertMessage(jdbc, assistantMessageId, runId, fixture.sessionId(), 1,
                "ASSISTANT", "", "STREAMING");

        insertMessageSource(assistantMessageId, fixture.knowledgeBaseId(), chunkId, 1,
                "DOCUMENT", "{}", "{}");
        assertThat(jdbc.queryForObject("""
                SELECT is_cited
                FROM message_source
                WHERE message_id = ? AND citation_no = 1
                """, Boolean.class, assistantMessageId)).isFalse();
        assertThatThrownBy(() -> insertMessageSource(assistantMessageId, fixture.knowledgeBaseId(), chunkId, 1,
                "DOCUMENT", "{}", "{}"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertMessageSource(assistantMessageId, fixture.knowledgeBaseId(), chunkId, 0,
                "DOCUMENT", "{}", "{}"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertMessageSource(assistantMessageId, fixture.knowledgeBaseId(), chunkId, 2,
                "TICKET", "{}", "{}"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertMessageSource(assistantMessageId, fixture.knowledgeBaseId(), chunkId, 2,
                "DOCUMENT", "[]", "{}"))
                .isInstanceOf(DataAccessException.class);
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
        return new ConversationFixture(userId, knowledgeBaseId, sessionId);
    }

    private UUID createReadyDocumentChunk(ConversationFixture fixture) {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document (id, kb_id, name, next_version_no, created_by)
                VALUES (?, ?, '来源约束测试文档', 2, ?)
                """, documentId, fixture.knowledgeBaseId(), fixture.userId());
        jdbc.update("""
                INSERT INTO document_version (
                    id, document_id, version_no, storage_key, file_hash, file_size, media_type,
                    original_filename, status, parser_version, chunker_version, embedding_model,
                    dimensions, config_hash, config_snapshot, chunk_count
                ) VALUES (?, ?, 1, 'test/source.txt', ?, 4, 'text/plain', 'source.txt', 'READY',
                    'plain-text-v1', 'token-budget-v1', 'bge-m3', 1024, ?, CAST('{}' AS JSONB), 1)
                """, versionId, documentId, "a".repeat(64), "b".repeat(64));
        jdbc.update("UPDATE document SET active_version_id = ? WHERE id = ?", versionId, documentId);
        jdbc.update("""
                INSERT INTO document_chunk (
                    id, version_id, chunk_index, section, start_offset, end_offset,
                    content_hash, char_count, token_count
                ) VALUES (?, ?, 0, '约束测试', 0, 4, ?, 4, 1)
                """, chunkId, versionId, "c".repeat(64));
        return chunkId;
    }

    private void insertMessageSource(UUID messageId, UUID knowledgeBaseId, UUID chunkId, int citationNumber,
            String sourceType, String locator, String snapshot) {
        jdbc.update("""
                INSERT INTO message_source (
                    id, message_id, citation_no, is_cited, source_type, kb_id, chunk_id, locator, snapshot
                ) VALUES (?, ?, ?, FALSE, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB))
                """, UUID.randomUUID(), messageId, citationNumber, sourceType, knowledgeBaseId, chunkId,
                locator, snapshot);
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

    private record ConversationFixture(UUID userId, UUID knowledgeBaseId, UUID sessionId) {
    }
}
