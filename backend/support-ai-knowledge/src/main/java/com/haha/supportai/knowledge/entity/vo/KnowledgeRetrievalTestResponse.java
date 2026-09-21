package com.haha.supportai.knowledge.entity.vo;

import java.util.List;
import java.util.UUID;

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

    @Schema(description = "服务端检索耗时，单位毫秒", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long durationMs; // 检索耗时毫秒数

    @Schema(description = "仅包含当前成员可读且已发布的文档证据", requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<KnowledgeRetrievalEvidenceResponse> matches; // 检索证据列表
}
