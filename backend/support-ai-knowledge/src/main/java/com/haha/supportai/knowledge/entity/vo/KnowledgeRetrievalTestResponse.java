package com.haha.supportai.knowledge.entity.vo;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.knowledge.entity.enums.RerankStatusEnum;
import com.haha.supportai.knowledge.entity.enums.RetrievalStrategyEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "KnowledgeRetrievalTestResponse", description = "知识库授权检索调试结果")
public class KnowledgeRetrievalTestResponse {

    @Schema(description = "本次检索的知识库标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID knowledgeBaseId; // 知识库标识

    @Schema(description = "规范化后的检索问题", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String query; // 检索问题

    @Schema(description = "请求的最大返回数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int topK; // 返回数量上限

    @Schema(description = "本次实际使用的检索策略", requiredMode = Schema.RequiredMode.REQUIRED)
    private final RetrievalStrategyEnum strategy; // 实际检索策略

    @Schema(description = "融合前的向量候选数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int vectorCandidateCount; // 向量候选数量

    @Schema(description = "融合前的关键词候选数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int keywordCandidateCount; // 关键词候选数量

    @Schema(description = "重排序执行状态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final RerankStatusEnum rerankStatus; // 重排序执行或回退状态

    @Schema(description = "重排序阶段接收的候选数量；关闭或无候选时为 0", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int rerankCandidateCount; // 重排序阶段接收的候选数量

    @Schema(description = "重排序尝试耗时，单位毫秒", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long rerankDurationMs; // 重排序尝试耗时毫秒

    @Schema(description = "重排序模型返回的 Token 总量；未调用或提供方未返回时为空", nullable = true)
    private final Integer rerankTotalTokens; // 重排序模型 Token 总量

    @Schema(description = "服务端检索耗时，单位毫秒", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long durationMs; // 检索耗时毫秒数

    @Schema(description = "仅包含当前成员可读且已发布的文档证据", requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<KnowledgeRetrievalEvidenceResponse> matches; // 检索证据列表
}
