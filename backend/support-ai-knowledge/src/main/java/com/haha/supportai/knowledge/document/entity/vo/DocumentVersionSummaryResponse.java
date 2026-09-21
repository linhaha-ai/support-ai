package com.haha.supportai.knowledge.document.entity.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.haha.supportai.data.knowledge.enums.DocumentVersionStatusEnum;
import com.haha.supportai.knowledge.ingestion.entity.vo.IngestionJobStatusResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "DocumentVersionSummaryResponse", description = "文档版本及其安全处理摘要")
public class DocumentVersionSummaryResponse {

    @Schema(description = "文档版本标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID versionId; // 文档版本标识

    @Schema(description = "文档内递增版本号", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int versionNo; // 文档内递增版本号

    @Schema(description = "版本处理状态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final DocumentVersionStatusEnum status; // 版本处理状态

    @Schema(description = "是否为当前生效版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean active; // 是否为当前生效版本

    @Schema(description = "是否为当前待处理版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean pending; // 是否为当前待处理版本

    @Schema(description = "上传时的原始文件名", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String originalFilename; // 上传时的原始文件名

    @Schema(description = "原文件字节数", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long fileSize; // 原文件字节数

    @Schema(description = "已校验的媒体类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String mediaType; // 已校验的媒体类型

    @Schema(description = "已持久化分块数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int chunkCount; // 已持久化分块数量

    @Schema(description = "关联入库任务摘要；历史数据没有任务时为空", nullable = true)
    private final IngestionJobStatusResponse task; // 关联入库任务的安全摘要

    @Schema(description = "版本创建时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime createdAt; // 版本创建时间

    @Schema(description = "版本最近更新时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime updatedAt; // 版本最近更新时间
}
