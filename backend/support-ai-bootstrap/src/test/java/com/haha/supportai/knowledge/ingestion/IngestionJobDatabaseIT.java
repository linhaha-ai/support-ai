package com.haha.supportai.knowledge.ingestion;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.data.knowledge.entity.po.IngestionJob;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.storage.PostgresFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key=", "spring.ai.ollama.base-url=http://127.0.0.1:1",
        "spring.ai.ollama.init.pull-model-strategy=never", "support.ai.ingestion.worker.enabled=false"
})
@AutoConfigureMockMvc
class IngestionJobDatabaseIT {

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @BeforeAll
    static void initializeSupportAccounts(@Autowired DataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/seed/identity/init_app_user.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
    }

    @Autowired
    IngestionJobRepository ingestionJobRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void concurrentClaimLetsOnlyOneWorkerAcquireTheSameJob() throws Exception {
        IngestionFixture fixture = createFixture();
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Optional<IngestionJob>>> futures = List.of(
                    executorService.submit(claimTask(ready, start, "worker-a")),
                    executorService.submit(claimTask(ready, start, "worker-b")));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Optional<IngestionJob>> results = new ArrayList<>();
            for (Future<Optional<IngestionJob>> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }

            assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
                    fixture.jobId())).isEqualTo("RUNNING");
            assertThat(jdbcTemplate.queryForObject("SELECT attempt FROM ingestion_job WHERE id = ?", Integer.class,
                    fixture.jobId())).isEqualTo(1);
        } finally {
            executorService.shutdownNow();
            deleteFixture(fixture);
        }
    }

    @Test
    void expiredWorkerCannotAdvanceButNewLeaseCanAdvanceAndResetAttempt() {
        IngestionFixture fixture = createFixture();
        String owner = "worker-old";
        UUID oldToken = UUID.randomUUID();
        try {
            assertThat(ingestionJobRepository.claimNextByStage(IngestionStageEnum.PARSE, owner, oldToken,
                    OffsetDateTime.now().plusSeconds(60))).isPresent();
            jdbcTemplate.update("UPDATE ingestion_job SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
                    fixture.jobId());

            String newOwner = "worker-new";
            UUID newToken = UUID.randomUUID();
            IngestionJob renewedJob = ingestionJobRepository.claimNextByStage(IngestionStageEnum.PARSE, newOwner,
                    newToken, OffsetDateTime.now().plusSeconds(60)).orElseThrow();

            assertThat(renewedJob.getAttempt()).isEqualTo(2);
            assertThat(ingestionJobRepository.renewLease(fixture.jobId(), owner, oldToken,
                    OffsetDateTime.now().plusSeconds(60))).isFalse();
            assertThat(ingestionJobRepository.updateProgress(fixture.jobId(), owner, oldToken, 1, 2)).isFalse();
            assertThat(ingestionJobRepository.markRetryWait(fixture.jobId(), owner, oldToken,
                    OffsetDateTime.now().plusSeconds(10), "INGESTION_PROCESSING_FAILED", "不应写入")).isFalse();
            assertThat(ingestionJobRepository.markFailed(fixture.jobId(), owner, oldToken,
                    "INGESTION_PROCESSING_FAILED", "不应写入")).isFalse();
            assertThat(ingestionJobRepository.advanceToNextStage(fixture.jobId(), owner, oldToken,
                    IngestionStageEnum.PARSE)).isFalse();
            assertThat(ingestionJobRepository.advanceToNextStage(fixture.jobId(), newOwner, newToken,
                    IngestionStageEnum.PARSE)).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
                    fixture.jobId())).isEqualTo("QUEUED");
            assertThat(jdbcTemplate.queryForObject("SELECT stage FROM ingestion_job WHERE id = ?", String.class,
                    fixture.jobId())).isEqualTo("CHUNK");
            assertThat(jdbcTemplate.queryForObject("SELECT attempt FROM ingestion_job WHERE id = ?", Integer.class,
                    fixture.jobId())).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT status = 'SUCCEEDED' FROM ingestion_job WHERE id = ?",
                    Boolean.class, fixture.jobId())).isFalse();
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void terminalFailureClearsPendingWhileAutomaticRetryKeepsIt() {
        IngestionFixture retryFixture = createFixture();
        String retryOwner = "worker-retry";
        UUID retryToken = UUID.randomUUID();
        try {
            assertThat(ingestionJobRepository.claimNextByStage(IngestionStageEnum.PARSE, retryOwner, retryToken,
                    OffsetDateTime.now().plusSeconds(60))).isPresent();
            assertThat(ingestionJobRepository.markRetryWait(retryFixture.jobId(), retryOwner, retryToken,
                    OffsetDateTime.now().plusSeconds(10), "DOCUMENT_READ_FAILED", "原文件暂时无法读取")).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT pending_version_id FROM document WHERE id = ?",
                    UUID.class, retryFixture.documentId())).isEqualTo(retryFixture.versionId());
        } finally {
            deleteFixture(retryFixture);
        }

        IngestionFixture failedFixture = createFixture();
        String failedOwner = "worker-failed";
        UUID failedToken = UUID.randomUUID();
        try {
            assertThat(ingestionJobRepository.claimNextByStage(IngestionStageEnum.PARSE, failedOwner, failedToken,
                    OffsetDateTime.now().plusSeconds(60))).isPresent();
            assertThat(ingestionJobRepository.markFailed(failedFixture.jobId(), failedOwner, failedToken,
                    "DOCUMENT_EMPTY", "文档没有可处理内容")).isTrue();

            assertThat(jdbcTemplate.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
                    failedFixture.jobId())).isEqualTo("FAILED");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM document_version WHERE id = ?", String.class,
                    failedFixture.versionId())).isEqualTo("FAILED");
            assertThat(jdbcTemplate.queryForObject("SELECT pending_version_id IS NULL FROM document WHERE id = ?",
                    Boolean.class, failedFixture.documentId())).isTrue();
        } finally {
            deleteFixture(failedFixture);
        }
    }

    @Test
    void cancelEndpointInvalidatesLeaseAndCannotBeClaimedAgain() throws Exception {
        IngestionFixture fixture = createFixture();
        String leaseOwner = "worker-cancelled";
        UUID leaseToken = UUID.randomUUID();
        try {
            assertThat(ingestionJobRepository.claimNextByStage(IngestionStageEnum.PARSE, leaseOwner, leaseToken,
                    OffsetDateTime.now().plusSeconds(60))).isPresent();
            AuthenticatedRequest administrator = login("admin", "admin");

            mvc.perform(post("/api/ingestion-jobs/{jobId}/cancel", fixture.jobId())
                            .session(administrator.session()).cookie(administrator.cookie())
                            .header(administrator.csrfHeader(), administrator.csrfToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.data.cancellable").value(false));

            assertThat(jdbcTemplate.queryForObject("SELECT status FROM document_version WHERE id = ?", String.class,
                    fixture.versionId())).isEqualTo("CANCELLED");
            assertThat(jdbcTemplate.queryForObject("SELECT pending_version_id IS NULL FROM document WHERE id = ?",
                    Boolean.class, fixture.documentId())).isTrue();
            assertThat(ingestionJobRepository.updateProgress(fixture.jobId(), leaseOwner, leaseToken, 1, 1)).isFalse();
            assertThat(ingestionJobRepository.claimNextByStage(IngestionStageEnum.PARSE, "worker-new",
                    UUID.randomUUID(), OffsetDateTime.now().plusSeconds(60))).isEmpty();

            mvc.perform(post("/api/ingestion-jobs/{jobId}/cancel", fixture.jobId())
                            .session(administrator.session()).cookie(administrator.cookie())
                            .header(administrator.csrfHeader(), administrator.csrfToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void retryEndpointRestoresFailedVersionWithoutResettingAttempt() throws Exception {
        IngestionFixture fixture = createFixture();
        try {
            jdbcTemplate.update("""
                    UPDATE ingestion_job
                    SET status = 'FAILED', attempt = 1, error_code = 'DOCUMENT_EMPTY',
                        error_detail = '文档没有可处理内容', finished_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, fixture.jobId());
            jdbcTemplate.update("UPDATE document_version SET status = 'FAILED' WHERE id = ?", fixture.versionId());
            jdbcTemplate.update("UPDATE document SET pending_version_id = NULL WHERE id = ?", fixture.documentId());
            AuthenticatedRequest administrator = login("admin", "admin");

            mvc.perform(post("/api/ingestion-jobs/{jobId}/retry", fixture.jobId())
                            .session(administrator.session()).cookie(administrator.cookie())
                            .header(administrator.csrfHeader(), administrator.csrfToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("QUEUED"))
                    .andExpect(jsonPath("$.data.attempt").value(1))
                    .andExpect(jsonPath("$.data.errorCode").isEmpty());

            assertThat(jdbcTemplate.queryForObject("SELECT status FROM document_version WHERE id = ?", String.class,
                    fixture.versionId())).isEqualTo("QUEUED");
            assertThat(jdbcTemplate.queryForObject("SELECT pending_version_id FROM document WHERE id = ?",
                    UUID.class, fixture.documentId())).isEqualTo(fixture.versionId());
            IngestionJob claimed = ingestionJobRepository.claimNextByStage(IngestionStageEnum.PARSE, "worker-retry",
                    UUID.randomUUID(), OffsetDateTime.now().plusSeconds(60)).orElseThrow();
            assertThat(claimed.getAttempt()).isEqualTo(2);
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void manualRetryRejectsOtherPendingHigherActiveAndExhaustedAttempt() {
        IngestionFixture fixture = createFixture();
        UUID ownerId = jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username = 'admin'", UUID.class);
        UUID higherVersionId = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                    UPDATE ingestion_job
                    SET status = 'FAILED', attempt = 1, error_code = 'DOCUMENT_EMPTY',
                        error_detail = '文档没有可处理内容', finished_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, fixture.jobId());
            jdbcTemplate.update("UPDATE document_version SET status = 'FAILED' WHERE id = ?", fixture.versionId());
            insertHigherVersion(fixture.documentId(), higherVersionId, "QUEUED");
            jdbcTemplate.update("UPDATE document SET pending_version_id = ? WHERE id = ?", higherVersionId,
                    fixture.documentId());
            assertThat(ingestionJobRepository.retry(fixture.jobId(), ownerId)).isEmpty();

            jdbcTemplate.update("UPDATE document_version SET status = 'READY' WHERE id = ?", higherVersionId);
            jdbcTemplate.update("UPDATE document SET active_version_id = ?, pending_version_id = NULL WHERE id = ?",
                    higherVersionId, fixture.documentId());
            assertThat(ingestionJobRepository.retry(fixture.jobId(), ownerId)).isEmpty();

            jdbcTemplate.update("UPDATE document SET active_version_id = NULL WHERE id = ?", fixture.documentId());
            jdbcTemplate.update("UPDATE ingestion_job SET attempt = max_attempts WHERE id = ?", fixture.jobId());
            assertThat(ingestionJobRepository.retry(fixture.jobId(), ownerId)).isEmpty();
        } finally {
            jdbcTemplate.update("UPDATE document SET active_version_id = NULL, pending_version_id = NULL WHERE id = ?",
                    fixture.documentId());
            jdbcTemplate.update("DELETE FROM document_version WHERE id = ?", higherVersionId);
            deleteFixture(fixture);
        }
    }

    @Test
    void nonMemberGetsNotFoundForAnotherKnowledgeBaseJob() throws Exception {
        IngestionFixture fixture = createFixture();
        try {
            AuthenticatedRequest administrator = login("admin", "admin");
            AuthenticatedRequest supportUser = login("support2", "support2");

            mvc.perform(get("/api/ingestion-jobs/{jobId}", fixture.jobId())
                            .session(administrator.session()).cookie(administrator.cookie()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.jobId").value(fixture.jobId().toString()))
                    .andExpect(jsonPath("$.data.leaseOwner").doesNotExist())
                    .andExpect(jsonPath("$.data.leaseToken").doesNotExist())
                    .andExpect(jsonPath("$.data.errorDetail").doesNotExist());
            mvc.perform(get("/api/ingestion-jobs/{jobId}", fixture.jobId())
                            .session(supportUser.session()).cookie(supportUser.cookie()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INGESTION_JOB_NOT_FOUND"));
        } finally {
            deleteFixture(fixture);
        }
    }

    private Callable<Optional<IngestionJob>> claimTask(CountDownLatch ready, CountDownLatch start, String owner) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("并发领取测试未能同步开始");
            }
            return ingestionJobRepository.claimNextByStage(IngestionStageEnum.PARSE, owner, UUID.randomUUID(),
                    OffsetDateTime.now().plusSeconds(60));
        };
    }

    private IngestionFixture createFixture() {
        UUID ownerId = jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username = 'admin'", UUID.class);
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO knowledge_base (id, name, description, owner_id, status, revision)
                VALUES (?, ?, '验证 F06 入库任务', ?, 'ACTIVE', 0)
                """, knowledgeBaseId, "F06 入库任务 " + knowledgeBaseId, ownerId);
        jdbcTemplate.update("""
                INSERT INTO kb_member (kb_id, user_id, permission, granted_by, created_at)
                VALUES (?, ?, 'WRITE', ?, CURRENT_TIMESTAMP)
                """, knowledgeBaseId, ownerId, ownerId);
        jdbcTemplate.update("""
                INSERT INTO document (id, kb_id, name, active_version_id, pending_version_id, next_version_no,
                                      created_by, deleted_at, revision)
                VALUES (?, ?, 'F06 入库任务文档', NULL, NULL, 2, ?, NULL, 0)
                """, documentId, knowledgeBaseId, ownerId);
        jdbcTemplate.update("""
                INSERT INTO document_version (
                    id, document_id, version_no, storage_key, file_hash, file_size, media_type, original_filename,
                    status, parser_version, chunker_version, embedding_model, dimensions, config_hash,
                    config_snapshot, chunk_count
                ) VALUES (?, ?, 1, ?, ?, 17, 'text/markdown', 'f06.md', 'QUEUED', 'plain-text-v1',
                          'token-budget-v1', 'bge-m3', 1024, ?,
                          CAST('{"embedding":{"model":"bge-m3","dimensions":1024}}' AS JSONB), 0)
                """, versionId, documentId, "2026/09/" + versionId + ".md", "a".repeat(64), "b".repeat(64));
        jdbcTemplate.update("UPDATE document SET pending_version_id = ? WHERE id = ?", versionId, documentId);
        jdbcTemplate.update("""
                INSERT INTO ingestion_job (id, version_id, status, stage, attempt, max_attempts, next_run_at)
                VALUES (?, ?, 'QUEUED', 'PARSE', 0, 3, CURRENT_TIMESTAMP)
                """, jobId, versionId);
        return new IngestionFixture(knowledgeBaseId, documentId, versionId, jobId);
    }

    private void insertHigherVersion(UUID documentId, UUID versionId, String status) {
        jdbcTemplate.update("""
                INSERT INTO document_version (
                    id, document_id, version_no, storage_key, file_hash, file_size, media_type, original_filename,
                    status, parser_version, chunker_version, embedding_model, dimensions, config_hash,
                    config_snapshot, chunk_count
                ) VALUES (?, ?, 2, ?, ?, 19, 'text/markdown', 'f20-v2.md', ?, 'plain-text-v1',
                          'token-budget-v1', 'bge-m3', 1024, ?,
                          CAST('{"embedding":{"model":"bge-m3","dimensions":1024}}' AS JSONB), 0)
                """, versionId, documentId, "2026/09/" + versionId + ".md", "c".repeat(64), status,
                "d".repeat(64));
    }

    private void deleteFixture(IngestionFixture fixture) {
        if (fixture == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM ingestion_job WHERE id = ?", fixture.jobId());
        jdbcTemplate.update("UPDATE document SET active_version_id = NULL, pending_version_id = NULL WHERE id = ?",
                fixture.documentId());
        jdbcTemplate.update("DELETE FROM document_version WHERE id = ?", fixture.versionId());
        jdbcTemplate.update("DELETE FROM document WHERE id = ?", fixture.documentId());
        jdbcTemplate.update("DELETE FROM kb_member WHERE kb_id = ?", fixture.knowledgeBaseId());
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", fixture.knowledgeBaseId());
    }

    private AuthenticatedRequest login(String username, String password) throws Exception {
        SessionCsrf csrf = csrf(null, null);
        MvcResult result = mvc.perform(post("/api/auth/login").session(csrf.session())
                        .header(csrf.headerName(), csrf.token()).contentType("application/json")
                        .content(objectMapper.createObjectNode().put("username", username).put("password", password)
                                .toString()))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = loginCookie(result);
        SessionCsrf currentCsrf = csrf((MockHttpSession) result.getRequest().getSession(false), cookie);
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
        return new Cookie(IdentityOpenApiConfiguration.SA_TOKEN_COOKIE_NAME,
                setCookie.substring(prefix.length(), valueEnd < 0 ? setCookie.length() : valueEnd));
    }

    private record IngestionFixture(UUID knowledgeBaseId, UUID documentId, UUID versionId, UUID jobId) {
    }

    private record SessionCsrf(String token, String headerName, MockHttpSession session) {
    }

    private record AuthenticatedRequest(MockHttpSession session, Cookie cookie, String csrfHeader, String csrfToken) {
    }
}
