package com.haha.supportai.conversation.entity.dto;

import com.haha.supportai.knowledge.entity.enums.RerankStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "RagMetrics", description = "RAG 检索与生成阶段的非敏感执行指标")
public class RagMetrics {

    @Schema(description = "实际送入模型的证据数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int evidenceCount; // 实际送入模型的证据数量

    @Schema(description = "回答显式引用的合法来源数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int citedCount; // 回答显式引用的合法来源数量

    @Schema(description = "是否走资料不足分支", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean noEvidence; // 是否走资料不足分支

    @Schema(description = "授权检索、可选重排序与证据复核的总耗时毫秒", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long retrievalMs; // 授权检索、可选重排序与证据复核的总耗时毫秒

    @Schema(description = "重排序执行或回退状态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final RerankStatusEnum rerankStatus; // 重排序执行或回退状态

    @Schema(description = "重排序阶段接收的候选数量；关闭或无候选时为 0", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int rerankCandidateCount; // 重排序阶段接收的候选数量

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
}
