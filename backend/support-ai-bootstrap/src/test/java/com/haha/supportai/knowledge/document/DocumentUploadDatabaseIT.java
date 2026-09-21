package com.haha.supportai.knowledge.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.storage.PostgresFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key=", "spring.ai.ollama.base-url=http://127.0.0.1:1",
        "spring.ai.ollama.init.pull-model-strategy=never", "support.ai.document.upload.max-file-size=32B"
})
@AutoConfigureMockMvc
class DocumentUploadDatabaseIT {

    private static final Path STORAGE_ROOT = Path.of("target", "f05-document-upload-it").toAbsolutePath();

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

    @Test
    void writeMemberUploadsDocumentThroughApiAndCreatesPendingVersionAndQueuedJob() throws Exception {
        AuthenticatedRequest admin = login("admin", "admin");
        UUID knowledgeBaseId = null;
        UUID documentId = null;
        UUID versionId = null;
        UUID jobId = null;
        String storageKey = null;
        try {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT success FROM flyway_schema_history WHERE version = '6'", Boolean.class)).isTrue();
            knowledgeBaseId = createKnowledgeBase(admin, "文档上传验收", "验证 F05 文档受理链路");
            MockMultipartFile file = new MockMultipartFile("file", "C:\\upload\\..\\outside\\guide.md",
                    "text/markdown", "# 支付故障".getBytes(StandardCharsets.UTF_8));

            MvcResult result = mvc.perform(multipart("/api/knowledge-bases/{kbId}/documents", knowledgeBaseId)
                            .file(file).param("displayName", "支付排障指南")
                            .session(admin.session()).cookie(admin.cookie())
                            .header(admin.csrfHeader(), admin.csrfToken()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.status").value("QUEUED"))
                    .andReturn();
            JsonNode receipt = responseData(result);
            documentId = UUID.fromString(receipt.path("documentId").asText());
            versionId = UUID.fromString(receipt.path("versionId").asText());
            jobId = UUID.fromString(receipt.path("jobId").asText());
            assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/ingestion-jobs/" + jobId);
            storageKey = jdbcTemplate.queryForObject("SELECT storage_key FROM document_version WHERE id = ?",
                    String.class, versionId);

            assertThat(jdbcTemplate.queryForObject("SELECT active_version_id IS NULL FROM document WHERE id = ?",
                    Boolean.class, documentId)).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT pending_version_id FROM document WHERE id = ?", UUID.class,
                    documentId)).isEqualTo(versionId);
            assertThat(jdbcTemplate.queryForObject("SELECT next_version_no FROM document WHERE id = ?", Integer.class,
                    documentId)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("SELECT original_filename FROM document_version WHERE id = ?",
                    String.class, versionId)).isEqualTo("guide.md");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM document_version WHERE id = ?", String.class,
                    versionId)).isEqualTo("QUEUED");
            assertThat(jdbcTemplate.queryForObject("SELECT dimensions FROM document_version WHERE id = ?", Integer.class,
                    versionId)).isEqualTo(1024);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
                    jobId)).isEqualTo("QUEUED");
            assertThat(jdbcTemplate.queryForObject("SELECT stage FROM ingestion_job WHERE id = ?", String.class,
                    jobId)).isEqualTo("PARSE");
            assertThat(Files.exists(STORAGE_ROOT.resolve(storageKey))).isTrue();
            mvc.perform(get("/api/knowledge-bases/{kbId}", knowledgeBaseId)
                            .session(admin.session()).cookie(admin.cookie()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.documentCount").value(1));
        } finally {
            deleteDocumentGraph(documentId, versionId, jobId, storageKey);
            deleteKnowledgeBase(knowledgeBaseId);
        }
    }

    @Test
    void readMemberCannotUploadAndInvalidFilesReturnExplicitErrors() throws Exception {
        AuthenticatedRequest admin = login("admin", "admin");
        AuthenticatedRequest supportUser = login("support1", "support1");
        UUID knowledgeBaseId = null;
        try {
            knowledgeBaseId = createKnowledgeBase(admin, "文档上传权限", "验证读权限不能提交文件");
            addReadMember(knowledgeBaseId, userId("support1"), userId("admin"));
            int documentCount = documentCount(knowledgeBaseId);

            mvc.perform(multipart("/api/knowledge-bases/{kbId}/documents", knowledgeBaseId)
                            .file(file("read-only.md", "text/markdown", "# 只读上传"))
                            .session(supportUser.session()).cookie(supportUser.cookie())
                            .header(supportUser.csrfHeader(), supportUser.csrfToken()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            assertThat(documentCount(knowledgeBaseId)).isEqualTo(documentCount);

            mvc.perform(multipart("/api/knowledge-bases/{kbId}/documents", knowledgeBaseId)
                            .file(file("unsupported.pdf", "application/pdf", "not a pdf"))
                            .session(admin.session()).cookie(admin.cookie())
                            .header(admin.csrfHeader(), admin.csrfToken()))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("DOCUMENT_FILE_TYPE_UNSUPPORTED"));
            assertThat(documentCount(knowledgeBaseId)).isEqualTo(documentCount);

            mvc.perform(multipart("/api/knowledge-bases/{kbId}/documents", knowledgeBaseId)
                            .file(file("oversize.txt", "text/plain", "123456789012345678901234567890123"))
                            .session(admin.session()).cookie(admin.cookie())
                            .header(admin.csrfHeader(), admin.csrfToken()))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.code").value("DOCUMENT_FILE_TOO_LARGE"));
            assertThat(documentCount(knowledgeBaseId)).isEqualTo(documentCount);

            mvc.perform(multipart("/api/knowledge-bases/{kbId}/documents", knowledgeBaseId)
                            .session(admin.session()).cookie(admin.cookie())
                            .header(admin.csrfHeader(), admin.csrfToken()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DOCUMENT_FILE_REQUIRED"));
            assertThat(documentCount(knowledgeBaseId)).isEqualTo(documentCount);
        } finally {
            deleteKnowledgeBase(knowledgeBaseId);
        }
    }

    @Test
    void commitFailureRollsBackDatabaseAndRemovesStoredFile() throws Exception {
        AuthenticatedRequest admin = login("admin", "admin");
        UUID knowledgeBaseId = null;
        long storedFilesBefore = storedFileCount();
        try {
            knowledgeBaseId = createKnowledgeBase(admin, "事务补偿验收", "验证数据库提交失败时清理原文件");
            createDeferredFailureTrigger();

            mvc.perform(multipart("/api/knowledge-bases/{kbId}/documents", knowledgeBaseId)
                            .file(file("commit-failure.txt", "text/plain", "commit failure"))
                            .session(admin.session()).cookie(admin.cookie())
                            .header(admin.csrfHeader(), admin.csrfToken()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DOCUMENT_UPLOAD_FAILED"));

            assertThat(documentCount(knowledgeBaseId)).isZero();
            assertThat(storedFileCount()).isEqualTo(storedFilesBefore);
        } finally {
            dropDeferredFailureTrigger();
            deleteKnowledgeBase(knowledgeBaseId);
        }
    }

    @Test
    void compositeForeignKeyRejectsVersionPointerFromAnotherDocument() {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID firstDocumentId = UUID.randomUUID();
        UUID secondDocumentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        try {
            insertUser(userId);
            insertKnowledgeBase(knowledgeBaseId, userId);
            insertDocument(firstDocumentId, knowledgeBaseId, userId, "原始文档");
            insertDocument(secondDocumentId, knowledgeBaseId, userId, "另一份文档");
            insertVersion(versionId, firstDocumentId);

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE document SET pending_version_id = ? WHERE id = ?", versionId, secondDocumentId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            deleteDocumentGraph(firstDocumentId, versionId, null, null);
            deleteDocumentGraph(secondDocumentId, null, null, null);
            jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", knowledgeBaseId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    private MockMultipartFile file(String filename, String mediaType, String content) {
        return new MockMultipartFile("file", filename, mediaType, content.getBytes(StandardCharsets.UTF_8));
    }

    private UUID createKnowledgeBase(AuthenticatedRequest request, String name, String description) throws Exception {
        MvcResult result = mvc.perform(post("/api/knowledge-bases").session(request.session()).cookie(request.cookie())
                        .header(request.csrfHeader(), request.csrfToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("name", name).put("description", description).toString()))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(responseData(result).path("id").asText());
    }

    private void addReadMember(UUID knowledgeBaseId, UUID userId, UUID grantedBy) {
        jdbcTemplate.update("""
                INSERT INTO kb_member (kb_id, user_id, permission, granted_by, created_at)
                VALUES (?, ?, 'READ', ?, CURRENT_TIMESTAMP)
                """, knowledgeBaseId, userId, grantedBy);
    }

    private int documentCount(UUID knowledgeBaseId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document WHERE kb_id = ?", Integer.class,
                knowledgeBaseId);
    }

    private void insertUser(UUID userId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, username, password_hash, display_name, role, enabled)
                VALUES (?, ?, 'test-password-hash', '文档迁移测试用户', 'USER', TRUE)
                """, userId, "document-it-" + userId);
    }

    private void insertKnowledgeBase(UUID knowledgeBaseId, UUID userId) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_base (id, name, description, owner_id, status, revision)
                VALUES (?, '文档迁移测试知识库', '验证 F05 文档登记约束', ?, 'ACTIVE', 0)
                """, knowledgeBaseId, userId);
    }

    private void insertDocument(UUID documentId, UUID knowledgeBaseId, UUID userId, String name) {
        jdbcTemplate.update("""
                INSERT INTO document (id, kb_id, name, active_version_id, pending_version_id, next_version_no,
                                      created_by, deleted_at, revision)
                VALUES (?, ?, ?, NULL, NULL, 2, ?, NULL, 0)
                """, documentId, knowledgeBaseId, name, userId);
    }

    private void insertVersion(UUID versionId, UUID documentId) {
        jdbcTemplate.update("""
                INSERT INTO document_version (
                    id, document_id, version_no, storage_key, file_hash, file_size, media_type, original_filename,
                    status, parser_version, chunker_version, embedding_model, dimensions, config_hash,
                    config_snapshot, chunk_count
                ) VALUES (?, ?, 1, '2026/09/f05-document.md', ?, 17, 'text/markdown', 'document.md',
                          'QUEUED', 'plain-text-v1', 'token-budget-v1', 'bge-m3', 1024, ?,
                          CAST('{\"embedding\":{\"model\":\"bge-m3\",\"dimensions\":1024}}' AS JSONB), 0)
                """, versionId, documentId, "a".repeat(64), "b".repeat(64));
    }

    private void createDeferredFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_f05_ingestion_job_commit() RETURNS TRIGGER AS $$
                BEGIN
                    RAISE EXCEPTION '模拟 F05 入库任务提交失败';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE CONSTRAINT TRIGGER trg_fail_f05_ingestion_job_commit
                AFTER INSERT ON ingestion_job
                DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW EXECUTE FUNCTION fail_f05_ingestion_job_commit()
                """);
    }

    private void dropDeferredFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_f05_ingestion_job_commit ON ingestion_job");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_f05_ingestion_job_commit()");
    }

    private long storedFileCount() throws IOException {
        if (Files.notExists(STORAGE_ROOT)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(STORAGE_ROOT)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private void deleteDocumentGraph(UUID documentId, UUID versionId, UUID jobId, String storageKey) {
        if (documentId != null) {
            jdbcTemplate.update("UPDATE document SET active_version_id = NULL, pending_version_id = NULL WHERE id = ?",
                    documentId);
        }
        if (jobId != null) {
            jdbcTemplate.update("DELETE FROM ingestion_job WHERE id = ?", jobId);
        }
        if (versionId != null) {
            jdbcTemplate.update("DELETE FROM document_version WHERE id = ?", versionId);
        }
        if (documentId != null) {
            jdbcTemplate.update("DELETE FROM document WHERE id = ?", documentId);
        }
        if (storageKey != null) {
            try {
                Files.deleteIfExists(STORAGE_ROOT.resolve(storageKey));
            } catch (IOException exception) {
                throw new AssertionError("未能清理测试原文件", exception);
            }
        }
    }

    private void deleteKnowledgeBase(UUID knowledgeBaseId) {
        if (knowledgeBaseId != null) {
            jdbcTemplate.update("DELETE FROM kb_member WHERE kb_id = ?", knowledgeBaseId);
            jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", knowledgeBaseId);
        }
    }

    private UUID userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username = ?", UUID.class, username);
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
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
        JsonNode data = responseData(result);
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
}
