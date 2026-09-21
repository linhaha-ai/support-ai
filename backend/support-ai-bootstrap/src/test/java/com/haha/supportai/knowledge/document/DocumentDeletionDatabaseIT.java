package com.haha.supportai.knowledge.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.DocumentCleanupJobRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.knowledge.service.document.DocumentCleanupService;
import com.haha.supportai.storage.PostgresFixture;
import jakarta.servlet.http.Cookie;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key=", "spring.ai.ollama.base-url=http://127.0.0.1:1",
        "spring.ai.ollama.init.pull-model-strategy=never", "support.ai.ingestion.worker.enabled=false",
        "support.ai.document.cleanup.enabled=false"
})
@AutoConfigureMockMvc
class DocumentDeletionDatabaseIT {

    private static final Path STORAGE_ROOT = Path.of("target", "f19-document-deletion-it").toAbsolutePath();

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("support.ai.storage.root", STORAGE_ROOT::toString);
    }

    @BeforeAll
    static void initializeSupportAccounts(@Autowired DataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/seed/identity/init_app_user.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    IngestionJobRepository ingestionJobRepository;

    @Autowired
    DocumentCleanupJobRepository documentCleanupJobRepository;

    @Autowired
    DocumentCleanupService documentCleanupService;

    @Test
    void writeMemberSoftDeletesDocumentCancelsWorkAndInvalidatesIngestionLease() throws Exception {
        DeletionFixture fixture = createFixture();
        try {
            AuthenticatedRequest admin = login("admin", "admin");

            deleteDocument(admin, fixture.documentId()).andExpect(status().isAccepted());

            assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM document WHERE id = ?",
                    Boolean.class, fixture.documentId())).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT active_version_id IS NULL FROM document WHERE id = ?",
                    Boolean.class, fixture.documentId())).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT pending_version_id IS NULL FROM document WHERE id = ?",
                    Boolean.class, fixture.documentId())).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT revision FROM document WHERE id = ?", Long.class,
                    fixture.documentId())).isEqualTo(8L);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM document_version WHERE id = ?", String.class,
                    fixture.pendingVersionId())).isEqualTo("CANCELLED");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
                    fixture.pendingJobId())).isEqualTo("CANCELLED");
            assertThat(jdbcTemplate.queryForObject("SELECT cancel_requested_at IS NOT NULL FROM ingestion_job WHERE id = ?",
                    Boolean.class, fixture.pendingJobId())).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL "
                    + "FROM ingestion_job WHERE id = ?", Boolean.class, fixture.pendingJobId())).isTrue();
            assertThat(ingestionJobRepository.findActiveLeaseForUpdate(fixture.pendingJobId(), fixture.pendingVersionId(),
                    IngestionStageEnum.EMBED, "f19-old-worker", fixture.pendingLeaseToken())).isEmpty();
            assertThat(ingestionJobRepository.claimNextByStage(IngestionStageEnum.EMBED, "f19-new-worker",
                    UUID.randomUUID(), OffsetDateTime.now().plusMinutes(1))).isEmpty();
            assertThat(cleanupJobCount(fixture.documentId())).isEqualTo(1);
            mvc.perform(get("/api/chunks/{chunkId}/source", fixture.activeChunkId())
                            .session(admin.session()).cookie(admin.cookie()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void readMemberCannotDeleteDocument() throws Exception {
        DeletionFixture fixture = createFixture();
        try {
            UUID supportUserId = userId("support1");
            jdbcTemplate.update("""
                    -- kb_member：为 F19 删除权限验收增加只读成员。
                    INSERT INTO kb_member (kb_id, user_id, permission, granted_by, created_at)
                    VALUES (?, ?, 'READ', ?, CURRENT_TIMESTAMP)
                    """, fixture.knowledgeBaseId(), supportUserId, userId("admin"));
            AuthenticatedRequest supportUser = login("support1", "support1");

            deleteDocument(supportUser, fixture.documentId())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));

            assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NULL FROM document WHERE id = ?", Boolean.class,
                    fixture.documentId())).isTrue();
            assertThat(cleanupJobCount(fixture.documentId())).isZero();
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void repeatedWriteDeleteIsIdempotentAndCreatesOnlyOneCleanupJob() throws Exception {
        DeletionFixture fixture = createFixture();
        try {
            AuthenticatedRequest admin = login("admin", "admin");

            deleteDocument(admin, fixture.documentId()).andExpect(status().isAccepted());
            deleteDocument(admin, fixture.documentId()).andExpect(status().isAccepted());

            assertThat(cleanupJobCount(fixture.documentId())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT revision FROM document WHERE id = ?", Long.class,
                    fixture.documentId())).isEqualTo(8L);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
                    fixture.pendingJobId())).isEqualTo("CANCELLED");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void expiredCleanupLeaseCannotMoveRunningJobToRetryWait() throws Exception {
        DeletionFixture fixture = createFixture();
        try {
            AuthenticatedRequest admin = login("admin", "admin");
            deleteDocument(admin, fixture.documentId()).andExpect(status().isAccepted());
            UUID cleanupJobId = jdbcTemplate.queryForObject(
                    "SELECT id FROM document_cleanup_job WHERE document_id = ?", UUID.class, fixture.documentId());
            UUID expiredToken = UUID.randomUUID();
            jdbcTemplate.update("""
                    -- document_cleanup_job：模拟同一执行者的过期 RUNNING 租约，不能再回写重试状态。
                    UPDATE document_cleanup_job
                    SET status = 'RUNNING',
                        attempt = 1,
                        lease_owner = 'f19-expired-cleanup-worker',
                        lease_token = ?,
                        lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second',
                        started_at = CURRENT_TIMESTAMP,
                        finished_at = NULL
                    WHERE id = ?
                    """, expiredToken, cleanupJobId);

            assertThat(documentCleanupJobRepository.markRetryWait(cleanupJobId, "f19-expired-cleanup-worker",
                    expiredToken, OffsetDateTime.now().plusMinutes(1), "FILE_DELETE_FAILED", "不应回写")).isFalse();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM document_cleanup_job WHERE id = ?", String.class,
                    cleanupJobId)).isEqualTo("RUNNING");
            assertThat(jdbcTemplate.queryForObject("SELECT lease_token FROM document_cleanup_job WHERE id = ?", UUID.class,
                    cleanupJobId)).isEqualTo(expiredToken);
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void cleanupDeletesOnlyTargetFilesAndVectorsWhileKeepingHistoricalLocators() throws Exception {
        DeletionFixture fixture = createFixture();
        try {
            AuthenticatedRequest admin = login("admin", "admin");
            deleteDocument(admin, fixture.documentId()).andExpect(status().isAccepted());

            documentCleanupService.executeOnce();

            assertThat(Files.exists(STORAGE_ROOT.resolve(fixture.activeStorageKey()))).isFalse();
            assertThat(Files.exists(STORAGE_ROOT.resolve(fixture.pendingStorageKey()))).isFalse();
            assertThat(Files.exists(STORAGE_ROOT.resolve(fixture.otherStorageKey()))).isTrue();
            assertThat(vectorCount(fixture.activeChunkId(), fixture.pendingChunkId())).isZero();
            assertThat(vectorCount(fixture.otherChunkId())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE version_id IN (?, ?)",
                    Integer.class, fixture.activeVersionId(), fixture.pendingVersionId())).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_version WHERE document_id = ?",
                    Integer.class, fixture.documentId())).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM message_source WHERE chunk_id = ?",
                    Integer.class, fixture.activeChunkId())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM document_cleanup_job WHERE document_id = ?",
                    String.class, fixture.documentId())).isEqualTo("SUCCEEDED");
            MvcResult historyResult = mvc.perform(get("/api/chat/sessions/{sessionId}/messages", fixture.sessionId())
                            .session(admin.session()).cookie(admin.cookie()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.contentReadable").value(true))
                    .andExpect(jsonPath("$.data.items[0].content").value("F19 历史回答"))
                    .andExpect(jsonPath("$.data.items[0].sources[0].chunkId")
                            .value(fixture.activeChunkId().toString()))
                    .andExpect(jsonPath("$.data.items[0].sources[0].available").value(false))
                    .andExpect(jsonPath("$.data.items[0].sources[0].isCurrent").value(false))
                    .andExpect(jsonPath("$.data.items[0].sources[0].previewPath").value(Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.items[0].sources[0].content").doesNotExist())
                    .andReturn();
            assertThat(historyResult.getResponse().getContentAsString())
                    .doesNotContain("F19 历史活动来源", "F19 构建中来源");
        } finally {
            deleteFixture(fixture);
        }
    }

    private ResultActions deleteDocument(AuthenticatedRequest request, UUID documentId) throws Exception {
        return mvc.perform(delete("/api/documents/{documentId}", documentId)
                        .session(request.session()).cookie(request.cookie())
                        .header(request.csrfHeader(), request.csrfToken()));
    }

    private DeletionFixture createFixture() throws IOException {
        UUID adminId = userId("admin");
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID otherDocumentId = UUID.randomUUID();
        UUID activeVersionId = UUID.randomUUID();
        UUID pendingVersionId = UUID.randomUUID();
        UUID otherVersionId = UUID.randomUUID();
        UUID activeJobId = UUID.randomUUID();
        UUID pendingJobId = UUID.randomUUID();
        UUID otherJobId = UUID.randomUUID();
        UUID activeChunkId = UUID.randomUUID();
        UUID pendingChunkId = UUID.randomUUID();
        UUID otherChunkId = UUID.randomUUID();
        UUID pendingLeaseToken = UUID.randomUUID();
        String storagePrefix = "2026/09/";
        String activeStorageKey = storagePrefix + activeVersionId + ".md";
        String pendingStorageKey = storagePrefix + pendingVersionId + ".md";
        String otherStorageKey = storagePrefix + otherVersionId + ".md";

        jdbcTemplate.update("""
                -- knowledge_base、kb_member：F19 删除验收知识库及其管理员 WRITE 成员。
                INSERT INTO knowledge_base (id, name, description, owner_id, status, revision)
                VALUES (?, ?, '验证文档逻辑删除与异步清理', ?, 'ACTIVE', 0)
                """, knowledgeBaseId, "F19 文档删除 " + knowledgeBaseId, adminId);
        jdbcTemplate.update("""
                INSERT INTO kb_member (kb_id, user_id, permission, granted_by, created_at)
                VALUES (?, ?, 'WRITE', ?, CURRENT_TIMESTAMP)
                """, knowledgeBaseId, adminId, adminId);
        insertDocument(documentId, knowledgeBaseId, adminId, "F19 待删除文档", 3);
        insertDocument(otherDocumentId, knowledgeBaseId, adminId, "F19 保留文档", 2);
        insertVersion(activeVersionId, documentId, 1, activeStorageKey, "READY");
        insertVersion(pendingVersionId, documentId, 2, pendingStorageKey, "BUILDING");
        insertVersion(otherVersionId, otherDocumentId, 1, otherStorageKey, "READY");
        insertJob(activeJobId, activeVersionId, "SUCCEEDED", "PUBLISH", null, null);
        insertJob(pendingJobId, pendingVersionId, "RUNNING", "EMBED", "f19-old-worker", pendingLeaseToken);
        insertJob(otherJobId, otherVersionId, "SUCCEEDED", "PUBLISH", null, null);
        jdbcTemplate.update("""
                -- document：目标文档同时拥有活动和构建中待处理版本，修订号用于核对删除仅递增一次。
                UPDATE document
                SET active_version_id = ?, pending_version_id = ?, revision = 7
                WHERE id = ?
                """, activeVersionId, pendingVersionId, documentId);
        jdbcTemplate.update("UPDATE document SET active_version_id = ? WHERE id = ?", otherVersionId, otherDocumentId);
        insertChunkAndVector(activeChunkId, activeVersionId, 0, "F19 历史活动来源");
        insertChunkAndVector(pendingChunkId, pendingVersionId, 0, "F19 构建中来源");
        insertChunkAndVector(otherChunkId, otherVersionId, 0, "F19 其他文档来源");
        HistoryFixture history = insertHistoricalSource(knowledgeBaseId, adminId, activeChunkId);
        writeStorageFile(activeStorageKey, "历史活动原文件");
        writeStorageFile(pendingStorageKey, "构建中原文件");
        writeStorageFile(otherStorageKey, "其他文档原文件");

        return new DeletionFixture(knowledgeBaseId, documentId, otherDocumentId, activeVersionId, pendingVersionId,
                activeJobId, pendingJobId, activeChunkId, pendingChunkId, otherChunkId, pendingLeaseToken,
                activeStorageKey, pendingStorageKey, otherStorageKey, history.sessionId(), history.runId(),
                history.messageId());
    }

    private void insertDocument(UUID documentId, UUID knowledgeBaseId, UUID userId, String name, int nextVersionNo) {
        jdbcTemplate.update("""
                -- document：F19 删除与隔离文档，初始时不填写版本指针以满足复合外键。
                INSERT INTO document (id, kb_id, name, active_version_id, pending_version_id, next_version_no,
                                      created_by, deleted_at, revision)
                VALUES (?, ?, ?, NULL, NULL, ?, ?, NULL, 0)
                """, documentId, knowledgeBaseId, name, nextVersionNo, userId);
    }

    private void insertVersion(UUID versionId, UUID documentId, int versionNo, String storageKey, String status) {
        jdbcTemplate.update("""
                -- document_version：F19 测试版本保留受控存储键和固定 1024 维配置快照。
                INSERT INTO document_version (
                    id, document_id, version_no, storage_key, file_hash, file_size, media_type, original_filename,
                    status, parser_version, chunker_version, embedding_model, dimensions, config_hash,
                    config_snapshot, chunk_count
                ) VALUES (?, ?, ?, ?, ?, 30, 'text/markdown', 'f19.md', ?, 'plain-text-v1',
                          'token-budget-v1', 'bge-m3', 1024, ?,
                          CAST('{"embedding":{"model":"bge-m3","dimensions":1024}}' AS JSONB), 1)
                """, versionId, documentId, versionNo, storageKey, "a".repeat(64), status, "b".repeat(64));
    }

    private void insertJob(UUID jobId, UUID versionId, String status, String stage, String leaseOwner, UUID leaseToken) {
        jdbcTemplate.update("""
                -- ingestion_job：目标 BUILDING 版本使用有效租约，删除后必须取消并清空该租约。
                INSERT INTO ingestion_job (
                    id, version_id, status, stage, attempt, max_attempts, lease_owner, lease_token, lease_until,
                    next_run_at, finished_at
                ) VALUES (?, ?, ?, ?, 1, 3, ?, ?,
                          CASE WHEN ? IS NULL THEN NULL ELSE CURRENT_TIMESTAMP + INTERVAL '5 minutes' END,
                          CURRENT_TIMESTAMP,
                          CASE WHEN ? = 'SUCCEEDED' THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, jobId, versionId, status, stage, leaseOwner, leaseToken, leaseToken, status);
    }

    private void insertChunkAndVector(UUID chunkId, UUID versionId, int chunkIndex, String content) {
        jdbcTemplate.update("""
                -- document_chunk：保存历史来源定位，清理后该记录必须保留。
                INSERT INTO document_chunk (
                    id, version_id, chunk_index, section, page_no, start_offset, end_offset,
                    content_hash, char_count, token_count
                ) VALUES (?, ?, ?, 'F19 来源章节', 1, 0, ?, ?, ?, 1)
                """, chunkId, versionId, chunkIndex, content.length(), "c".repeat(64), content.length());
        jdbcTemplate.update("""
                -- vector_store：正文和向量会在已删除文档清理完成后删除，其他文档保持不变。
                INSERT INTO vector_store (id, content, metadata, embedding)
                VALUES (?, ?, CAST('{"source":"f19"}' AS JSON), CAST(? AS vector))
                """, chunkId, content, vectorLiteral());
    }

    private HistoryFixture insertHistoricalSource(UUID knowledgeBaseId, UUID userId, UUID chunkId) {
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        jdbcTemplate.update("""
                -- chat_session：为 message_source 构造可追溯的已完成会话上下文。
                INSERT INTO chat_session (id, user_id, title, mode, next_message_seq)
                VALUES (?, ?, 'F19 历史来源会话', 'RAG', 2)
                """, sessionId, userId);
        jdbcTemplate.update("INSERT INTO chat_session_kb (session_id, kb_id) VALUES (?, ?)", sessionId, knowledgeBaseId);
        jdbcTemplate.update("""
                INSERT INTO chat_run (id, session_id, client_request_id, request_hash, status, config_snapshot,
                                      usage, metrics, finished_at)
                VALUES (?, ?, ?, ?, 'COMPLETED', CAST('{}' AS JSONB), NULL, CAST('{}' AS JSONB), CURRENT_TIMESTAMP)
                """, runId, sessionId, UUID.randomUUID(), "d".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO chat_message (id, run_id, session_id, seq, role, content, status)
                VALUES (?, ?, ?, 1, 'ASSISTANT', 'F19 历史回答', 'COMPLETED')
                """, messageId, runId, sessionId);
        jdbcTemplate.update("""
                -- message_source：历史引用直接关联目标文档分块；向量清理不得删除定位记录。
                INSERT INTO message_source (
                    id, message_id, citation_no, is_cited, source_type, kb_id, chunk_id, locator, snapshot
                ) VALUES (?, ?, 1, TRUE, 'DOCUMENT', ?, ?, CAST('{"section":"F19 来源章节"}' AS JSONB),
                          CAST('{"document":"f19"}' AS JSONB))
                """, UUID.randomUUID(), messageId, knowledgeBaseId, chunkId);
        return new HistoryFixture(sessionId, runId, messageId);
    }

    private void writeStorageFile(String storageKey, String content) throws IOException {
        Path path = STORAGE_ROOT.resolve(storageKey);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private int cleanupJobCount(UUID documentId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_cleanup_job WHERE document_id = ?",
                Integer.class, documentId);
    }

    private int vectorCount(UUID chunkId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store WHERE id = ?", Integer.class, chunkId);
    }

    private int vectorCount(UUID firstChunkId, UUID secondChunkId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store WHERE id IN (?, ?)", Integer.class,
                firstChunkId, secondChunkId);
    }

    private String vectorLiteral() {
        return "[" + "0,".repeat(1023) + "0]";
    }

    private void deleteFixture(DeletionFixture fixture) {
        if (fixture == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM message_source WHERE chunk_id IN (?, ?, ?)", fixture.activeChunkId(),
                fixture.pendingChunkId(), fixture.otherChunkId());
        jdbcTemplate.update("DELETE FROM chat_message WHERE id = ?", fixture.messageId());
        jdbcTemplate.update("DELETE FROM chat_run WHERE id = ?", fixture.runId());
        jdbcTemplate.update("DELETE FROM chat_session_kb WHERE session_id = ?", fixture.sessionId());
        jdbcTemplate.update("DELETE FROM chat_session WHERE id = ?", fixture.sessionId());
        jdbcTemplate.update("DELETE FROM document_cleanup_job WHERE document_id = ?", fixture.documentId());
        jdbcTemplate.update("DELETE FROM vector_store WHERE id IN (?, ?, ?)", fixture.activeChunkId(),
                fixture.pendingChunkId(), fixture.otherChunkId());
        jdbcTemplate.update("DELETE FROM document_chunk WHERE id IN (?, ?, ?)", fixture.activeChunkId(),
                fixture.pendingChunkId(), fixture.otherChunkId());
        jdbcTemplate.update("DELETE FROM ingestion_job WHERE id IN (?, ?, ?)", fixture.activeJobId(),
                fixture.pendingJobId(), fixture.otherJobId());
        jdbcTemplate.update("UPDATE document SET active_version_id = NULL, pending_version_id = NULL WHERE id IN (?, ?)",
                fixture.documentId(), fixture.otherDocumentId());
        jdbcTemplate.update("DELETE FROM document_version WHERE id IN (?, ?, ?)", fixture.activeVersionId(),
                fixture.pendingVersionId(), fixture.otherVersionId());
        jdbcTemplate.update("DELETE FROM document WHERE id IN (?, ?)", fixture.documentId(), fixture.otherDocumentId());
        jdbcTemplate.update("DELETE FROM kb_member WHERE kb_id = ?", fixture.knowledgeBaseId());
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", fixture.knowledgeBaseId());
        deleteStorageFile(fixture.activeStorageKey());
        deleteStorageFile(fixture.pendingStorageKey());
        deleteStorageFile(fixture.otherStorageKey());
    }

    private void deleteStorageFile(String storageKey) {
        try {
            Files.deleteIfExists(STORAGE_ROOT.resolve(storageKey));
        } catch (IOException exception) {
            throw new AssertionError("未能清理 F19 测试原文件", exception);
        }
    }

    private UUID userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username = ?", UUID.class, username);
    }

    private AuthenticatedRequest login(String username, String password) throws Exception {
        SessionCsrf initialCsrf = csrf(null, null);
        MvcResult loginResult = mvc.perform(post("/api/auth/login").session(initialCsrf.session())
                        .header(initialCsrf.headerName(), initialCsrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("username", username).put("password", password).toString()))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = loginCookie(loginResult);
        SessionCsrf currentCsrf = csrf((MockHttpSession) loginResult.getRequest().getSession(false), cookie);
        return new AuthenticatedRequest(currentCsrf.session(), cookie, currentCsrf.headerName(), currentCsrf.token());
    }

    private SessionCsrf csrf(MockHttpSession session, Cookie cookie) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        if (cookie != null) {
            request.cookie(cookie);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new SessionCsrf(data.path("token").asText(), data.path("headerName").asText(),
                (MockHttpSession) result.getRequest().getSession(false));
    }

    private Cookie loginCookie(MvcResult loginResult) {
        String prefix = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE_NAME + "=";
        String setCookie = loginResult.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(prefix)).filter(value -> !value.contains("Max-Age=0"))
                .reduce((previous, current) -> current)
                .orElseThrow(() -> new AssertionError("登录响应缺少 Sa-Token Cookie"));
        int valueEnd = setCookie.indexOf(';');
        String tokenValue = setCookie.substring(prefix.length(), valueEnd < 0 ? setCookie.length() : valueEnd);
        return new Cookie(IdentityOpenApiConfiguration.SA_TOKEN_COOKIE_NAME, tokenValue);
    }

    private record SessionCsrf(String token, String headerName, MockHttpSession session) {
    }

    private record AuthenticatedRequest(MockHttpSession session, Cookie cookie, String csrfHeader, String csrfToken) {
    }

    private record DeletionFixture(UUID knowledgeBaseId, UUID documentId, UUID otherDocumentId, UUID activeVersionId,
                                   UUID pendingVersionId, UUID activeJobId, UUID pendingJobId, UUID activeChunkId,
                                   UUID pendingChunkId, UUID otherChunkId, UUID pendingLeaseToken,
                                   String activeStorageKey, String pendingStorageKey, String otherStorageKey,
                                   UUID sessionId, UUID runId, UUID messageId) {
    }

    private record HistoryFixture(UUID sessionId, UUID runId, UUID messageId) {
    }
}
