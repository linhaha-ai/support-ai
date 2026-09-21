package com.haha.supportai.knowledge.document.entity.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "DocumentSummaryResponse", description = "知识库文档列表项")
public class DocumentSummaryResponse {

    @Schema(description = "文档标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID documentId; // 文档标识

    @Schema(description = "文档展示名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String name; // 文档展示名称

    @Schema(description = "当前生效版本；尚未发布时为空", nullable = true)
    private final DocumentVersionSummaryResponse activeVersion; // 当前生效版本摘要

    @Schema(description = "当前待处理版本；没有在途版本时为空", nullable = true)
    private final DocumentVersionSummaryResponse pendingVersion; // 当前待处理版本摘要

    @Schema(description = "文档元数据修订号", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long revision; // 文档元数据修订号

    @Schema(description = "文档创建时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime createdAt; // 文档创建时间

    @Schema(description = "文档最近更新时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime updatedAt; // 文档最近更新时间
}
