package com.haha.supportai.knowledge.service.retrieval;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.data.knowledge.entity.po.AuthorizedRetrievalHit;
import com.haha.supportai.data.knowledge.repository.AuthorizedRetrievalRepository;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.document.entity.dto.ProcessingConfigurationSnapshot;
import com.haha.supportai.knowledge.document.support.DocumentProcessingSnapshotFactory;
import com.haha.supportai.knowledge.entity.dto.KnowledgeRetrievalTestRequest;
import com.haha.supportai.knowledge.entity.vo.KnowledgeRetrievalEvidenceResponse;
import com.haha.supportai.knowledge.entity.vo.KnowledgeRetrievalTestResponse;
import com.haha.supportai.knowledge.exception.KnowledgeRetrievalInvalidRequestException;
import com.haha.supportai.knowledge.exception.KnowledgeRetrievalUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 50;
    private static final int MAX_QUERY_LENGTH = 2000;

    private final EmbeddingModel embeddingModel;
    private final AuthorizedRetrievalRepository authorizedRetrievalRepository;
    private final AccessPolicy accessPolicy;
    private final DocumentProcessingSnapshotFactory documentProcessingSnapshotFactory;
    private final ObjectMapper objectMapper;

    public KnowledgeRetrievalTestResponse testWritableKnowledgeBase(UUID knowledgeBaseId,
            KnowledgeRetrievalTestRequest request) {
        String query = normalizeQuery(request == null ? null : request.getQuery());
        int topK = validateTopK(request == null ? null : request.getTopK());
        long startNanos = System.nanoTime();

        // 模型调用前先完成 WRITE 校验，避免无权限请求消耗模型资源。
        UUID userId = accessPolicy.requireWrite(knowledgeBaseId).getMember().getUserId();
        ProcessingConfigurationSnapshot snapshot = currentSnapshot();
        float[] queryEmbedding = createQueryEmbedding(query);
        List<AuthorizedRetrievalHit> hits = authorizedRetrievalRepository.searchWritable(userId, knowledgeBaseId,
                queryEmbedding, topK, embeddingProvider(snapshot), snapshot.getEmbeddingModel(), snapshot.getDimensions());
        return new KnowledgeRetrievalTestResponse(knowledgeBaseId, query, topK, elapsedMillis(startNanos),
                hits.stream().map(this::toEvidence).toList());
    }

    /**
     * 会话范围只由服务端保存的会话标识确定，仓储层同时校验当前成员关系，调用方不能扩展检索范围。
     */
    public List<KnowledgeRetrievalEvidenceResponse> retrieveReadableWithinSession(UUID sessionId, String query,
            int topK) {
        if (sessionId == null) {
            throw new KnowledgeRetrievalInvalidRequestException();
        }
        String normalizedQuery = normalizeQuery(query);
        int validatedTopK = validateTopK(topK);
        UUID userId = accessPolicy.currentUser().getId();
        ProcessingConfigurationSnapshot snapshot = currentSnapshot();
        float[] queryEmbedding = createQueryEmbedding(normalizedQuery);
        return authorizedRetrievalRepository.searchReadableWithinSession(userId, sessionId, queryEmbedding, validatedTopK,
                        embeddingProvider(snapshot), snapshot.getEmbeddingModel(), snapshot.getDimensions())
                .stream()
                .map(this::toEvidence)
                .toList();
    }

    private float[] createQueryEmbedding(String query) {
        try {
            // ValidatedEmbeddingModel 在模型边界固定 query 用途并验证 1024 维有限数值。
            return embeddingModel.embed(query);
        } catch (RuntimeException exception) {
            throw new KnowledgeRetrievalUnavailableException(exception);
        }
    }

    private ProcessingConfigurationSnapshot currentSnapshot() {
        try {
            return documentProcessingSnapshotFactory.create();
        } catch (RuntimeException exception) {
            throw new KnowledgeRetrievalUnavailableException(exception);
        }
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            throw new KnowledgeRetrievalInvalidRequestException();
        }
        String normalized = query.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_QUERY_LENGTH) {
            throw new KnowledgeRetrievalInvalidRequestException();
        }
        return normalized;
    }

    private int validateTopK(Integer topK) {
        if (topK == null || topK < MIN_TOP_K || topK > MAX_TOP_K) {
            throw new KnowledgeRetrievalInvalidRequestException();
        }
        return topK;
    }

    private String embeddingProvider(ProcessingConfigurationSnapshot snapshot) {
        try {
            String provider = objectMapper.readTree(snapshot.getConfigSnapshot())
                    .path("embedding")
                    .path("provider")
                    .asText();
            if (!provider.isBlank()) {
                return provider;
            }
            throw new IllegalStateException("文档处理配置快照中的向量提供方无效");
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new KnowledgeRetrievalUnavailableException(
                    new IllegalStateException("文档处理配置快照中的向量提供方无效", exception));
        }
    }

    private long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private KnowledgeRetrievalEvidenceResponse toEvidence(AuthorizedRetrievalHit hit) {
        return new KnowledgeRetrievalEvidenceResponse(hit.getKnowledgeBaseId(), hit.getDocumentId(),
                hit.getDocumentName(), hit.getVersionId(), hit.getVersionNo(), hit.getChunkId(), hit.getChunkIndex(),
                hit.getSection(), hit.getPageNo(), hit.getStartOffset(), hit.getEndOffset(), hit.getContent(),
                hit.getScore());
    }
}
