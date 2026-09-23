package com.haha.supportai.knowledge.service.retrieval;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.data.knowledge.entity.po.AuthorizedRetrievalHit;
import com.haha.supportai.data.knowledge.repository.AuthorizedRetrievalRepository;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.config.RetrievalProperties;
import com.haha.supportai.knowledge.document.entity.dto.ProcessingConfigurationSnapshot;
import com.haha.supportai.knowledge.document.support.DocumentProcessingSnapshotFactory;
import com.haha.supportai.knowledge.entity.dto.KnowledgeRetrievalTestRequest;
import com.haha.supportai.knowledge.entity.enums.RetrievalStrategyEnum;
import com.haha.supportai.knowledge.entity.vo.KnowledgeRetrievalEvidenceResponse;
import com.haha.supportai.knowledge.entity.vo.KnowledgeRetrievalTestResponse;
import com.haha.supportai.knowledge.exception.KnowledgeRetrievalInvalidRequestException;
import com.haha.supportai.knowledge.exception.KnowledgeRetrievalUnavailableException;
import com.haha.supportai.knowledge.support.RetrievalKeywordExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 50;
    private static final int MAX_CANDIDATE_TOP_K = 250;
    private static final int MAX_QUERY_LENGTH = 2000;

    private final EmbeddingModel embeddingModel;
    private final AuthorizedRetrievalRepository authorizedRetrievalRepository;
    private final AccessPolicy accessPolicy;
    private final DocumentProcessingSnapshotFactory documentProcessingSnapshotFactory;
    private final ObjectMapper objectMapper;
    private final RetrievalProperties retrievalProperties;
    private final RetrievalKeywordExtractor retrievalKeywordExtractor;

    public KnowledgeRetrievalTestResponse testWritableKnowledgeBase(UUID knowledgeBaseId,
            KnowledgeRetrievalTestRequest request) {
        String query = normalizeQuery(request == null ? null : request.getQuery());
        int topK = validateTopK(request == null ? null : request.getTopK());
        RetrievalStrategyEnum strategy = request != null && request.getStrategy() != null
                ? request.getStrategy()
                : retrievalProperties.getStrategy();
        long startNanos = System.nanoTime();

        // 模型调用前先完成 WRITE 校验，避免无权限请求消耗模型资源。
        UUID userId = accessPolicy.requireWrite(knowledgeBaseId).getMember().getUserId();
        ProcessingConfigurationSnapshot snapshot = currentSnapshot();
        String embeddingProvider = embeddingProvider(snapshot);
        float[] queryEmbedding = createQueryEmbedding(query);
        RetrievalResult result = retrieve(query, topK, strategy,
                candidateTopK -> authorizedRetrievalRepository.searchWritable(userId, knowledgeBaseId,
                        queryEmbedding, candidateTopK, embeddingProvider, snapshot.getEmbeddingModel(),
                        snapshot.getDimensions()),
                (keywordPatterns, candidateTopK) -> authorizedRetrievalRepository.searchWritableByKeywords(
                        userId, knowledgeBaseId, keywordPatterns, candidateTopK, embeddingProvider,
                        snapshot.getEmbeddingModel(), snapshot.getDimensions()));
        return new KnowledgeRetrievalTestResponse(knowledgeBaseId, query, topK, strategy,
                result.vectorCandidateCount(), result.keywordCandidateCount(), elapsedMillis(startNanos),
                result.hits().stream().map(this::toEvidence).toList());
    }

    /**
     * 会话范围只由服务端保存的会话标识确定，仓储层同时校验当前成员关系，调用方不能扩展检索范围。
     */
    public List<KnowledgeRetrievalEvidenceResponse> retrieveReadableWithinSession(UUID sessionId, String query,
            int topK) {
        if (sessionId == null) {
            throw new KnowledgeRetrievalInvalidRequestException();
        }
        UUID userId = accessPolicy.currentUser().getId();
        return retrieveReadableWithinSession(userId, sessionId, query, topK);
    }

    /**
     * 后台问答线程使用准备阶段确认的用户标识，避免依赖请求线程登录上下文。
     */
    public List<KnowledgeRetrievalEvidenceResponse> retrieveReadableWithinSession(UUID userId, UUID sessionId,
            String query, int topK) {
        if (userId == null || sessionId == null) {
            throw new KnowledgeRetrievalInvalidRequestException();
        }
        String normalizedQuery = normalizeQuery(query);
        int validatedTopK = validateTopK(topK);
        ProcessingConfigurationSnapshot snapshot = currentSnapshot();
        String embeddingProvider = embeddingProvider(snapshot);
        float[] queryEmbedding = createQueryEmbedding(normalizedQuery);
        RetrievalResult result = retrieve(normalizedQuery, validatedTopK, retrievalProperties.getStrategy(),
                candidateTopK -> authorizedRetrievalRepository.searchReadableWithinSession(userId, sessionId,
                        queryEmbedding, candidateTopK, embeddingProvider, snapshot.getEmbeddingModel(),
                        snapshot.getDimensions()),
                (keywordPatterns, candidateTopK) -> authorizedRetrievalRepository
                        .searchReadableWithinSessionByKeywords(userId, sessionId, keywordPatterns, candidateTopK,
                                embeddingProvider, snapshot.getEmbeddingModel(), snapshot.getDimensions()));
        return result.hits()
                .stream()
                .map(this::toEvidence)
                .toList();
    }

    /**
     * 模型调用前对候选证据重新执行会话范围、成员权限、有效版本和模型空间校验。
     */
    public List<KnowledgeRetrievalEvidenceResponse> revalidateReadableWithinSession(UUID sessionId,
            List<UUID> chunkIds) {
        validateRevalidationRequest(sessionId, chunkIds);
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        UUID userId = accessPolicy.currentUser().getId();
        return revalidateReadableWithinSession(userId, sessionId, chunkIds);
    }

    /**
     * 后台问答线程以服务端可信用户重新校验证据，不接受客户端提供的权限范围。
     */
    public List<KnowledgeRetrievalEvidenceResponse> revalidateReadableWithinSession(UUID userId, UUID sessionId,
            List<UUID> chunkIds) {
        if (userId == null) {
            throw new KnowledgeRetrievalInvalidRequestException();
        }
        validateRevalidationRequest(sessionId, chunkIds);
        if (chunkIds.isEmpty()) {
            return List.of();
        }

        List<UUID> orderedChunkIds = List.copyOf(new LinkedHashSet<>(chunkIds));
        ProcessingConfigurationSnapshot snapshot = currentSnapshot();
        List<AuthorizedRetrievalHit> readableHits = authorizedRetrievalRepository.revalidateReadableWithinSession(
                userId, sessionId, orderedChunkIds, embeddingProvider(snapshot), snapshot.getEmbeddingModel(),
                snapshot.getDimensions());

        Map<UUID, AuthorizedRetrievalHit> hitByChunkId = new HashMap<>();
        readableHits.forEach(hit -> hitByChunkId.putIfAbsent(hit.getChunkId(), hit));
        return orderedChunkIds.stream()
                .map(hitByChunkId::get)
                .filter(Objects::nonNull)
                .map(this::toEvidence)
                .toList();
    }

    private void validateRevalidationRequest(UUID sessionId, List<UUID> chunkIds) {
        if (sessionId == null || chunkIds == null || chunkIds.stream().anyMatch(Objects::isNull)) {
            throw new KnowledgeRetrievalInvalidRequestException();
        }
    }

    private RetrievalResult retrieve(String query, int topK, RetrievalStrategyEnum strategy,
            IntFunction<List<AuthorizedRetrievalHit>> vectorSearch,
            BiFunction<List<String>, Integer, List<AuthorizedRetrievalHit>> keywordSearch) {
        if (strategy == null) {
            throw new KnowledgeRetrievalUnavailableException(
                    new IllegalStateException("检索策略配置不能为空"));
        }
        int candidateTopK = strategy == RetrievalStrategyEnum.FUSION ? candidateTopK(topK) : topK;
        List<AuthorizedRetrievalHit> vectorHits = vectorSearch.apply(candidateTopK);
        if (strategy == RetrievalStrategyEnum.VECTOR) {
            return new RetrievalResult(vectorHits, vectorHits.size(), 0);
        }

        List<String> keywordPatterns = retrievalKeywordExtractor.extractPatterns(query,
                retrievalProperties.getMaxKeywords());
        if (keywordPatterns.isEmpty()) {
            return new RetrievalResult(limit(vectorHits, topK), vectorHits.size(), 0);
        }
        List<AuthorizedRetrievalHit> keywordHits = keywordSearch.apply(keywordPatterns, candidateTopK);
        return new RetrievalResult(fuseByReciprocalRank(vectorHits, keywordHits, topK), vectorHits.size(),
                keywordHits.size());
    }

    private int candidateTopK(int topK) {
        long multiplied = (long) topK * retrievalProperties.getCandidateMultiplier();
        return (int) Math.min(MAX_CANDIDATE_TOP_K, Math.max(topK, multiplied));
    }

    private List<AuthorizedRetrievalHit> fuseByReciprocalRank(List<AuthorizedRetrievalHit> vectorHits,
            List<AuthorizedRetrievalHit> keywordHits, int topK) {
        Map<UUID, AuthorizedRetrievalHit> hitByChunkId = new LinkedHashMap<>();
        Map<UUID, Double> scoreByChunkId = new HashMap<>();
        Map<UUID, Integer> bestRankByChunkId = new HashMap<>();
        addReciprocalRanks(vectorHits, hitByChunkId, scoreByChunkId, bestRankByChunkId);
        addReciprocalRanks(keywordHits, hitByChunkId, scoreByChunkId, bestRankByChunkId);
        return hitByChunkId.keySet().stream()
                .sorted((left, right) -> compareFused(left, right, scoreByChunkId, bestRankByChunkId))
                .limit(topK)
                .map(chunkId -> copyWithScore(hitByChunkId.get(chunkId), scoreByChunkId.get(chunkId)))
                .toList();
    }

    private void addReciprocalRanks(List<AuthorizedRetrievalHit> hits,
            Map<UUID, AuthorizedRetrievalHit> hitByChunkId, Map<UUID, Double> scoreByChunkId,
            Map<UUID, Integer> bestRankByChunkId) {
        Set<UUID> rankedChunkIds = new HashSet<>();
        for (int index = 0; index < hits.size(); index++) {
            AuthorizedRetrievalHit hit = hits.get(index);
            if (hit.getChunkId() == null || !rankedChunkIds.add(hit.getChunkId())) {
                continue;
            }
            int rank = index + 1;
            hitByChunkId.putIfAbsent(hit.getChunkId(), hit);
            scoreByChunkId.merge(hit.getChunkId(), 1.0D / (retrievalProperties.getRrfK() + rank), Double::sum);
            bestRankByChunkId.merge(hit.getChunkId(), rank, Math::min);
        }
    }

    private int compareFused(UUID left, UUID right, Map<UUID, Double> scores, Map<UUID, Integer> bestRanks) {
        int scoreComparison = Double.compare(scores.get(right), scores.get(left));
        if (scoreComparison != 0) {
            return scoreComparison;
        }
        int rankComparison = Integer.compare(bestRanks.get(left), bestRanks.get(right));
        return rankComparison != 0 ? rankComparison : left.compareTo(right);
    }

    private List<AuthorizedRetrievalHit> limit(List<AuthorizedRetrievalHit> hits, int topK) {
        return hits.stream().limit(topK).toList();
    }

    private AuthorizedRetrievalHit copyWithScore(AuthorizedRetrievalHit hit, double score) {
        return new AuthorizedRetrievalHit(hit.getKnowledgeBaseId(), hit.getDocumentId(), hit.getDocumentName(),
                hit.getVersionId(), hit.getVersionNo(), hit.getChunkId(), hit.getChunkIndex(), hit.getSection(),
                hit.getPageNo(), hit.getStartOffset(), hit.getEndOffset(), hit.getContent(), score);
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

    private record RetrievalResult(List<AuthorizedRetrievalHit> hits, int vectorCandidateCount,
                                   int keywordCandidateCount) {
    }
}
