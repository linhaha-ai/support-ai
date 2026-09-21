package com.haha.supportai.conversation.entity.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(name = "RagMetrics", description = "RAG 检索、生成指标与受权限保护的改写诊断")
public class RagMetrics {

    @Schema(description = "实际送入模型的证据数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int evidenceCount; // 实际送入模型的证据数量

    @Schema(description = "回答显式引用的合法来源数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int citedCount; // 回答显式引用的合法来源数量

    @Schema(description = "是否走资料不足分支", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean noEvidence; // 是否走资料不足分支

    @Schema(description = "从开始执行到终态提交前的总耗时毫秒", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long totalMs; // 从开始执行到终态提交前的总耗时毫秒

    @Schema(description = "从开始执行到首个持久化增量的耗时毫秒", nullable = true)
    private final Long firstDeltaMs; // 从开始执行到首个持久化增量的耗时毫秒

    @Schema(description = "追问检索问题改写诊断，可能因权限不可读而隐藏", nullable = true)
    private final RetrievalQueryDiagnostics queryRewrite; // 追问检索问题改写诊断

    public RagMetrics(int evidenceCount, int citedCount, boolean noEvidence, long totalMs, Long firstDeltaMs) {
        this(evidenceCount, citedCount, noEvidence, totalMs, firstDeltaMs, null);
    }

    @JsonCreator
    public RagMetrics(@JsonProperty("evidenceCount") int evidenceCount,
            @JsonProperty("citedCount") int citedCount,
            @JsonProperty("noEvidence") boolean noEvidence,
            @JsonProperty("totalMs") long totalMs,
            @JsonProperty("firstDeltaMs") Long firstDeltaMs,
            @JsonProperty("queryRewrite") RetrievalQueryDiagnostics queryRewrite) {
        this.evidenceCount = evidenceCount;
        this.citedCount = citedCount;
        this.noEvidence = noEvidence;
        this.totalMs = totalMs;
        this.firstDeltaMs = firstDeltaMs;
        this.queryRewrite = queryRewrite;
    }

    /**
     * 当前用户无权读取会话内容时，返回不包含原问题和改写问题的诊断副本。
     */
    public RagMetrics withoutQueryRewriteText() {
        return new RagMetrics(evidenceCount, citedCount, noEvidence, totalMs, firstDeltaMs,
                queryRewrite == null ? null : new RetrievalQueryDiagnostics(null, null, queryRewrite.getStatus()));
    }
}
