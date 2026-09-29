package com.haha.supportai.knowledge.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.haha.supportai.data.knowledge.entity.po.DocumentChunk;
import com.haha.supportai.data.knowledge.entity.po.DocumentVector;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVectorRepository;
import com.haha.supportai.knowledge.document.embedding.exception.DocumentIndexIntegrityException;
import com.haha.supportai.knowledge.document.embedding.service.DocumentEmbeddingConfigurationValidator;
import com.haha.supportai.knowledge.document.embedding.service.DocumentIndexPublicationService;
import com.haha.supportai.knowledge.document.embedding.service.DocumentVectorPersistenceService;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import com.haha.supportai.knowledge.ingestion.exception.IngestionLeaseLostException;
import com.haha.supportai.storage.PostgresFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
        "spring.ai.ollama.init.pull-model-strategy=never", "support.ai.ingestion.worker.enabled=false",
        "support.ai.document.cleanup.enabled=false"
})
@Testcontainers
class DocumentVectorDatabaseIT {

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired
    DocumentVectorPersistenceService persistenceService;

    @Autowired
    DocumentIndexPublicationService publicationService;

    @Autowired
    DocumentChunkRepository documentChunkRepository;

    @Autowired
    DocumentVectorRepository documentVectorRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockBean
    DocumentEmbeddingConfigurationValidator configurationValidator;

