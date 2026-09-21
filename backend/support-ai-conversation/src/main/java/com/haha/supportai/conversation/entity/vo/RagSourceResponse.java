package com.haha.supportai.conversation.entity.vo;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "RagSourceResponse", description = "流式回答实际引用且当前仍可读取的来源")
public class RagSourceResponse {

    @Schema(description = "回答中的引用编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int number; // 回答中的引用编号

    @Schema(description = "来源类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private final MessageSourceTypeEnum type; // 当前来源类型

    @Schema(description = "文档标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID documentId; // 文档标识

    @Schema(description = "文档版本标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID versionId; // 文档版本标识

    @Schema(description = "分块标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID chunkId; // 点击来源时使用的分块标识

    @Schema(description = "文档展示标题", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String title; // 文档展示标题

    @Schema(description = "来源章节", nullable = true)
    private final String section; // 来源章节

    @Schema(name = "isCurrent", description = "是否仍为文档当前版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean current; // 是否仍为文档当前版本

    @Schema(description = "按当前身份重新鉴权的来源预览路径", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String previewPath; // 点击来源时重新鉴权的预览路径

    @JsonProperty("isCurrent")
    public boolean isCurrent() {
        return current;
    }
}
