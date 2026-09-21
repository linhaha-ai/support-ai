package com.haha.supportai.knowledge.document;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.DocumentChunkContent;
import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.data.knowledge.entity.po.IngestionJob;
import com.haha.supportai.data.knowledge.enums.IngestionJobStatusEnum;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.data.knowledge.repository.KnowledgeDocumentRepository;
import com.haha.supportai.storage.PostgresFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key=", "spring.ai.ollama.base-url=http://127.0.0.1:1",
        "spring.ai.ollama.init.pull-model-strategy=never", "support.ai.ingestion.worker.enabled=false"
})
class DocumentQueryDatabaseIT {

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired
    KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Autowired
    DocumentVersionRepository documentVersionRepository;

    @Autowired
    DocumentChunkRepository documentChunkRepository;

    @Autowired
    IngestionJobRepository ingestionJobRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void readMemberListsDocumentsAndVersionsInDescendingCursorOrder() {
        QueryFixture fixture = createFixture();
        try {
            assertThat(knowledgeDocumentRepository.listAccessibleByKnowledgeBaseId(
                    fixture.knowledgeBaseId(), fixture.readerId()))
                    .extracting(document -> document.getId())
                    .containsExactlyInAnyOrder(fixture.documentId(), fixture.otherDocumentId());

            List<DocumentVersion> firstPage = documentVersionRepository.listAccessibleByDocumentId(
                    fixture.documentId(), fixture.readerId(), null, 2);
            assertThat(firstPage).extracting(DocumentVersion::getVersionNo).containsExactly(3, 2);

            List<DocumentVersion> secondPage = documentVersionRepository.listAccessibleByDocumentId(
                    fixture.documentId(), fixture.readerId(), firstPage.getLast().getVersionNo(), 2);
            assertThat(secondPage).extracting(DocumentVersion::getVersionNo).containsExactly(1);
            assertThat(documentVersionRepository.findAccessibleByDocumentIdAndIdAndUserId(
                    fixture.documentId(), fixture.versionTwoId(), fixture.readerId())).isPresent();
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void inaccessibleOrDeletedDocumentAndCrossDocumentVersionAreNotReturned() {
        QueryFixture fixture = createFixture();
        try {
            assertThat(knowledgeDocumentRepository.listAccessibleByKnowledgeBaseId(
                    fixture.knowledgeBaseId(), fixture.outsiderId())).isEmpty();
            assertThat(knowledgeDocumentRepository.findAccessibleByIdAndUserId(
                    fixture.documentId(), fixture.outsiderId())).isEmpty();
            assertThat(documentVersionRepository.listAccessibleByDocumentId(
                    fixture.documentId(), fixture.outsiderId(), null, 10)).isEmpty();
            assertThat(documentVersionRepository.findAccessibleByDocumentIdAndIdAndUserId(
                    fixture.documentId(), fixture.otherDocumentVersionId(), fixture.readerId())).isEmpty();

            assertThat(knowledgeDocumentRepository.listAccessibleByKnowledgeBaseId(
                    fixture.knowledgeBaseId(), fixture.disabledReaderId())).isEmpty();
            jdbcTemplate.update("UPDATE knowledge_base SET status = 'DISABLED' WHERE id = ?",
                    fixture.knowledgeBaseId());
            assertThat(knowledgeDocumentRepository.findAccessibleByIdAndUserId(
                    fixture.documentId(), fixture.readerId())).isEmpty();

            jdbcTemplate.update("UPDATE knowledge_base SET status = 'ACTIVE' WHERE id = ?",
                    fixture.knowledgeBaseId());
            jdbcTemplate.update("UPDATE document SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?",
                    fixture.documentId());
            assertThat(knowledgeDocumentRepository.listAccessibleByKnowledgeBaseId(
                    fixture.knowledgeBaseId(), fixture.readerId()))
                    .extracting(document -> document.getId())
                    .containsExactly(fixture.otherDocumentId());
            assertThat(documentVersionRepository.findAccessibleByDocumentIdAndIdAndUserId(
                    fixture.documentId(), fixture.versionThreeId(), fixture.readerId())).isEmpty();
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void enabledAdminWithoutKnowledgeBaseMembershipCannotReadDocumentsOrVersions() {
        QueryFixture fixture = createFixture();
        try {
            assertThat(knowledgeDocumentRepository.listAccessibleByKnowledgeBaseId(
                    fixture.knowledgeBaseId(), fixture.adminId())).isEmpty();
            assertThat(documentVersionRepository.listAccessibleByDocumentId(
                    fixture.documentId(), fixture.adminId(), null, 10)).isEmpty();
            assertThat(documentVersionRepository.findAccessibleByDocumentIdAndIdAndUserId(
                    fixture.documentId(), fixture.versionTwoId(), fixture.adminId())).isEmpty();
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void chunkContentUsesLeftJoinAndBatchJobQueryMapsPersistedFields() {
        QueryFixture fixture = createFixture();
        try {
            List<DocumentChunkContent> firstPage = documentChunkRepository.listContentByVersionId(
                    fixture.versionThreeId(), null, 2);
            assertThat(firstPage).extracting(DocumentChunkContent::getChunkIndex).containsExactly(0, 1);
            assertThat(firstPage).extracting(DocumentChunkContent::getContent).containsExactly(null, "已嵌入分块正文");

            List<DocumentChunkContent> secondPage = documentChunkRepository.listContentByVersionId(
                    fixture.versionThreeId(), firstPage.getLast().getChunkIndex(), 2);
            assertThat(secondPage).extracting(DocumentChunkContent::getChunkIndex).containsExactly(2);
            assertThat(secondPage).extracting(DocumentChunkContent::getContent).containsExactly("后续已嵌入分块正文");

            List<IngestionJob> jobs = ingestionJobRepository.findByVersionIds(List.of(
                    fixture.versionThreeId(), fixture.versionTwoId(), fixture.versionOneId()));
            assertThat(jobs).extracting(IngestionJob::getVersionId)
                    .containsExactlyInAnyOrder(fixture.versionThreeId(), fixture.versionTwoId(), fixture.versionOneId());
            assertThat(jobs).filteredOn(job -> job.getVersionId().equals(fixture.versionThreeId()))
                    .singleElement()
                    .satisfies(job -> {
                        assertThat(job.getStatus()).isEqualTo(IngestionJobStatusEnum.RUNNING);
                        assertThat(job.getStage()).isEqualTo(IngestionStageEnum.EMBED);
                        assertThat(job.getCompletedChunks()).isEqualTo(1);
                        assertThat(job.getTotalChunks()).isEqualTo(3);
                    });
        } finally {
            deleteFixture(fixture);
        }
    }

    private QueryFixture createFixture() {
        UUID readerId = UUID.randomUUID();
        UUID outsiderId = UUID.randomUUID();
        UUID disabledReaderId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID otherDocumentId = UUID.randomUUID();
        UUID versionOneId = UUID.randomUUID();
        UUID versionTwoId = UUID.randomUUID();
        UUID versionThreeId = UUID.randomUUID();
        UUID otherDocumentVersionId = UUID.randomUUID();
        UUID jobOneId = UUID.randomUUID();
        UUID jobTwoId = UUID.randomUUID();
        UUID jobThreeId = UUID.randomUUID();
        UUID chunkWithoutVectorId = UUID.randomUUID();
        UUID chunkWithVectorId = UUID.randomUUID();
        UUID laterChunkWithVectorId = UUID.randomUUID();
        jdbcTemplate.update("""
                -- app_user：F09 文档查询测试的已启用读取成员。
                INSERT INTO app_user (id, username, password_hash, display_name, role, enabled)
                VALUES (?, ?, 'test-password-hash', 'F09 读取成员', 'USER', TRUE)
                """, readerId, "f09-reader-" + readerId);
        jdbcTemplate.update("""
                -- app_user：F09 文档查询测试的无成员关系账号。
                INSERT INTO app_user (id, username, password_hash, display_name, role, enabled)
                VALUES (?, ?, 'test-password-hash', 'F09 非成员', 'USER', TRUE)
                """, outsiderId, "f09-outsider-" + outsiderId);
        jdbcTemplate.update("""
                -- app_user：F09 文档查询测试的已禁用成员账号。
                INSERT INTO app_user (id, username, password_hash, display_name, role, enabled)
                VALUES (?, ?, 'test-password-hash', 'F09 禁用成员', 'USER', FALSE)
                """, disabledReaderId, "f09-disabled-" + disabledReaderId);
        jdbcTemplate.update("""
                -- app_user：F09 文档查询测试的已启用管理员，但故意不创建知识库成员关系。
                INSERT INTO app_user (id, username, password_hash, display_name, role, enabled)
                VALUES (?, ?, 'test-password-hash', 'F09 无成员管理员', 'ADMIN', TRUE)
                """, adminId, "f09-admin-without-member-" + adminId);
        jdbcTemplate.update("""
                -- knowledge_base：F09 文档查询测试使用的启用知识库。
                INSERT INTO knowledge_base (id, name, description, owner_id, status, revision)
                VALUES (?, ?, '验证 F09 文档查询数据层契约', ?, 'ACTIVE', 0)
                """, knowledgeBaseId, "F09 文档查询知识库 " + knowledgeBaseId, readerId);
        jdbcTemplate.update("""
                -- kb_member：为已启用和禁用账号分别创建读取成员关系。
                INSERT INTO kb_member (kb_id, user_id, permission, granted_by, created_at)
                VALUES (?, ?, 'READ', ?, CURRENT_TIMESTAMP), (?, ?, 'READ', ?, CURRENT_TIMESTAMP)
                """, knowledgeBaseId, readerId, readerId, knowledgeBaseId, disabledReaderId, readerId);
        jdbcTemplate.update("""
                -- document：主文档用于验证版本倒序、权限过滤和逻辑删除过滤。
                INSERT INTO document (id, kb_id, name, active_version_id, pending_version_id, next_version_no,
                                      created_by, deleted_at, revision)
                VALUES (?, ?, 'F09 主文档', NULL, NULL, 4, ?, NULL, 0)
                """, documentId, knowledgeBaseId, readerId);
        jdbcTemplate.update("""
                -- document：同一知识库内的另一文档用于验证 documentId 与 versionId 不能串用。
                INSERT INTO document (id, kb_id, name, active_version_id, pending_version_id, next_version_no,
                                      created_by, deleted_at, revision)
                VALUES (?, ?, 'F09 另一文档', NULL, NULL, 2, ?, NULL, 0)
                """, otherDocumentId, knowledgeBaseId, readerId);
        insertVersion(versionOneId, documentId, 1, "READY", 0);
        insertVersion(versionTwoId, documentId, 2, "READY", 0);
        insertVersion(versionThreeId, documentId, 3, "BUILDING", 3);
        insertVersion(otherDocumentVersionId, otherDocumentId, 1, "READY", 0);
        jdbcTemplate.update("""
                -- document：活动版本和待处理版本均指向同一文档内的合法版本。
                UPDATE document
                SET active_version_id = ?, pending_version_id = ?
                WHERE id = ?
                """, versionTwoId, versionThreeId, documentId);
        jdbcTemplate.update("""
                -- ingestion_job：为主文档三个版本写入不同任务状态，验证批量映射。
                INSERT INTO ingestion_job (
                    id, version_id, status, stage, attempt, max_attempts, next_run_at, completed_chunks, total_chunks
                ) VALUES
                    (?, ?, 'SUCCEEDED', 'PUBLISH', 1, 3, CURRENT_TIMESTAMP, 0, 0),
                    (?, ?, 'QUEUED', 'PARSE', 0, 3, CURRENT_TIMESTAMP, NULL, NULL),
                    (?, ?, 'RUNNING', 'EMBED', 1, 3, CURRENT_TIMESTAMP, 1, 3)
                """, jobOneId, versionOneId, jobTwoId, versionTwoId, jobThreeId, versionThreeId);
        insertChunk(chunkWithoutVectorId, versionThreeId, 0, "未嵌入分块正文");
        insertChunk(chunkWithVectorId, versionThreeId, 1, "已嵌入分块正文");
        insertChunk(laterChunkWithVectorId, versionThreeId, 2, "后续已嵌入分块正文");
        insertVector(chunkWithVectorId, "已嵌入分块正文");
        insertVector(laterChunkWithVectorId, "后续已嵌入分块正文");
        return new QueryFixture(readerId, outsiderId, disabledReaderId, adminId, knowledgeBaseId, documentId, otherDocumentId,
                versionOneId, versionTwoId, versionThreeId, otherDocumentVersionId, jobOneId, jobTwoId, jobThreeId);
    }

    private void insertVersion(UUID versionId, UUID documentId, int versionNo, String status, int chunkCount) {
        jdbcTemplate.update("""
                -- document_version：F09 查询测试文档版本，固定 1024 维配置快照。
                INSERT INTO document_version (
                    id, document_id, version_no, storage_key, file_hash, file_size, media_type, original_filename,
                    status, parser_version, chunker_version, embedding_model, dimensions, config_hash,
                    config_snapshot, chunk_count
                ) VALUES (?, ?, ?, ?, ?, 32, 'text/markdown', ?, ?, 'plain-text-v1', 'token-budget-v1',
                          'bge-m3', 1024, ?,
                          CAST('{"embedding":{"model":"bge-m3","dimensions":1024}}' AS JSONB), ?)
                """, versionId, documentId, versionNo, "2026/09/" + versionId + ".md", "a".repeat(64),
                "f09-v" + versionNo + ".md", status, "b".repeat(64), chunkCount);
    }

    private void insertChunk(UUID chunkId, UUID versionId, int index, String content) {
        jdbcTemplate.update("""
                -- document_chunk：F09 分块查询测试数据，正文由 vector_store 作为唯一副本。
                INSERT INTO document_chunk (
                    id, version_id, chunk_index, section, page_no, start_offset, end_offset,
                    content_hash, char_count, token_count
                ) VALUES (?, ?, ?, 'F09 章节', 1, ?, ?, ?, ?, 2)
                """, chunkId, versionId, index, index * 10, index * 10 + content.length(), "c".repeat(64),
                content.length());
    }

    private void insertVector(UUID chunkId, String content) {
        jdbcTemplate.update("""
                -- vector_store：已完成嵌入的 F09 分块正文，验证 LEFT JOIN 能读取正文。
                INSERT INTO vector_store (id, content, metadata, embedding)
                VALUES (?, ?, CAST('{"source":"f09"}' AS JSON), CAST(? AS vector))
                """, chunkId, content, vectorLiteral());
    }

    private String vectorLiteral() {
        return "[" + "0,".repeat(1023) + "0]";
    }

    private void deleteFixture(QueryFixture fixture) {
        if (fixture == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM vector_store WHERE id IN (SELECT id FROM document_chunk WHERE version_id IN (?, ?, ?))",
                fixture.versionOneId(), fixture.versionTwoId(), fixture.versionThreeId());
        jdbcTemplate.update("DELETE FROM document_chunk WHERE version_id IN (?, ?, ?)", fixture.versionOneId(),
                fixture.versionTwoId(), fixture.versionThreeId());
        jdbcTemplate.update("DELETE FROM ingestion_job WHERE id IN (?, ?, ?)", fixture.jobOneId(), fixture.jobTwoId(),
                fixture.jobThreeId());
        jdbcTemplate.update("UPDATE document SET active_version_id = NULL, pending_version_id = NULL WHERE id IN (?, ?)",
                fixture.documentId(), fixture.otherDocumentId());
        jdbcTemplate.update("DELETE FROM document_version WHERE id IN (?, ?, ?, ?)", fixture.versionOneId(),
                fixture.versionTwoId(), fixture.versionThreeId(), fixture.otherDocumentVersionId());
        jdbcTemplate.update("DELETE FROM document WHERE id IN (?, ?)", fixture.documentId(), fixture.otherDocumentId());
        jdbcTemplate.update("DELETE FROM kb_member WHERE kb_id = ?", fixture.knowledgeBaseId());
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", fixture.knowledgeBaseId());
        jdbcTemplate.update("DELETE FROM app_user WHERE id IN (?, ?, ?, ?)", fixture.readerId(), fixture.outsiderId(),
                fixture.disabledReaderId(), fixture.adminId());
    }

    private record QueryFixture(UUID readerId, UUID outsiderId, UUID disabledReaderId, UUID adminId, UUID knowledgeBaseId,
                                UUID documentId, UUID otherDocumentId, UUID versionOneId, UUID versionTwoId,
                                UUID versionThreeId, UUID otherDocumentVersionId, UUID jobOneId, UUID jobTwoId,
                                UUID jobThreeId) {
    }
}
