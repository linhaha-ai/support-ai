package com.haha.supportai.knowledge.document.entity.vo;

import java.util.UUID;

import com.haha.supportai.data.knowledge.enums.IngestionJobStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "DocumentVersionUploadResponse", description = "文档版本更新受理结果")
public class DocumentVersionUploadResponse {

    @Schema(description = "逻辑文档标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID documentId; // 逻辑文档标识

    @Schema(description = "本次使用的文档版本标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID versionId; // 新建或复用的文档版本标识

    @Schema(description = "版本对应的持久导入任务标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID jobId; // 新建或复用版本对应的任务标识

    @Schema(description = "任务当前状态", example = "QUEUED", requiredMode = Schema.RequiredMode.REQUIRED)
    private final IngestionJobStatusEnum status; // 任务当前状态

    @Schema(description = "操作完成后的文档修订号", example = "2",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final long revision; // 操作完成后的文档修订号

    @Schema(description = "是否直接复用了当前已发布版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean reused; // 是否未创建新版本和新任务
}