    @Test
    void vectorStoreHasFourColumnsAndIdempotentlyPersists1024DimensionBatch() {
        Fixture fixture = createFixture("EMBED", false, 2);
        try {
            List<DocumentChunk> chunks = chunks(fixture.versionId(), fixture.chunkCount());
            List<DocumentVector> vectors = vectors(chunks);
            persistenceService.persistBatch(fixture.lease(), chunks, vectors, 2, 2);
            persistenceService.persistBatch(fixture.lease(), chunks, vectors, 2, 2);

            assertThat(jdbcTemplate.queryForList("""
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_name = 'vector_store'
                    ORDER BY ordinal_position
                    """, String.class)).containsExactly("id", "content", "metadata", "embedding");
            assertThat(documentVectorRepository.countByVersionId(fixture.versionId())).isEqualTo(2);
            DocumentVector savedVector = documentVectorRepository.findById(chunks.getFirst().getId());
            assertThat(savedVector.getEmbedding()).hasSize(1024);
            assertThat(savedVector.getEmbedding()[0]).isEqualTo(1.0F);
            assertThat(jdbcTemplate.queryForList("""
                    SELECT vector_dims(embedding)
                    FROM vector_store vector
                    INNER JOIN document_chunk chunk ON chunk.id = vector.id
                    WHERE chunk.version_id = ?
                    ORDER BY chunk.chunk_index
                    """, Integer.class, fixture.versionId())).containsExactly(1024, 1024);
            UUID invalidVectorId = UUID.randomUUID();
            documentChunkRepository.upsertBatch(List.of(new DocumentChunk(invalidVectorId, fixture.versionId(), 2,
                    "订单", null, 20, 24, "d".repeat(64), 4, 1)));
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO vector_store (id, content, metadata, embedding)
                    VALUES (?, '错误维度', CAST('{}' AS JSON), CAST('[1,2,3]' AS vector))
                    """, invalidVectorId)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void expiredLeaseCannotWriteVectorBatch() {
        Fixture fixture = createFixture("EMBED", true, 1);
        try {
            List<DocumentChunk> chunks = chunks(fixture.versionId(), fixture.chunkCount());

            assertThatThrownBy(() -> persistenceService.persistBatch(fixture.lease(), chunks, vectors(chunks), 1, 1))
                    .isInstanceOf(IngestionLeaseLostException.class);

            assertThat(documentVectorRepository.countByVersionId(fixture.versionId())).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE version_id = ?",
                    Integer.class, fixture.versionId())).isZero();
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void completePublishActivatesVersionAndSucceedsJobAtomically() {
        Fixture fixture = createFixture("PUBLISH", false, 2);
        try {
            List<DocumentChunk> chunks = chunks(fixture.versionId(), fixture.chunkCount());
            documentChunkRepository.upsertBatch(chunks);
            documentVectorRepository.upsertBatch(vectors(chunks));

            assertThat(publicationService.publish(fixture.lease())).isEqualTo(2);

            assertThat(jdbcTemplate.queryForObject("SELECT status FROM document_version WHERE id = ?", String.class,
                    fixture.versionId())).isEqualTo("READY");
            assertThat(jdbcTemplate.queryForObject("SELECT active_version_id FROM document WHERE id = ?", UUID.class,
                    fixture.documentId())).isEqualTo(fixture.versionId());
            assertThat(jdbcTemplate.queryForObject("SELECT pending_version_id IS NULL FROM document WHERE id = ?",
                    Boolean.class, fixture.documentId())).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
                    fixture.jobId())).isEqualTo("SUCCEEDED");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void incompleteIndexDoesNotPublishPendingVersionAfterMiddleBatchFailure() {
        Fixture fixture = createFixture("PUBLISH", false, 2);
        try {
            List<DocumentChunk> chunks = chunks(fixture.versionId(), fixture.chunkCount());
            documentChunkRepository.upsertBatch(chunks);
            documentVectorRepository.upsertBatch(List.of(vectors(chunks).getFirst()));

            assertThatThrownBy(() -> publicationService.publish(fixture.lease()))
                    .isInstanceOf(DocumentIndexIntegrityException.class);

            assertThat(jdbcTemplate.queryForObject("SELECT status FROM document_version WHERE id = ?", String.class,
                    fixture.versionId())).isEqualTo("BUILDING");
            assertThat(jdbcTemplate.queryForObject("SELECT active_version_id IS NULL FROM document WHERE id = ?",
                    Boolean.class, fixture.documentId())).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT pending_version_id FROM document WHERE id = ?", UUID.class,
                    fixture.documentId())).isEqualTo(fixture.versionId());
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
                    fixture.jobId())).isEqualTo("RUNNING");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void wrongLeaseTokenCannotPublishCompleteIndex() {
        Fixture fixture = createFixture("PUBLISH", false, 2);
        try {
            List<DocumentChunk> chunks = chunks(fixture.versionId(), fixture.chunkCount());
            documentChunkRepository.upsertBatch(chunks);
            documentVectorRepository.upsertBatch(vectors(chunks));
            IngestionTaskLease wrongLease = new IngestionTaskLease(fixture.jobId(), fixture.versionId(),
                    IngestionStageEnum.PUBLISH, 1, 3, fixture.leaseOwner(), UUID.randomUUID(),
                    OffsetDateTime.now().plusSeconds(60));

            assertThatThrownBy(() -> publicationService.publish(wrongLease)).isInstanceOf(IngestionLeaseLostException.class);

            assertUnpublished(fixture);
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void failedFinalJobTransitionRollsBackReadyAndActiveVersion() {
        Fixture fixture = createFixture("PUBLISH", false, 2);
        try {
            List<DocumentChunk> chunks = chunks(fixture.versionId(), fixture.chunkCount());
            documentChunkRepository.upsertBatch(chunks);
            documentVectorRepository.upsertBatch(vectors(chunks));
            jdbcTemplate.update("UPDATE ingestion_job SET completed_chunks = 1, total_chunks = 2 WHERE id = ?",
                    fixture.jobId());

            assertThatThrownBy(() -> publicationService.publish(fixture.lease()))
                    .isInstanceOf(IngestionLeaseLostException.class);

            assertUnpublished(fixture);
        } finally {
            deleteFixture(fixture);
        }
    }

    private Fixture createFixture(String stage, boolean expiredLease, int chunkCount) {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        String leaseOwner = "f08-vector-worker-" + jobId;
        jdbcTemplate.update("""
                INSERT INTO app_user (id, username, password_hash, display_name, role, enabled)
                VALUES (?, ?, 'test-password-hash', 'F08 向量测试用户', 'USER', TRUE)
                """, userId, "f08-vector-" + userId);
        jdbcTemplate.update("""
                INSERT INTO knowledge_base (id, name, description, owner_id, status, revision)
                VALUES (?, ?, '验证 F08 向量阶段', ?, 'ACTIVE', 0)
                """, knowledgeBaseId, "F08 向量知识库 " + knowledgeBaseId, userId);
        jdbcTemplate.update("""
                INSERT INTO kb_member (kb_id, user_id, permission, granted_by, created_at)
                VALUES (?, ?, 'WRITE', ?, CURRENT_TIMESTAMP)
                """, knowledgeBaseId, userId, userId);
        jdbcTemplate.update("""
                INSERT INTO document (id, kb_id, name, active_version_id, pending_version_id, next_version_no,
                                      created_by, deleted_at, revision)
                VALUES (?, ?, 'F08 向量文档', NULL, NULL, 2, ?, NULL, 0)
                """, documentId, knowledgeBaseId, userId);
        jdbcTemplate.update("""
                INSERT INTO document_version (
                    id, document_id, version_no, storage_key, file_hash, file_size, media_type, original_filename,
                    status, parser_version, chunker_version, embedding_model, dimensions, config_hash,
                    config_snapshot, chunk_count
                ) VALUES (?, ?, 1, ?, ?, 12, 'text/markdown', 'f08.md', 'BUILDING', 'plain-text-v1',
                          'token-budget-v1', 'bge-m3', 1024, ?,
                          CAST('{"embedding":{"provider":"ollama","model":"bge-m3","dimensions":1024}}' AS JSONB), ?)
                """, versionId, documentId, "2026/09/" + versionId + ".md", "a".repeat(64), "b".repeat(64),
                chunkCount);
        jdbcTemplate.update("UPDATE document SET pending_version_id = ? WHERE id = ?", versionId, documentId);
        jdbcTemplate.update("""
                INSERT INTO ingestion_job (
                    id, version_id, status, stage, attempt, max_attempts, lease_owner, lease_token, lease_until,
                    next_run_at, completed_chunks, total_chunks
                ) VALUES (?, ?, 'RUNNING', ?, 1, 3, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?)
                """, jobId, versionId, stage, leaseOwner, leaseToken,
                expiredLease ? OffsetDateTime.now().minusSeconds(1) : OffsetDateTime.now().plusSeconds(60),
                "PUBLISH".equals(stage) ? chunkCount : 0, "PUBLISH".equals(stage) ? chunkCount : null);
        return new Fixture(userId, knowledgeBaseId, documentId, versionId, jobId, leaseOwner, leaseToken, stage,
                chunkCount);
    }

    private List<DocumentChunk> chunks(UUID versionId, int chunkCount) {
        return IntStream.range(0, chunkCount)
                .mapToObj(index -> chunk(versionId, index, index == 0 ? "订单支付说明" : "订单取消说明"))
                .toList();
    }

    private DocumentChunk chunk(UUID versionId, int index, String content) {
        return new DocumentChunk(UUID.nameUUIDFromBytes((versionId + ":" + index).getBytes()), versionId, index,
                "订单", null, index * 10, index * 10 + content.length(), Integer.toHexString(content.hashCode())
                + "a".repeat(64 - Integer.toHexString(content.hashCode()).length()), content.length(), 3);
    }

    private List<DocumentVector> vectors(List<DocumentChunk> chunks) {
        return chunks.stream().map(chunk -> new DocumentVector(chunk.getId(), "分块-" + chunk.getChunkIndex(),
                "{\"chunkIndex\":" + chunk.getChunkIndex() + "}", vector(chunk.getChunkIndex() + 1))).toList();
    }

    private float[] vector(int value) {
        float[] vector = new float[1024];
        vector[0] = value;
        return vector;
    }

    private void deleteFixture(Fixture fixture) {
        jdbcTemplate.update("DELETE FROM vector_store WHERE id IN (SELECT id FROM document_chunk WHERE version_id = ?)",
                fixture.versionId());
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

    private void assertUnpublished(Fixture fixture) {
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM document_version WHERE id = ?", String.class,
                fixture.versionId())).isEqualTo("BUILDING");
        assertThat(jdbcTemplate.queryForObject("SELECT active_version_id IS NULL FROM document WHERE id = ?",
                Boolean.class, fixture.documentId())).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT pending_version_id FROM document WHERE id = ?", UUID.class,
                fixture.documentId())).isEqualTo(fixture.versionId());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
                fixture.jobId())).isEqualTo("RUNNING");
    }

    private record Fixture(UUID userId, UUID knowledgeBaseId, UUID documentId, UUID versionId, UUID jobId,
                           String leaseOwner, UUID leaseToken, String stage, int chunkCount) {

        private IngestionTaskLease lease() {
            return new IngestionTaskLease(jobId, versionId, IngestionStageEnum.valueOf(stage), 1, 3, leaseOwner,
                    leaseToken, OffsetDateTime.now().plusSeconds(60));
        }
    }
}
