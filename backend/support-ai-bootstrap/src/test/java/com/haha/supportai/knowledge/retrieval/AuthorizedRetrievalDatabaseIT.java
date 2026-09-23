package com.haha.supportai.knowledge.retrieval;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.AuthorizedDocumentSource;
import com.haha.supportai.data.knowledge.entity.po.AuthorizedRetrievalHit;
import com.haha.supportai.data.knowledge.repository.AuthorizedDocumentSourceRepository;
import com.haha.supportai.data.knowledge.repository.AuthorizedRetrievalRepository;
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
class AuthorizedRetrievalDatabaseIT {

    private static final String EMBEDDING_PROVIDER = "ollama";
    private static final String EMBEDDING_MODEL = "bge-m3";
    private static final int DIMENSIONS = 1024;
    private static final String PROCESSING_CONFIG_HASH = "b".repeat(64);
    private static final String ERROR_CODE_PATTERN =
            "(^|[^A-Za-z0-9_-])pay-cb-1001([^A-Za-z0-9_-]|$)";
    private static final String API_PATH_PATTERN =
            "(^|[^A-Za-z0-9_{}./%-])/api/v1/orders/\\{merchantorderno\\}([^A-Za-z0-9_{}./%-]|$)";

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired
    AuthorizedRetrievalRepository authorizedRetrievalRepository;

