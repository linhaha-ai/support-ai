package com.haha.supportai.conversation.entity.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.haha.supportai.knowledge.entity.enums.RerankStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(name = "RagMetrics", description = "RAG 检索、生成指标与受权限保护的改写诊断")
public class RagMetrics {

    @Schema(description = "实际送入模型的证据数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int evidenceCount; // 实际送入模型的证据数量

    @Schema(description = "二次授权复核后、分数与预算筛选前的证据数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int retrievedEvidenceCount; // 二次授权复核后的证据数量

    @Schema(description = "因最终评分低于当前策略阈值而移除的证据数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int scoreFilteredEvidenceCount; // 分数阈值移除的证据数量

    @Schema(description = "因证据正文 Token 预算而移除的证据数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int budgetFilteredEvidenceCount; // Token 预算移除的证据数量

    @Schema(description = "实际送入模型的证据正文估算 Token 总量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int evidenceTokenCount; // 入模证据正文估算 Token 总量

    @Schema(description = "回答显式引用的合法来源数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int citedCount; // 回答显式引用的合法来源数量

    @Schema(description = "是否走资料不足分支", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean noEvidence; // 是否走资料不足分支

    @Schema(description = "授权检索、可选重排序与证据复核的总耗时毫秒", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long retrievalMs; // 授权检索、可选重排序与证据复核的总耗时毫秒

    @Schema(description = "查询向量模型返回的 Token 总量；提供方未提供时为空", nullable = true)
    private final Integer embeddingTotalTokens; // 查询向量模型 Token 总量，当前提供方未知

    @Schema(description = "重排序执行或回退状态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final RerankStatusEnum rerankStatus; // 重排序执行或回退状态

    @Schema(description = "重排序阶段接收的候选数量；关闭或无候选时为 0", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int rerankCandidateCount; // 重排序阶段接收的候选数量，关闭或无候选时为 0

    @Schema(description = "重排序尝试耗时毫秒", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long rerankMs; // 重排序尝试耗时毫秒

    @Schema(description = "重排序模型返回的 Token 总量；未知或未调用时为空", nullable = true)
    private final Integer rerankTotalTokens; // 重排序模型 Token 总量

    @Schema(description = "模型流式生成耗时毫秒；未调用模型时为空", nullable = true)
    private final Long generationMs; // 模型流式生成耗时毫秒，未调用模型时为空

    @Schema(description = "从开始执行到终态提交前的总耗时毫秒", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long totalMs; // 从开始执行到终态提交前的总耗时毫秒

    @Schema(description = "从开始执行到首个持久化增量的耗时毫秒", nullable = true)
    private final Long firstDeltaMs; // 从开始执行到首个持久化增量的耗时毫秒

    @Schema(description = "追问检索问题改写诊断，可能因权限不可读而隐藏", nullable = true)
    private final RetrievalQueryDiagnostics queryRewrite; // 追问检索问题改写诊断

    public RagMetrics(int evidenceCount, int citedCount, boolean noEvidence, long totalMs, Long firstDeltaMs) {
        this(evidenceCount, citedCount, noEvidence, totalMs, firstDeltaMs, null);
    }

    public RagMetrics(int evidenceCount, int citedCount, boolean noEvidence, long totalMs, Long firstDeltaMs,
            RetrievalQueryDiagnostics queryRewrite) {
        this(evidenceCount, evidenceCount, 0, 0, 0, citedCount, noEvidence, 0L, null,
                RerankStatusEnum.NOT_REACHED, 0, 0L, null, null, totalMs, firstDeltaMs, queryRewrite);
    }

    @JsonCreator
    public RagMetrics(@JsonProperty("evidenceCount") int evidenceCount,
            @JsonProperty("retrievedEvidenceCount") int retrievedEvidenceCount,
            @JsonProperty("scoreFilteredEvidenceCount") int scoreFilteredEvidenceCount,
            @JsonProperty("budgetFilteredEvidenceCount") int budgetFilteredEvidenceCount,
            @JsonProperty("evidenceTokenCount") int evidenceTokenCount,
            @JsonProperty("citedCount") int citedCount,
            @JsonProperty("noEvidence") boolean noEvidence,
            @JsonProperty("retrievalMs") long retrievalMs,
            @JsonProperty("embeddingTotalTokens") Integer embeddingTotalTokens,
            @JsonProperty("rerankStatus") RerankStatusEnum rerankStatus,
            @JsonProperty("rerankCandidateCount") int rerankCandidateCount,
            @JsonProperty("rerankMs") long rerankMs,
            @JsonProperty("rerankTotalTokens") Integer rerankTotalTokens,
            @JsonProperty("generationMs") Long generationMs,
            @JsonProperty("totalMs") long totalMs,
            @JsonProperty("firstDeltaMs") Long firstDeltaMs,
            @JsonProperty("queryRewrite") RetrievalQueryDiagnostics queryRewrite) {
        this.evidenceCount = evidenceCount;
        this.retrievedEvidenceCount = retrievedEvidenceCount;
        this.scoreFilteredEvidenceCount = scoreFilteredEvidenceCount;
        this.budgetFilteredEvidenceCount = budgetFilteredEvidenceCount;
        this.evidenceTokenCount = evidenceTokenCount;
        this.citedCount = citedCount;
        this.noEvidence = noEvidence;
        this.retrievalMs = retrievalMs;
        this.embeddingTotalTokens = embeddingTotalTokens;
        this.rerankStatus = rerankStatus == null ? RerankStatusEnum.NOT_REACHED : rerankStatus;
        this.rerankCandidateCount = rerankCandidateCount;
        this.rerankMs = rerankMs;
        this.rerankTotalTokens = rerankTotalTokens;
        this.generationMs = generationMs;
        this.totalMs = totalMs;
        this.firstDeltaMs = firstDeltaMs;
        this.queryRewrite = queryRewrite;
    }

    /**
     * 当前用户无权读取会话内容时，返回不包含原问题和改写问题的诊断副本。
     */
    public RagMetrics withoutQueryRewriteText() {
        return new RagMetrics(evidenceCount, retrievedEvidenceCount, scoreFilteredEvidenceCount,
                budgetFilteredEvidenceCount, evidenceTokenCount, citedCount, noEvidence, retrievalMs,
                embeddingTotalTokens, rerankStatus, rerankCandidateCount, rerankMs, rerankTotalTokens,
                generationMs, totalMs, firstDeltaMs,
                queryRewrite == null ? null : new RetrievalQueryDiagnostics(null, null, queryRewrite.getStatus()));
    }
}
