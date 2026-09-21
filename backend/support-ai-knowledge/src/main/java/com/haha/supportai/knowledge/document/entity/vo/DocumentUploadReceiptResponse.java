package com.haha.supportai.knowledge.document.entity.vo;

import java.util.UUID;

import com.haha.supportai.data.knowledge.enums.IngestionJobStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "DocumentUploadReceiptResponse", description = "文档上传受理回执")
public class DocumentUploadReceiptResponse {

    @Schema(description = "逻辑文档标识", example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID documentId; // 逻辑文档标识

    @Schema(description = "待处理版本标识", example = "550e8400-e29b-41d4-a716-446655440001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID versionId; // 待处理版本标识

    @Schema(description = "持久导入任务标识", example = "550e8400-e29b-41d4-a716-446655440002",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID jobId; // 持久导入任务标识

    @Schema(description = "当前处理状态；QUEUED 仅表示已接收，尚不可用于问答", example = "QUEUED",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final IngestionJobStatusEnum status; // 当前处理状态
}
