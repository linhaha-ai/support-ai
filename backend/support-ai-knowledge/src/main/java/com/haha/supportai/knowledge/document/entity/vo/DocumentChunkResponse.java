package com.haha.supportai.knowledge.document.entity.vo;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "DocumentChunkResponse", description = "可检查的文档分块及定位信息")
public class DocumentChunkResponse {

    @Schema(description = "分块标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID chunkId; // 分块标识

    @Schema(description = "版本内从零开始的分块序号", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int chunkIndex; // 版本内分块序号

    @Schema(description = "分块所属章节；无法识别时为空", nullable = true)
    private final String section; // 分块所属章节

    @Schema(description = "来源页码；纯文本没有页码时为空", nullable = true)
    private final Integer pageNo; // 来源页码

    @Schema(description = "在规范化原文中的起始偏移量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int startOffset; // 在规范化原文中的起始偏移量

    @Schema(description = "在规范化原文中的结束偏移量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int endOffset; // 在规范化原文中的结束偏移量

    @Schema(description = "分块字符数", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int charCount; // 分块字符数

    @Schema(description = "分块令牌数；尚未计算时为空", nullable = true)
    private final Integer tokenCount; // 分块令牌数

    @Schema(description = "分块正文；嵌入尚未落库时为空", nullable = true)
    private final String content; // 分块正文
}