    @Autowired
    AuthorizedDocumentSourceRepository authorizedDocumentSourceRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void filtersAuthorizationAndPublicationStateBeforeVectorTopK() {
        Fixture fixture = createFixture();
        try {
            float[] queryEmbedding = vector(1.0F, 0.0F);

            List<AuthorizedRetrievalHit> singleHit = authorizedRetrievalRepository.searchWritable(fixture.writerId(),
                    fixture.activeKnowledgeBaseId(), queryEmbedding, 1, EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS,
                    PROCESSING_CONFIG_HASH);
            assertThat(singleHit).extracting(AuthorizedRetrievalHit::getChunkId)
                    .containsExactly(fixture.authorizedDocument().chunkId());

            List<AuthorizedRetrievalHit> writableHits = authorizedRetrievalRepository.searchWritable(fixture.writerId(),
                    fixture.activeKnowledgeBaseId(), queryEmbedding, 10, EMBEDDING_PROVIDER, EMBEDDING_MODEL,
                    DIMENSIONS, PROCESSING_CONFIG_HASH);
            assertThat(writableHits).extracting(AuthorizedRetrievalHit::getChunkId)
                    .containsExactlyInAnyOrder(fixture.authorizedDocument().chunkId(), fixture.currentVersionDocument().chunkId());
            assertThat(writableHits).extracting(AuthorizedRetrievalHit::getDocumentName)
                    .containsExactlyInAnyOrder("F11 已授权已发布文档", "F11 当前活动版本文档");

            assertThat(authorizedRetrievalRepository.searchWritable(fixture.readerId(), fixture.activeKnowledgeBaseId(),
                    queryEmbedding, 10, EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS,
                    PROCESSING_CONFIG_HASH)).isEmpty();
            assertThat(authorizedRetrievalRepository.searchWritable(fixture.outsiderId(), fixture.activeKnowledgeBaseId(),
                    queryEmbedding, 10, EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS,
                    PROCESSING_CONFIG_HASH)).isEmpty();

            List<AuthorizedRetrievalHit> readableHits = authorizedRetrievalRepository.searchReadableWithinSession(
                    fixture.readerId(), fixture.readerSessionId(), queryEmbedding, 10, EMBEDDING_PROVIDER,
                    EMBEDDING_MODEL, DIMENSIONS, PROCESSING_CONFIG_HASH);
            assertThat(readableHits).extracting(AuthorizedRetrievalHit::getChunkId)
                    .containsExactlyInAnyOrder(fixture.authorizedDocument().chunkId(), fixture.currentVersionDocument().chunkId());
            assertThat(authorizedRetrievalRepository.searchReadableWithinSession(fixture.writerId(),
                    fixture.readerSessionId(), queryEmbedding, 10, EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS,
                    PROCESSING_CONFIG_HASH)).isEmpty();
            assertThat(authorizedRetrievalRepository.searchReadableWithinSession(fixture.readerId(),
                    fixture.deletedReaderSessionId(), queryEmbedding, 10, EMBEDDING_PROVIDER, EMBEDDING_MODEL,
                    DIMENSIONS, PROCESSING_CONFIG_HASH)).isEmpty();
            assertThat(authorizedRetrievalRepository.searchReadableWithinSession(fixture.readerId(),
                    fixture.assistantReaderSessionId(), queryEmbedding, 10, EMBEDDING_PROVIDER, EMBEDDING_MODEL,
                    DIMENSIONS, PROCESSING_CONFIG_HASH)).isEmpty();
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void keywordRoutesUseExactMatchingAndTheSameAuthorizationBoundary() {
        Fixture fixture = createFixture();
        try {
            List<AuthorizedRetrievalHit> writableHits = authorizedRetrievalRepository.searchWritableByKeywords(
                    fixture.writerId(), fixture.activeKnowledgeBaseId(), List.of(ERROR_CODE_PATTERN), 10,
                    EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS, PROCESSING_CONFIG_HASH);
            assertThat(writableHits).extracting(AuthorizedRetrievalHit::getChunkId)
                    .containsExactly(fixture.authorizedDocument().chunkId());

            List<AuthorizedRetrievalHit> pathHits = authorizedRetrievalRepository.searchReadableWithinSessionByKeywords(
                    fixture.readerId(), fixture.readerSessionId(), List.of(API_PATH_PATTERN), 10,
                    EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS, PROCESSING_CONFIG_HASH);
            assertThat(pathHits).extracting(AuthorizedRetrievalHit::getChunkId)
                    .containsExactly(fixture.authorizedDocument().chunkId());

            assertThat(authorizedRetrievalRepository.searchWritableByKeywords(fixture.readerId(),
                    fixture.activeKnowledgeBaseId(), List.of(ERROR_CODE_PATTERN), 10,
                    EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS, PROCESSING_CONFIG_HASH)).isEmpty();
            assertThat(authorizedRetrievalRepository.searchReadableWithinSessionByKeywords(fixture.readerId(),
                    fixture.deletedReaderSessionId(), List.of(ERROR_CODE_PATTERN), 10,
                    EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS, PROCESSING_CONFIG_HASH)).isEmpty();
            assertThat(authorizedRetrievalRepository.searchReadableWithinSessionByKeywords(fixture.disabledMemberId(),
                    fixture.disabledMemberSessionId(), List.of(ERROR_CODE_PATTERN), 10,
                    EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS, PROCESSING_CONFIG_HASH)).isEmpty();
            assertThat(authorizedRetrievalRepository.searchReadableWithinSessionByKeywords(fixture.readerId(),
                    fixture.readerSessionId(), List.of(ERROR_CODE_PATTERN), 10,
                    "dashscope", "text-embedding-v4", DIMENSIONS, PROCESSING_CONFIG_HASH)).isEmpty();
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void returnsNoHitForDisabledMemberOrDifferentProcessingConfiguration() {
        Fixture fixture = createFixture();
        try {
            float[] queryEmbedding = vector(1.0F, 0.0F);
            String differentConfigHash = "d".repeat(64);

            assertThat(authorizedRetrievalRepository.searchReadableWithinSession(fixture.disabledMemberId(),
                    fixture.disabledMemberSessionId(), queryEmbedding, 10, EMBEDDING_PROVIDER, EMBEDDING_MODEL,
                    DIMENSIONS, PROCESSING_CONFIG_HASH)).isEmpty();
            assertThat(authorizedRetrievalRepository.searchReadableWithinSession(fixture.readerId(),
                    fixture.readerSessionId(), queryEmbedding, 10, "dashscope", "text-embedding-v4", DIMENSIONS,
                    PROCESSING_CONFIG_HASH)).isEmpty();
            assertThat(authorizedRetrievalRepository.searchReadableWithinSession(fixture.readerId(),
                    fixture.readerSessionId(), queryEmbedding, 10, EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS,
                    differentConfigHash)).isEmpty();
            assertThat(authorizedRetrievalRepository.searchWritable(fixture.writerId(),
                    fixture.activeKnowledgeBaseId(), queryEmbedding, 10, EMBEDDING_PROVIDER, EMBEDDING_MODEL,
                    DIMENSIONS, differentConfigHash)).isEmpty();
            assertThat(authorizedRetrievalRepository.searchWritableByKeywords(fixture.writerId(),
                    fixture.activeKnowledgeBaseId(), List.of(ERROR_CODE_PATTERN), 10,
                    EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS, differentConfigHash)).isEmpty();
            assertThat(authorizedRetrievalRepository.searchReadableWithinSessionByKeywords(fixture.readerId(),
                    fixture.readerSessionId(), List.of(ERROR_CODE_PATTERN), 10,
                    EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS, differentConfigHash)).isEmpty();
            assertThat(authorizedRetrievalRepository.revalidateReadableWithinSession(fixture.readerId(),
                    fixture.readerSessionId(), List.of(fixture.authorizedDocument().chunkId()),
                    EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS, differentConfigHash)).isEmpty();
            assertThat(authorizedRetrievalRepository.searchReadableWithinSession(fixture.readerId(), UUID.randomUUID(),
                    queryEmbedding, 10, EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS,
                    PROCESSING_CONFIG_HASH)).isEmpty();
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void revalidatesOnlyCurrentEvidenceButAllowsAuthorizedHistoricalSourcePreview() {
        Fixture fixture = createFixture();
        try {
            List<AuthorizedRetrievalHit> revalidated = authorizedRetrievalRepository.revalidateReadableWithinSession(
                    fixture.readerId(), fixture.readerSessionId(),
                    List.of(fixture.historicalDocument().chunkId(), fixture.authorizedDocument().chunkId()),
                    EMBEDDING_PROVIDER, EMBEDDING_MODEL, DIMENSIONS, PROCESSING_CONFIG_HASH);

            assertThat(revalidated).extracting(AuthorizedRetrievalHit::getChunkId)
                    .containsExactly(fixture.authorizedDocument().chunkId());

            AuthorizedDocumentSource historicalSource = authorizedDocumentSourceRepository.findReadableByChunkId(
                    fixture.readerId(), fixture.historicalDocument().chunkId()).orElseThrow();
            assertThat(historicalSource.isCurrent()).isFalse();
            assertThat(historicalSource.getContent()).contains("F11 非活动版本文档");
            assertThat(authorizedDocumentSourceRepository.findReadableByChunkId(
                    fixture.readerId(), fixture.authorizedDocument().chunkId()).orElseThrow().isCurrent()).isTrue();
            assertThat(authorizedDocumentSourceRepository.findReadableByChunkId(
                    fixture.outsiderId(), fixture.authorizedDocument().chunkId())).isEmpty();
            assertThat(authorizedDocumentSourceRepository.findReadableByChunkId(
                    fixture.disabledMemberId(), fixture.authorizedDocument().chunkId())).isEmpty();

            jdbcTemplate.update("DELETE FROM kb_member WHERE kb_id = ? AND user_id = ?",
                    fixture.activeKnowledgeBaseId(), fixture.readerId());
            assertThat(authorizedDocumentSourceRepository.findReadableByChunkId(
                    fixture.readerId(), fixture.authorizedDocument().chunkId())).isEmpty();
        } finally {
            deleteFixture(fixture);
        }
    }

    private Fixture createFixture() {
        UUID ownerId = UUID.randomUUID();
        UUID writerId = UUID.randomUUID();
        UUID readerId = UUID.randomUUID();
        UUID outsiderId = UUID.randomUUID();
        UUID disabledMemberId = UUID.randomUUID();
        UUID activeKnowledgeBaseId = UUID.randomUUID();
        UUID inactiveKnowledgeBaseId = UUID.randomUUID();
        UUID outsideKnowledgeBaseId = UUID.randomUUID();
        insertUser(ownerId, "f11-owner-" + ownerId, true, "USER");
        insertUser(writerId, "f11-writer-" + writerId, true, "USER");
        insertUser(readerId, "f11-reader-" + readerId, true, "USER");
        insertUser(outsiderId, "f11-outsider-" + outsiderId, true, "ADMIN");
        insertUser(disabledMemberId, "f11-disabled-" + disabledMemberId, false, "USER");
        insertKnowledgeBase(activeKnowledgeBaseId, ownerId, "ACTIVE");
        insertKnowledgeBase(inactiveKnowledgeBaseId, ownerId, "DISABLED");
        insertKnowledgeBase(outsideKnowledgeBaseId, outsiderId, "ACTIVE");
        insertMember(activeKnowledgeBaseId, writerId, "WRITE", ownerId);
        insertMember(activeKnowledgeBaseId, readerId, "READ", ownerId);
        insertMember(inactiveKnowledgeBaseId, readerId, "READ", ownerId);
        insertMember(activeKnowledgeBaseId, disabledMemberId, "WRITE", ownerId);
        insertMember(inactiveKnowledgeBaseId, writerId, "WRITE", ownerId);
        insertMember(outsideKnowledgeBaseId, outsiderId, "WRITE", outsiderId);
        UUID readerSessionId = insertSession(readerId, "RAG", false,
                List.of(activeKnowledgeBaseId, inactiveKnowledgeBaseId, outsideKnowledgeBaseId));
        UUID deletedReaderSessionId = insertSession(readerId, "RAG", true, List.of(activeKnowledgeBaseId));
        UUID assistantReaderSessionId = insertSession(readerId, "ASSISTANT", false, List.of(activeKnowledgeBaseId));
        UUID disabledMemberSessionId = insertSession(disabledMemberId, "RAG", false, List.of(activeKnowledgeBaseId));

        DocumentFixture authorizedDocument = insertDocument(activeKnowledgeBaseId, ownerId, "F11 已授权已发布文档",
                "READY", EMBEDDING_PROVIDER, EMBEDDING_MODEL, true, false, vector(0.8F, 0.6F));
        DocumentFixture currentVersionDocument = insertDocument(activeKnowledgeBaseId, ownerId, "F11 当前活动版本文档",
                "READY", EMBEDDING_PROVIDER, EMBEDDING_MODEL, true, false, vector(0.7F, 0.7F));
        DocumentFixture historicalDocument = insertDocument(activeKnowledgeBaseId, ownerId, "F11 非活动版本文档", "READY", EMBEDDING_PROVIDER,
                EMBEDDING_MODEL, false, false, vector(0.99F, 0.1F));
        insertDocument(activeKnowledgeBaseId, ownerId, "F11 未就绪版本文档", "BUILDING", EMBEDDING_PROVIDER,
                EMBEDDING_MODEL, true, false, vector(0.98F, 0.2F));
        insertDocument(activeKnowledgeBaseId, ownerId, "F11 已删除文档", "READY", EMBEDDING_PROVIDER, EMBEDDING_MODEL,
                true, true, vector(0.97F, 0.25F));
        insertDocument(activeKnowledgeBaseId, ownerId, "F11 提供方不匹配文档", "READY", "dashscope", EMBEDDING_MODEL,
                true, false, vector(0.96F, 0.28F));
        insertDocument(activeKnowledgeBaseId, ownerId, "F11 模型不匹配文档", "READY", EMBEDDING_PROVIDER, "other-model",
                true, false, vector(0.95F, 0.31F));
        DocumentFixture dimensionMismatchDocument = insertDocument(activeKnowledgeBaseId, ownerId, "F11 维度快照不匹配文档",
                "READY", EMBEDDING_PROVIDER, EMBEDDING_MODEL, true, false, vector(0.945F, 0.32F));
        jdbcTemplate.update("""
                -- document_version：将配置快照维度改为非当前空间，验证检索不能混用同列向量。
                UPDATE document_version
                SET config_snapshot = CAST('{"embedding":{"provider":"ollama","model":"bge-m3","dimensions":768}}' AS JSONB)
                WHERE id = ?
                """, dimensionMismatchDocument.versionId());
        insertDocument(inactiveKnowledgeBaseId, ownerId, "F11 停用知识库文档", "READY", EMBEDDING_PROVIDER,
                EMBEDDING_MODEL, true, false, vector(0.94F, 0.34F));
        insertDocument(outsideKnowledgeBaseId, outsiderId, "F11 外部高相似文档", "READY", EMBEDDING_PROVIDER,
                EMBEDDING_MODEL, true, false, vector(1.0F, 0.0F));

        return new Fixture(ownerId, writerId, readerId, outsiderId, disabledMemberId, activeKnowledgeBaseId,
                inactiveKnowledgeBaseId, outsideKnowledgeBaseId, readerSessionId, deletedReaderSessionId,
                assistantReaderSessionId, disabledMemberSessionId, authorizedDocument, currentVersionDocument,
                historicalDocument);
    }

    private void insertUser(UUID userId, String username, boolean enabled, String role) {
        jdbcTemplate.update("""
                -- app_user：F11 授权检索测试账号，enabled 和管理员角色均不能绕过知识库成员关系。
                INSERT INTO app_user (id, username, password_hash, display_name, role, enabled)
                VALUES (?, ?, 'test-password-hash', 'F11 授权检索测试账号', ?, ?)
                """, userId, username, role, enabled);
    }

    private void insertKnowledgeBase(UUID knowledgeBaseId, UUID ownerId, String status) {
        jdbcTemplate.update("""
                -- knowledge_base：F11 授权检索测试知识库，status 用于验证 ACTIVE 范围过滤。
                INSERT INTO knowledge_base (id, name, description, owner_id, status, revision)
                VALUES (?, ?, 'F11 授权检索数据库测试', ?, ?, 0)
                """, knowledgeBaseId, "F11 知识库 " + knowledgeBaseId, ownerId, status);
    }

    private void insertMember(UUID knowledgeBaseId, UUID userId, String permission, UUID grantedBy) {
        jdbcTemplate.update("""
                -- kb_member：F11 授权检索测试成员关系，permission 区分 READ 与 WRITE。
                INSERT INTO kb_member (kb_id, user_id, permission, granted_by, created_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, knowledgeBaseId, userId, permission, grantedBy);
    }

    private UUID insertSession(UUID userId, String mode, boolean deleted, List<UUID> knowledgeBaseIds) {
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                -- chat_session：F11 会话范围检索测试会话，只有未删除 RAG 会话可以用于检索。
                INSERT INTO chat_session (id, user_id, title, mode, next_message_seq, deleted_at)
                VALUES (?, ?, 'F11 检索范围测试会话', ?, 1, ?)
                """, sessionId, userId, mode, deleted ? OffsetDateTime.now() : null);
        for (UUID knowledgeBaseId : knowledgeBaseIds) {
            jdbcTemplate.update("""
                    -- chat_session_kb：会话创建时固化的知识库范围，检索仍需与当前成员关系再次求交。
                    INSERT INTO chat_session_kb (session_id, kb_id, created_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    """, sessionId, knowledgeBaseId);
        }
        return sessionId;
    }

    private DocumentFixture insertDocument(UUID knowledgeBaseId, UUID createdBy, String name, String status,
                                           String embeddingProvider, String embeddingModel, boolean active,
                                           boolean deleted, float[] embedding) {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        String content = retrievalContent(name);
        String configuration = "{\"embedding\":{\"provider\":\"" + embeddingProvider + "\",\"model\":\""
                + embeddingModel + "\",\"dimensions\":" + DIMENSIONS + "}}";
        jdbcTemplate.update("""
                -- document：F11 检索测试文档，active_version_id 仅在版本插入后回填。
                INSERT INTO document (id, kb_id, name, active_version_id, pending_version_id, next_version_no,
                                      created_by, deleted_at, revision)
                VALUES (?, ?, ?, NULL, NULL, 2, ?, NULL, 0)
                """, documentId, knowledgeBaseId, name, createdBy);
        jdbcTemplate.update("""
                -- document_version：F11 检索测试版本，config_snapshot 固化嵌入提供方、模型和维度。
                INSERT INTO document_version (
                    id, document_id, version_no, storage_key, file_hash, file_size, media_type, original_filename,
                    status, parser_version, chunker_version, embedding_model, dimensions, config_hash,
                    config_snapshot, chunk_count
                ) VALUES (?, ?, 1, ?, ?, 32, 'text/markdown', 'f11.md', ?, 'plain-text-v1', 'token-budget-v1',
                          ?, 1024, ?, CAST(? AS JSONB), 1)
                """, versionId, documentId, "2026/09/" + versionId + ".md", "a".repeat(64), status,
                embeddingModel, PROCESSING_CONFIG_HASH, configuration);
        jdbcTemplate.update("""
                -- document_chunk：F11 检索测试分块，vector_store.id 与本表 id 一致。
                INSERT INTO document_chunk (
                    id, version_id, chunk_index, section, page_no, start_offset, end_offset,
                    content_hash, char_count, token_count
                ) VALUES (?, ?, 0, 'F11 章节', 1, 0, ?, ?, ?, 3)
                """, chunkId, versionId, content.length(), "c".repeat(64), content.length());
        jdbcTemplate.update("""
                -- vector_store：F11 检索测试 1024 维向量，余弦排序应在授权过滤后执行。
                INSERT INTO vector_store (id, content, metadata, embedding)
                VALUES (?, ?, CAST('{"source":"f11"}' AS JSON), CAST(? AS vector))
                """, chunkId, content, vectorLiteral(embedding));
        if (active) {
            jdbcTemplate.update("UPDATE document SET active_version_id = ? WHERE id = ?", versionId, documentId);
        }
        if (deleted) {
            jdbcTemplate.update("UPDATE document SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", documentId);
        }
        return new DocumentFixture(documentId, versionId, chunkId);
    }

    private String retrievalContent(String documentName) {
        if ("F11 当前活动版本文档".equals(documentName)) {
            return documentName + " 分块正文，错误码 PAY-CB-10010，接口 /api/v1/orders/{merchantOrderNo}/close。";
        }
        return documentName + " 分块正文，错误码 PAY-CB-1001，接口 /api/v1/orders/{merchantOrderNo}。";
    }

    private float[] vector(float first, float second) {
        float[] vector = new float[DIMENSIONS];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }

    private String vectorLiteral(float[] embedding) {
        StringBuilder literal = new StringBuilder(DIMENSIONS * 3 + 2);
        literal.append('[');
        for (int index = 0; index < embedding.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(embedding[index]);
        }
        return literal.append(']').toString();
    }

    private void deleteFixture(Fixture fixture) {
        jdbcTemplate.update("DELETE FROM chat_session_kb WHERE session_id IN (?, ?, ?, ?)", fixture.readerSessionId(),
                fixture.deletedReaderSessionId(), fixture.assistantReaderSessionId(), fixture.disabledMemberSessionId());
        jdbcTemplate.update("DELETE FROM chat_session WHERE id IN (?, ?, ?, ?)", fixture.readerSessionId(),
                fixture.deletedReaderSessionId(), fixture.assistantReaderSessionId(), fixture.disabledMemberSessionId());
        jdbcTemplate.update("DELETE FROM vector_store WHERE id IN (SELECT chunk.id FROM document_chunk chunk "
                + "INNER JOIN document_version version ON version.id = chunk.version_id "
                + "INNER JOIN document document ON document.id = version.document_id "
                + "WHERE document.kb_id IN (?, ?, ?))", fixture.activeKnowledgeBaseId(),
                fixture.inactiveKnowledgeBaseId(), fixture.outsideKnowledgeBaseId());
        jdbcTemplate.update("DELETE FROM document_chunk WHERE version_id IN (SELECT version.id FROM document_version version "
                + "INNER JOIN document document ON document.id = version.document_id "
                + "WHERE document.kb_id IN (?, ?, ?))", fixture.activeKnowledgeBaseId(),
                fixture.inactiveKnowledgeBaseId(), fixture.outsideKnowledgeBaseId());
        jdbcTemplate.update("UPDATE document SET active_version_id = NULL, pending_version_id = NULL "
                + "WHERE kb_id IN (?, ?, ?)", fixture.activeKnowledgeBaseId(), fixture.inactiveKnowledgeBaseId(),
                fixture.outsideKnowledgeBaseId());
        jdbcTemplate.update("DELETE FROM document_version WHERE document_id IN (SELECT id FROM document "
                + "WHERE kb_id IN (?, ?, ?))", fixture.activeKnowledgeBaseId(), fixture.inactiveKnowledgeBaseId(),
                fixture.outsideKnowledgeBaseId());
        jdbcTemplate.update("DELETE FROM document WHERE kb_id IN (?, ?, ?)", fixture.activeKnowledgeBaseId(),
                fixture.inactiveKnowledgeBaseId(), fixture.outsideKnowledgeBaseId());
        jdbcTemplate.update("DELETE FROM kb_member WHERE kb_id IN (?, ?, ?)", fixture.activeKnowledgeBaseId(),
                fixture.inactiveKnowledgeBaseId(), fixture.outsideKnowledgeBaseId());
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE id IN (?, ?, ?)", fixture.activeKnowledgeBaseId(),
                fixture.inactiveKnowledgeBaseId(), fixture.outsideKnowledgeBaseId());
        jdbcTemplate.update("DELETE FROM app_user WHERE id IN (?, ?, ?, ?, ?)", fixture.ownerId(), fixture.writerId(),
                fixture.readerId(), fixture.outsiderId(), fixture.disabledMemberId());
    }

    private record Fixture(UUID ownerId, UUID writerId, UUID readerId, UUID outsiderId, UUID disabledMemberId,
                           UUID activeKnowledgeBaseId, UUID inactiveKnowledgeBaseId, UUID outsideKnowledgeBaseId,
                           UUID readerSessionId, UUID deletedReaderSessionId, UUID assistantReaderSessionId,
                           UUID disabledMemberSessionId,
                           DocumentFixture authorizedDocument, DocumentFixture currentVersionDocument,
                           DocumentFixture historicalDocument) {
    }

    private record DocumentFixture(UUID documentId, UUID versionId, UUID chunkId) {
    }
}
