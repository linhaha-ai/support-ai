package com.haha.supportai.knowledge.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        "spring.ai.ollama.init.pull-model-strategy=never", "support.ai.document.upload.max-file-size=64B",
        "support.ai.ingestion.worker.enabled=false"
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
                            .file(file("oversize.txt", "text/plain", "x".repeat(65)))
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
    void newVersionKeepsActiveVersionSearchableAndRejectsPendingOrStaleRequests() throws Exception {
        AuthenticatedRequest admin = login("admin", "admin");
        UUID knowledgeBaseId = null;
        UploadedDocument original = null;
        UUID newVersionId = null;
        UUID newJobId = null;
        String newStorageKey = null;
        try {
            knowledgeBaseId = createKnowledgeBase(admin, "文档版本发布验收", "验证 F18 新版本受理不会替换旧版本");
            original = uploadInitialDocument(admin, knowledgeBaseId, "guide-v1.md", "# 支付故障\n旧版本内容");
            activateDocumentVersion(original);

            MvcResult result = mvc.perform(multipart("/api/documents/{documentId}/versions", original.documentId())
                            .file(file("guide-v2.md", "text/markdown", "# 支付故障\n新版本内容"))
                            .param("expectedRevision", "1")
                            .session(admin.session()).cookie(admin.cookie())
                            .header(admin.csrfHeader(), admin.csrfToken()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.status").value("QUEUED"))
                    .andExpect(jsonPath("$.data.reused").value(false))
                    .andExpect(jsonPath("$.data.revision").value(2))
                    .andReturn();
            JsonNode receipt = responseData(result);
            newVersionId = UUID.fromString(receipt.path("versionId").asText());
            newJobId = UUID.fromString(receipt.path("jobId").asText());
            newStorageKey = jdbcTemplate.queryForObject("SELECT storage_key FROM document_version WHERE id = ?",
                    String.class, newVersionId);

            assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/ingestion-jobs/" + newJobId);
            assertThat(jdbcTemplate.queryForObject("SELECT active_version_id FROM document WHERE id = ?", UUID.class,
                    original.documentId())).isEqualTo(original.versionId());
            assertThat(jdbcTemplate.queryForObject("SELECT pending_version_id FROM document WHERE id = ?", UUID.class,
                    original.documentId())).isEqualTo(newVersionId);
            assertThat(jdbcTemplate.queryForObject("SELECT next_version_no FROM document WHERE id = ?", Integer.class,
                    original.documentId())).isEqualTo(3);
            assertThat(jdbcTemplate.queryForObject("SELECT revision FROM document WHERE id = ?", Long.class,
                    original.documentId())).isEqualTo(2L);
            assertThat(jdbcTemplate.queryForObject("SELECT version_no FROM document_version WHERE id = ?", Integer.class,
                    newVersionId)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM document_version WHERE id = ?", String.class,
                    newVersionId)).isEqualTo("QUEUED");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
                    newJobId)).isEqualTo("QUEUED");
            assertThat(Files.exists(STORAGE_ROOT.resolve(newStorageKey))).isTrue();

            mvc.perform(multipart("/api/documents/{documentId}/versions", original.documentId())
                            .file(file("guide-v3.md", "text/markdown", "# 支付故障\n第三版内容"))
                            .param("expectedRevision", "2")
                            .session(admin.session()).cookie(admin.cookie())
                            .header(admin.csrfHeader(), admin.csrfToken()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DOCUMENT_VERSION_CONFLICT"));
            mvc.perform(multipart("/api/documents/{documentId}/versions", original.documentId())
                            .file(file("guide-stale.md", "text/markdown", "# 支付故障\n过期修订内容"))
                            .param("expectedRevision", "1")
                            .session(admin.session()).cookie(admin.cookie())
                            .header(admin.csrfHeader(), admin.csrfToken()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DOCUMENT_VERSION_CONFLICT"));

            assertThat(documentVersionCount(original.documentId())).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("SELECT active_version_id FROM document WHERE id = ?", UUID.class,
                    original.documentId())).isEqualTo(original.versionId());
        } finally {
            if (original != null) {
                deleteDocumentGraph(original.documentId(), values(original.versionId(), newVersionId),
                        values(original.jobId(), newJobId), values(original.storageKey(), newStorageKey));
            }
            deleteKnowledgeBase(knowledgeBaseId);
        }
    }

    @Test
    void missingExpectedRevisionReturnsBadRequest() throws Exception {
        AuthenticatedRequest admin = login("admin", "admin");
        long filesBefore = storedFileCount();

        mvc.perform(multipart("/api/documents/{documentId}/versions", UUID.randomUUID())
                        .file(file("guide.md", "text/markdown", "# 缺少修订号"))
                        .session(admin.session()).cookie(admin.cookie())
                        .header(admin.csrfHeader(), admin.csrfToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("缺少必要请求参数"));

        assertThat(storedFileCount()).isEqualTo(filesBefore);
    }

    @Test
    void simultaneousUpdatesWithSameRevisionCreateOnlyOnePendingVersion() throws Exception {
        AuthenticatedRequest firstAdmin = login("admin", "admin");
        AuthenticatedRequest secondAdmin = login("admin", "admin");
        UUID knowledgeBaseId = null;
        UploadedDocument original = null;
        ExecutorService executor = null;
        try {
            knowledgeBaseId = createKnowledgeBase(firstAdmin, "文档并发更新验收", "验证 F18 同修订号只接受一个新版本");
            original = uploadInitialDocument(firstAdmin, knowledgeBaseId, "guide-v1.md", "# V1\n旧内容");
            activateDocumentVersion(original);
            long filesBefore = storedFileCount();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            executor = Executors.newFixedThreadPool(2);
            UUID documentId = original.documentId();

            Future<Integer> first = executor.submit(() -> uploadVersionStatus(firstAdmin, documentId,
                    "guide-v2-a.md", "# V2-A\n并发内容A", ready, start));
            Future<Integer> second = executor.submit(() -> uploadVersionStatus(secondAdmin, documentId,
                    "guide-v2-b.md", "# V2-B\n并发内容B", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(202, 409);
            assertThat(documentVersionCount(documentId)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM ingestion_job job
                    INNER JOIN document_version version ON version.id = job.version_id
                    WHERE version.document_id = ?
                    """, Integer.class, documentId)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForList(
                    "SELECT version_no FROM document_version WHERE document_id = ? ORDER BY version_no",
                    Integer.class, documentId)).containsExactly(1, 2);
            assertThat(jdbcTemplate.queryForObject("SELECT pending_version_id IS NOT NULL FROM document WHERE id = ?",
                    Boolean.class, documentId)).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT next_version_no FROM document WHERE id = ?", Integer.class,
                    documentId)).isEqualTo(3);
            assertThat(jdbcTemplate.queryForObject("SELECT revision FROM document WHERE id = ?", Long.class,
                    documentId)).isEqualTo(2L);
            assertThat(storedFileCount()).isEqualTo(filesBefore + 1);
        } finally {
            if (executor != null) {
                executor.shutdownNow();
            }
            if (original != null) {
                UUID documentId = original.documentId();
                List<UUID> versionIds = jdbcTemplate.queryForList(
                        "SELECT id FROM document_version WHERE document_id = ?", UUID.class, documentId);
                List<UUID> jobIds = jdbcTemplate.queryForList("""
                        SELECT job.id
                        FROM ingestion_job job
                        INNER JOIN document_version version ON version.id = job.version_id
                        WHERE version.document_id = ?
                        """, UUID.class, documentId);
                List<String> storageKeys = jdbcTemplate.queryForList(
                        "SELECT storage_key FROM document_version WHERE document_id = ?", String.class, documentId);
                deleteDocumentGraph(documentId, versionIds, jobIds, storageKeys);
            }
            deleteKnowledgeBase(knowledgeBaseId);
        }
    }

    @Test
    void sameContentAndConfigurationReuseActiveVersionWithoutCreatingFileVersionOrJob() throws Exception {
        AuthenticatedRequest admin = login("admin", "admin");
        UUID knowledgeBaseId = null;
        UploadedDocument original = null;
        try {
            knowledgeBaseId = createKnowledgeBase(admin, "文档版本复用验收", "验证 F18 相同内容不重复构建");
            original = uploadInitialDocument(admin, knowledgeBaseId, "guide.md", "# 支付故障\n可复用内容");
            activateDocumentVersion(original);
            long filesBefore = storedFileCount();

            MvcResult result = mvc.perform(multipart("/api/documents/{documentId}/versions", original.documentId())
                            .file(file("guide-copy.md", "text/markdown", "# 支付故障\n可复用内容"))
                            .param("expectedRevision", "1")
                            .session(admin.session()).cookie(admin.cookie())
                            .header(admin.csrfHeader(), admin.csrfToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.data.reused").value(true))
                    .andExpect(jsonPath("$.data.revision").value(1))
                    .andReturn();

            JsonNode receipt = responseData(result);
            assertThat(UUID.fromString(receipt.path("documentId").asText())).isEqualTo(original.documentId());
            assertThat(UUID.fromString(receipt.path("versionId").asText())).isEqualTo(original.versionId());
            assertThat(UUID.fromString(receipt.path("jobId").asText())).isEqualTo(original.jobId());
            assertThat(documentVersionCount(original.documentId())).isOne();
            assertThat(storedFileCount()).isEqualTo(filesBefore);
            assertThat(jdbcTemplate.queryForObject("SELECT active_version_id FROM document WHERE id = ?", UUID.class,
                    original.documentId())).isEqualTo(original.versionId());
            assertThat(jdbcTemplate.queryForObject("SELECT pending_version_id IS NULL FROM document WHERE id = ?",
                    Boolean.class, original.documentId())).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT next_version_no FROM document WHERE id = ?", Integer.class,
                    original.documentId())).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("SELECT revision FROM document WHERE id = ?", Long.class,
                    original.documentId())).isEqualTo(1L);
        } finally {
            if (original != null) {
                deleteDocumentGraph(original.documentId(), List.of(original.versionId()), List.of(original.jobId()),
                        List.of(original.storageKey()));
            }
            deleteKnowledgeBase(knowledgeBaseId);
        }
    }

    @Test
    void readMemberCannotUploadDocumentVersion() throws Exception {
        AuthenticatedRequest admin = login("admin", "admin");
        AuthenticatedRequest supportUser = login("support1", "support1");
        UUID knowledgeBaseId = null;
        UploadedDocument original = null;
        try {
            knowledgeBaseId = createKnowledgeBase(admin, "文档版本权限验收", "验证 F18 读取成员不能发布新版本");
            original = uploadInitialDocument(admin, knowledgeBaseId, "guide.md", "# 支付故障\n原始内容");
            activateDocumentVersion(original);
            addReadMember(knowledgeBaseId, userId("support1"), userId("admin"));
            long filesBefore = storedFileCount();

            mvc.perform(multipart("/api/documents/{documentId}/versions", original.documentId())
                            .file(file("guide-v2.md", "text/markdown", "# 支付故障\n未授权内容"))
                            .param("expectedRevision", "1")
                            .session(supportUser.session()).cookie(supportUser.cookie())
                            .header(supportUser.csrfHeader(), supportUser.csrfToken()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            assertThat(documentVersionCount(original.documentId())).isOne();
            assertThat(storedFileCount()).isEqualTo(filesBefore);
            assertThat(jdbcTemplate.queryForObject("SELECT active_version_id FROM document WHERE id = ?", UUID.class,
                    original.documentId())).isEqualTo(original.versionId());
            assertThat(jdbcTemplate.queryForObject("SELECT pending_version_id IS NULL FROM document WHERE id = ?",
                    Boolean.class, original.documentId())).isTrue();
        } finally {
            if (original != null) {
                deleteDocumentGraph(original.documentId(), List.of(original.versionId()), List.of(original.jobId()),
                        List.of(original.storageKey()));
            }
            deleteKnowledgeBase(knowledgeBaseId);
        }
    }

    @Test
    void newVersionCommitFailureKeepsActiveVersionAndCompensatesStoredFile() throws Exception {
        AuthenticatedRequest admin = login("admin", "admin");
        UUID knowledgeBaseId = null;
        UploadedDocument original = null;
        try {
            knowledgeBaseId = createKnowledgeBase(admin, "文档版本补偿验收", "验证 F18 新版本提交失败的存储补偿");
            original = uploadInitialDocument(admin, knowledgeBaseId, "guide-v1.md", "# 支付故障\n旧版本内容");
            activateDocumentVersion(original);
            long filesBefore = storedFileCount();
            createDeferredFailureTrigger();

            mvc.perform(multipart("/api/documents/{documentId}/versions", original.documentId())
                            .file(file("guide-v2.md", "text/markdown", "# 支付故障\n无法提交的新版本"))
                            .param("expectedRevision", "1")
                            .session(admin.session()).cookie(admin.cookie())
                            .header(admin.csrfHeader(), admin.csrfToken()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DOCUMENT_UPLOAD_FAILED"));

            assertThat(documentVersionCount(original.documentId())).isOne();
            assertThat(storedFileCount()).isEqualTo(filesBefore);
            assertThat(jdbcTemplate.queryForObject("SELECT active_version_id FROM document WHERE id = ?", UUID.class,
                    original.documentId())).isEqualTo(original.versionId());
            assertThat(jdbcTemplate.queryForObject("SELECT pending_version_id IS NULL FROM document WHERE id = ?",
                    Boolean.class, original.documentId())).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT revision FROM document WHERE id = ?", Long.class,
                    original.documentId())).isEqualTo(1L);
        } finally {
            dropDeferredFailureTrigger();
            if (original != null) {
                deleteDocumentGraph(original.documentId(), List.of(original.versionId()), List.of(original.jobId()),
                        List.of(original.storageKey()));
            }
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
            deleteDocumentGraph(secondDocumentId, List.of(), List.of(), List.of());
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

    private int documentVersionCount(UUID documentId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_version WHERE document_id = ?", Integer.class,
                documentId);
    }

    private UploadedDocument uploadInitialDocument(AuthenticatedRequest request, UUID knowledgeBaseId, String filename,
                                                   String content) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/knowledge-bases/{kbId}/documents", knowledgeBaseId)
                        .file(file(filename, "text/markdown", content))
                        .session(request.session()).cookie(request.cookie())
                        .header(request.csrfHeader(), request.csrfToken()))
                .andExpect(status().isAccepted()).andReturn();
        JsonNode receipt = responseData(result);
        UUID documentId = UUID.fromString(receipt.path("documentId").asText());
        UUID versionId = UUID.fromString(receipt.path("versionId").asText());
        UUID jobId = UUID.fromString(receipt.path("jobId").asText());
        String storageKey = jdbcTemplate.queryForObject("SELECT storage_key FROM document_version WHERE id = ?",
                String.class, versionId);
        return new UploadedDocument(documentId, versionId, jobId, storageKey);
    }

    private int uploadVersionStatus(AuthenticatedRequest request, UUID documentId, String filename, String content,
                                    CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发版本更新未能同时开始");
        }
        return mvc.perform(multipart("/api/documents/{documentId}/versions", documentId)
                        .file(file(filename, "text/markdown", content))
                        .param("expectedRevision", "1")
                        .session(request.session()).cookie(request.cookie())
                        .header(request.csrfHeader(), request.csrfToken()))
                .andReturn().getResponse().getStatus();
    }

    private void activateDocumentVersion(UploadedDocument uploadedDocument) {
        jdbcTemplate.update("UPDATE document_version SET status = 'READY' WHERE id = ?", uploadedDocument.versionId());
        jdbcTemplate.update("UPDATE ingestion_job SET status = 'SUCCEEDED', stage = 'PUBLISH', finished_at = CURRENT_TIMESTAMP "
                + "WHERE id = ?", uploadedDocument.jobId());
        jdbcTemplate.update("UPDATE document SET active_version_id = ?, pending_version_id = NULL, revision = revision + 1 "
                + "WHERE id = ?", uploadedDocument.versionId(), uploadedDocument.documentId());
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
        deleteDocumentGraph(documentId, values(versionId), values(jobId), values(storageKey));
    }

    private void deleteDocumentGraph(UUID documentId, List<UUID> versionIds, List<UUID> jobIds,
                                     List<String> storageKeys) {
        if (documentId != null) {
            jdbcTemplate.update("UPDATE document SET active_version_id = NULL, pending_version_id = NULL WHERE id = ?",
                    documentId);
        }
        for (UUID jobId : jobIds) {
            if (jobId != null) {
                jdbcTemplate.update("DELETE FROM ingestion_job WHERE id = ?", jobId);
            }
        }
        for (UUID versionId : versionIds) {
            if (versionId != null) {
                jdbcTemplate.update("DELETE FROM document_version WHERE id = ?", versionId);
            }
        }
        if (documentId != null) {
            jdbcTemplate.update("DELETE FROM document WHERE id = ?", documentId);
        }
        for (String storageKey : storageKeys) {
            if (storageKey == null) {
                continue;
            }
            try {
                Files.deleteIfExists(STORAGE_ROOT.resolve(storageKey));
            } catch (IOException exception) {
                throw new AssertionError("未能清理测试原文件", exception);
            }
        }
    }

    @SafeVarargs
    private static <T> List<T> values(T... values) {
        return Arrays.stream(values).filter(Objects::nonNull).toList();
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

    private record UploadedDocument(UUID documentId, UUID versionId, UUID jobId, String storageKey) {
    }
}
