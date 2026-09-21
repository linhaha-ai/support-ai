package com.haha.supportai.knowledge.entity.vo;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "DocumentSourceResponse", description = "当前用户可读取的文档来源正文与定位信息")
public class DocumentSourceResponse {

    @Schema(description = "知识库标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID knowledgeBaseId; // 知识库标识

    @Schema(description = "文档标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID documentId; // 文档标识

    @Schema(description = "文档展示名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String documentName; // 文档展示名称

    @Schema(description = "文档版本标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID versionId; // 文档版本标识

    @Schema(description = "文档版本号", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int versionNo; // 文档版本号

    @Schema(description = "分块标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID chunkId; // 分块标识

    @Schema(description = "分块序号，从 0 开始", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int chunkIndex; // 分块序号

    @Schema(description = "来源章节；没有章节时为空", nullable = true)
    private final String section; // 来源章节

    @Schema(description = "来源页码；纯文本或无页码来源时为空", nullable = true)
    private final Integer pageNo; // 来源页码

    @Schema(description = "分块起始字符偏移量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int startOffset; // 分块起始字符偏移量

    @Schema(description = "分块结束字符偏移量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int endOffset; // 分块结束字符偏移量

    @Schema(name = "isCurrent", description = "是否仍为文档当前生效版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean current; // 是否为当前生效版本

    @Schema(description = "来源分块正文", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String content; // 来源分块正文

    @JsonProperty("isCurrent")
    public boolean isCurrent() {
        return current;
    }
}
