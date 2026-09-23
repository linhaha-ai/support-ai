package com.haha.supportai.knowledge.service.retrieval;

import java.util.ArrayList;
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
import com.haha.supportai.ai.config.RerankProperties;
import com.haha.supportai.ai.entity.dto.RerankDocumentScore;
import com.haha.supportai.ai.entity.dto.RerankModelResult;
import com.haha.supportai.ai.exception.RerankInputLimitException;
import com.haha.supportai.ai.exception.RerankInvalidResponseException;
import com.haha.supportai.ai.rerank.RerankModelAdapter;
import com.haha.supportai.data.knowledge.entity.po.AuthorizedRetrievalHit;
import com.haha.supportai.data.knowledge.repository.AuthorizedRetrievalRepository;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.config.RetrievalProperties;
import com.haha.supportai.knowledge.document.entity.dto.ProcessingConfigurationSnapshot;
import com.haha.supportai.knowledge.document.support.DocumentProcessingSnapshotFactory;
import com.haha.supportai.knowledge.entity.dto.KnowledgeRetrievalResult;
import com.haha.supportai.knowledge.entity.dto.KnowledgeRetrievalTestRequest;
import com.haha.supportai.knowledge.entity.enums.RerankStatusEnum;
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
    private final RerankProperties rerankProperties;
    private final RerankModelAdapter rerankModelAdapter;

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
                        snapshot.getDimensions(), snapshot.getConfigHash()),
                (keywordPatterns, candidateTopK) -> authorizedRetrievalRepository.searchWritableByKeywords(
                        userId, knowledgeBaseId, keywordPatterns, candidateTopK, embeddingProvider,
                        snapshot.getEmbeddingModel(), snapshot.getDimensions(), snapshot.getConfigHash()));
        return new KnowledgeRetrievalTestResponse(knowledgeBaseId, query, topK, strategy,
                result.vectorCandidateCount(), result.keywordCandidateCount(), result.rerankStatus(),
                result.rerankCandidateCount(), result.rerankDurationMs(), result.rerankTotalTokens(),
                elapsedMillis(startNanos), result.hits().stream().map(this::toEvidence).toList());
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
     * 后台问答或工具线程使用准备阶段确认的用户标识，避免依赖请求线程登录上下文。
     */
    public List<KnowledgeRetrievalEvidenceResponse> retrieveReadableWithinSession(UUID userId, UUID sessionId,
            String query, int topK) {
        return retrieveReadableWithinSessionDetailed(userId, sessionId, query, topK).getMatches();
    }

    public KnowledgeRetrievalResult retrieveReadableWithinSessionDetailed(UUID userId, UUID sessionId,
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
                        snapshot.getDimensions(), snapshot.getConfigHash()),
                (keywordPatterns, candidateTopK) -> authorizedRetrievalRepository
                        .searchReadableWithinSessionByKeywords(userId, sessionId, keywordPatterns, candidateTopK,
                                embeddingProvider, snapshot.getEmbeddingModel(), snapshot.getDimensions(),
                                snapshot.getConfigHash()));
        return new KnowledgeRetrievalResult(result.hits().stream().map(this::toEvidence).toList(),
                retrievalProperties.getStrategy(), result.vectorCandidateCount(), result.keywordCandidateCount(),
                result.rerankStatus(), result.rerankCandidateCount(), result.rerankDurationMs(),
                result.rerankTotalTokens());
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
                snapshot.getDimensions(), snapshot.getConfigHash());

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
        int rerankCandidateTopK = rerankCandidateTopK(topK);
        int baseRouteCandidateTopK = strategy == RetrievalStrategyEnum.FUSION
                ? fusionCandidateTopK(topK)
                : topK;
        int expandedRouteCandidateTopK = strategy == RetrievalStrategyEnum.FUSION
                ? fusionCandidateTopK(rerankCandidateTopK)
                : rerankCandidateTopK;
        List<AuthorizedRetrievalHit> vectorHits = vectorSearch.apply(expandedRouteCandidateTopK);
        List<AuthorizedRetrievalHit> rerankCandidates;
        List<AuthorizedRetrievalHit> fallbackHits;
        int keywordCandidateCount = 0;
        if (strategy == RetrievalStrategyEnum.VECTOR) {
            rerankCandidates = limit(vectorHits, rerankCandidateTopK);
            fallbackHits = limit(vectorHits, topK);
        } else {
            List<String> keywordPatterns = retrievalKeywordExtractor.extractPatterns(query,
                    retrievalProperties.getMaxKeywords());
            if (keywordPatterns.isEmpty()) {
                rerankCandidates = limit(vectorHits, rerankCandidateTopK);
                fallbackHits = limit(vectorHits, topK);
            } else {
                List<AuthorizedRetrievalHit> keywordHits = keywordSearch.apply(
                        keywordPatterns, expandedRouteCandidateTopK);
                keywordCandidateCount = keywordHits.size();
                rerankCandidates = fuseByReciprocalRank(vectorHits, keywordHits, rerankCandidateTopK);
                fallbackHits = rerankProperties.isEnabled()
                        ? fuseByReciprocalRank(limit(vectorHits, baseRouteCandidateTopK),
                                limit(keywordHits, baseRouteCandidateTopK), topK)
                        : rerankCandidates;
            }
        }
        RerankOutcome rerankOutcome = applyRerank(query, rerankCandidates, fallbackHits, topK);
        return new RetrievalResult(rerankOutcome.hits(), vectorHits.size(), keywordCandidateCount,
                rerankOutcome.status(), rerankOutcome.candidateCount(), rerankOutcome.durationMs(),
                rerankOutcome.totalTokens());
    }

    private int fusionCandidateTopK(int topK) {
        long multiplied = (long) topK * retrievalProperties.getCandidateMultiplier();
        return (int) Math.min(MAX_CANDIDATE_TOP_K, Math.max(topK, multiplied));
    }

    private int rerankCandidateTopK(int topK) {
        if (!rerankProperties.isEnabled() || topK > rerankProperties.getMaxCandidates()) {
            return topK;
        }
        long multiplied = (long) topK * rerankProperties.getCandidateMultiplier();
        return (int) Math.min(rerankProperties.getMaxCandidates(), Math.max(topK, multiplied));
    }

    private RerankOutcome applyRerank(String query, List<AuthorizedRetrievalHit> candidates,
            List<AuthorizedRetrievalHit> fallback, int topK) {
        if (!rerankProperties.isEnabled()) {
            return new RerankOutcome(fallback, RerankStatusEnum.DISABLED, 0, 0L, null);
        }
        if (candidates.isEmpty()) {
            return new RerankOutcome(List.of(), RerankStatusEnum.SKIPPED_NO_CANDIDATES, 0, 0L, null);
        }
        if (candidates.size() == 1) {
            return new RerankOutcome(fallback, RerankStatusEnum.SKIPPED_INSUFFICIENT_CANDIDATES,
                    candidates.size(), 0L, null);
        }
        if (topK > rerankProperties.getMaxCandidates()
                || candidates.size() > rerankProperties.getMaxCandidates()) {
            return new RerankOutcome(fallback, RerankStatusEnum.SKIPPED_INPUT_LIMIT,
                    candidates.size(), 0L, null);
        }

        long startedNanos = System.nanoTime();
        try {
            RerankModelResult modelResult = rerankModelAdapter.rerank(query,
                    candidates.stream().map(AuthorizedRetrievalHit::getContent).toList(),
                    Math.min(topK, candidates.size()));
            List<AuthorizedRetrievalHit> reranked = mapRerankResult(candidates, modelResult,
                    Math.min(topK, candidates.size()));
            return new RerankOutcome(reranked, RerankStatusEnum.APPLIED, candidates.size(),
                    elapsedMillis(startedNanos), modelResult.getTotalTokens());
        } catch (RerankInputLimitException exception) {
            return new RerankOutcome(fallback, RerankStatusEnum.SKIPPED_INPUT_LIMIT, candidates.size(),
                    elapsedMillis(startedNanos), null);
        } catch (RerankInvalidResponseException exception) {
            return new RerankOutcome(fallback, RerankStatusEnum.FALLBACK_INVALID_RESPONSE, candidates.size(),
                    elapsedMillis(startedNanos), null);
        } catch (RuntimeException exception) {
            return new RerankOutcome(fallback, RerankStatusEnum.FALLBACK_UNAVAILABLE, candidates.size(),
                    elapsedMillis(startedNanos), null);
        }
    }

    private List<AuthorizedRetrievalHit> mapRerankResult(List<AuthorizedRetrievalHit> candidates,
            RerankModelResult modelResult, int expectedCount) {
        if (modelResult == null || modelResult.getRankings() == null
                || modelResult.getRankings().size() != expectedCount
                || (modelResult.getTotalTokens() != null && modelResult.getTotalTokens() < 0)) {
            throw new RerankInvalidResponseException();
        }
        boolean[] seen = new boolean[candidates.size()];
        double previousScore = Double.POSITIVE_INFINITY;
        List<AuthorizedRetrievalHit> reranked = new ArrayList<>(expectedCount);
        for (RerankDocumentScore ranking : modelResult.getRankings()) {
            if (ranking == null || ranking.getIndex() < 0 || ranking.getIndex() >= candidates.size()
                    || seen[ranking.getIndex()] || !Double.isFinite(ranking.getScore())
                    || ranking.getScore() < 0D || ranking.getScore() > 1D
                    || ranking.getScore() > previousScore) {
                throw new RerankInvalidResponseException();
            }
            seen[ranking.getIndex()] = true;
            previousScore = ranking.getScore();
            reranked.add(copyWithScore(candidates.get(ranking.getIndex()), ranking.getScore()));
        }
        return List.copyOf(reranked);
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
                                   int keywordCandidateCount, RerankStatusEnum rerankStatus,
                                   int rerankCandidateCount, long rerankDurationMs, Integer rerankTotalTokens) {
    }

    private record RerankOutcome(List<AuthorizedRetrievalHit> hits, RerankStatusEnum status,
                                 int candidateCount, long durationMs, Integer totalTokens) {
    }
}
