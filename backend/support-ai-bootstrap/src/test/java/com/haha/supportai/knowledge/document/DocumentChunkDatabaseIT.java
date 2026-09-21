package com.haha.supportai.knowledge.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.DocumentChunk;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.knowledge.document.parsing.entity.dto.DocumentChunkDraft;
import com.haha.supportai.knowledge.document.parsing.service.DocumentChunkPersistenceService;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import com.haha.supportai.knowledge.ingestion.exception.IngestionLeaseLostException;
import com.haha.supportai.storage.PostgresFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key=", "spring.ai.ollama.base-url=http://127.0.0.1:1",
        "spring.ai.ollama.init.pull-model-strategy=never", "support.ai.ingestion.worker.enabled=false"
})
@Testcontainers
class DocumentChunkDatabaseIT {

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired
    DocumentChunkPersistenceService documentChunkPersistenceService;

    @Autowired
    DocumentChunkRepository documentChunkRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void activeLeaseCreatesIdempotentChunksAndBuildsVersion() {
        Fixture fixture = createFixture(false);
        try {
            documentChunkPersistenceService.replaceChunks(fixture.lease(), drafts());
            List<UUID> firstIds = chunkIds(fixture.versionId());
            documentChunkPersistenceService.replaceChunks(fixture.lease(), updatedDrafts());

            assertThat(chunkIds(fixture.versionId())).containsExactlyElementsOf(firstIds);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE version_id = ?",
                    Integer.class, fixture.versionId())).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM document_version WHERE id = ?", String.class,
                    fixture.versionId())).isEqualTo("BUILDING");
            assertThat(jdbcTemplate.queryForObject("SELECT chunk_count FROM document_version WHERE id = ?", Integer.class,
                    fixture.versionId())).isEqualTo(2);
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void batchUpsertKeepsExistingChunkIdAndV8ConstraintsRejectInvalidChunk() {
        Fixture fixture = createFixture(false);
        try {
            UUID originalId = UUID.randomUUID();
            documentChunkRepository.upsertBatch(List.of(new DocumentChunk(originalId, fixture.versionId(), 0, null,
                    null, 0, 2, "a".repeat(64), 2, 1)));
            documentChunkRepository.upsertBatch(List.of(new DocumentChunk(UUID.randomUUID(), fixture.versionId(), 0,
                    "更新章节", null, 0, 2, "b".repeat(64), 2, 1)));

            assertThat(jdbcTemplate.queryForObject("SELECT id FROM document_chunk WHERE version_id = ? AND chunk_index = 0",
                    UUID.class, fixture.versionId())).isEqualTo(originalId);
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO document_chunk (
                        id, version_id, chunk_index, start_offset, end_offset, content_hash, char_count, token_count
                    ) VALUES (?, ?, 1, 0, 2, ?, 3, 1)
                    """, UUID.randomUUID(), fixture.versionId(), "c".repeat(64)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void expiredLeaseCannotWriteDocumentChunks() {
        Fixture fixture = createFixture(true);
        try {
            assertThatThrownBy(() -> documentChunkPersistenceService.replaceChunks(fixture.lease(), drafts()))
                    .isInstanceOf(IngestionLeaseLostException.class);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE version_id = ?", Integer.class,
                    fixture.versionId())).isZero();
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void replacedPendingVersionCannotWriteDocumentChunks() {
        Fixture fixture = createFixture(false);
        try {
            jdbcTemplate.update("UPDATE document SET pending_version_id = NULL WHERE id = ?", fixture.documentId());

            assertThatThrownBy(() -> documentChunkPersistenceService.replaceChunks(fixture.lease(), drafts()))
                    .isInstanceOf(IngestionLeaseLostException.class);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE version_id = ?",
                    Integer.class, fixture.versionId())).isZero();
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void deletedDocumentCannotWriteDocumentChunks() {
        Fixture fixture = createFixture(false);
        try {
            jdbcTemplate.update("UPDATE document SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?",
                    fixture.documentId());

            assertThatThrownBy(() -> documentChunkPersistenceService.replaceChunks(fixture.lease(), drafts()))
                    .isInstanceOf(IngestionLeaseLostException.class);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE version_id = ?",
                    Integer.class, fixture.versionId())).isZero();
        } finally {
            deleteFixture(fixture);
        }
    }

    private Fixture createFixture(boolean expiredLease) {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        String leaseOwner = "f07-worker-" + jobId;
        jdbcTemplate.update("""
                INSERT INTO app_user (id, username, password_hash, display_name, role, enabled)
                VALUES (?, ?, 'test-password-hash', 'F07 分块测试用户', 'USER', TRUE)
                """, userId, "f07-chunk-" + userId);
        jdbcTemplate.update("""
                INSERT INTO knowledge_base (id, name, description, owner_id, status, revision)
                VALUES (?, ?, '验证 F07 分块持久化', ?, 'ACTIVE', 0)
                """, knowledgeBaseId, "F07 分块知识库 " + knowledgeBaseId, userId);
        jdbcTemplate.update("""
                INSERT INTO kb_member (kb_id, user_id, permission, granted_by, created_at)
                VALUES (?, ?, 'WRITE', ?, CURRENT_TIMESTAMP)
                """, knowledgeBaseId, userId, userId);
        jdbcTemplate.update("""
                INSERT INTO document (id, kb_id, name, active_version_id, pending_version_id, next_version_no,
                                      created_by, deleted_at, revision)
                VALUES (?, ?, 'F07 分块文档', NULL, NULL, 2, ?, NULL, 0)
                """, documentId, knowledgeBaseId, userId);
        jdbcTemplate.update("""
                INSERT INTO document_version (
                    id, document_id, version_no, storage_key, file_hash, file_size, media_type, original_filename,
                    status, parser_version, chunker_version, embedding_model, dimensions, config_hash,
                    config_snapshot, chunk_count
                ) VALUES (?, ?, 1, ?, ?, 8, 'text/markdown', 'f07.md', 'QUEUED', 'plain-text-v1',
                          'token-budget-v1', 'bge-m3', 1024, ?,
                          CAST('{"embedding":{"model":"bge-m3","dimensions":1024}}' AS JSONB), 0)
                """, versionId, documentId, "2026/09/" + versionId + ".md", "a".repeat(64), "b".repeat(64));
        jdbcTemplate.update("UPDATE document SET pending_version_id = ? WHERE id = ?", versionId, documentId);
        jdbcTemplate.update("""
                INSERT INTO ingestion_job (
                    id, version_id, status, stage, attempt, max_attempts, lease_owner, lease_token, lease_until,
                    next_run_at
                ) VALUES (?, ?, 'RUNNING', 'CHUNK', 1, 3, ?, ?, ?, CURRENT_TIMESTAMP)
                """, jobId, versionId, leaseOwner, leaseToken,
                expiredLease ? OffsetDateTime.now().minusSeconds(1) : OffsetDateTime.now().plusSeconds(60));
        return new Fixture(userId, knowledgeBaseId, documentId, versionId, jobId, leaseOwner, leaseToken);
    }

    private List<UUID> chunkIds(UUID versionId) {
        return jdbcTemplate.queryForList("SELECT id FROM document_chunk WHERE version_id = ? ORDER BY chunk_index",
                UUID.class, versionId);
    }

    private List<DocumentChunkDraft> drafts() {
        return List.of(
                draft(0, "支付", 0, "支付流程"),
                draft(1, "", 4, "处理说明"));
    }

    private List<DocumentChunkDraft> updatedDrafts() {
        return List.of(
                draft(0, "支付更新", 0, "付款流程"),
                draft(1, "", 4, "更新说明"));
    }

    private DocumentChunkDraft draft(int index, String section, int startOffset, String content) {
        return new DocumentChunkDraft(index, section, startOffset, startOffset + content.length(), sha256(content),
                content.length(), 2, content);
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError("测试环境不支持 SHA-256", exception);
        }
    }

    private void deleteFixture(Fixture fixture) {
        jdbcTemplate.update("DELETE FROM document_chunk WHERE version_id = ?", fixture.versionId());
        jdbcTemplate.update("DELETE FROM ingestion_job WHERE id = ?", fixture.jobId());
        jdbcTemplate.update("UPDATE document SET active_version_id = NULL, pending_version_id = NULL WHERE id = ?",
                fixture.documentId());
        jdbcTemplate.update("DELETE FROM document_version WHERE id = ?", fixture.versionId());
        jdbcTemplate.update("DELETE FROM document WHERE id = ?", fixture.documentId());
        jdbcTemplate.update("DELETE FROM kb_member WHERE kb_id = ?", fixture.knowledgeBaseId());
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", fixture.knowledgeBaseId());
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", fixture.userId());
    }

    private record Fixture(UUID userId, UUID knowledgeBaseId, UUID documentId, UUID versionId, UUID jobId,
                           String leaseOwner, UUID leaseToken) {

        private IngestionTaskLease lease() {
            return new IngestionTaskLease(jobId, versionId, IngestionStageEnum.CHUNK,
                    1, 3, leaseOwner, leaseToken, OffsetDateTime.now().plusSeconds(60));
        }
    }
}
