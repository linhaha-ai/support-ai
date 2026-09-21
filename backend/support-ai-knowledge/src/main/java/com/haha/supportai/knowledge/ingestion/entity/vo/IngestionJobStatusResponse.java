package com.haha.supportai.knowledge.ingestion.entity.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.haha.supportai.data.knowledge.enums.IngestionJobStatusEnum;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "IngestionJobStatusResponse", description = "当前用户可查看的文档入库任务状态")
public class IngestionJobStatusResponse {

    @Schema(description = "入库任务标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID jobId; // 入库任务标识

    @Schema(description = "待处理文档版本标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID versionId; // 待处理文档版本标识

    @Schema(description = "任务状态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final IngestionJobStatusEnum status; // 任务状态

    @Schema(description = "当前处理阶段", requiredMode = Schema.RequiredMode.REQUIRED)
    private final IngestionStageEnum stage; // 当前处理阶段

    @Schema(description = "当前阶段已领取次数", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int attempt; // 当前阶段已领取次数

    @Schema(description = "当前阶段最大领取次数", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int maxAttempts; // 当前阶段最大领取次数

    @Schema(description = "已完成分块数量；尚未确定时为空", nullable = true)
    private final Integer completedChunks; // 已完成分块数量

    @Schema(description = "已知分块总数；尚未确定时为空", nullable = true)
    private final Integer totalChunks; // 已知分块总数

    @Schema(description = "整数进度百分比；总量未知时为空", minimum = "0", maximum = "100", nullable = true)
    private final Integer progressPercent; // 整数进度百分比

    @Schema(description = "是否具备后续人工重试条件", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean retryable; // 是否具备人工重试条件

    @Schema(description = "是否处于可请求取消的非终态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean cancellable; // 是否处于可请求取消的状态

    @Schema(description = "脱敏错误分类码", nullable = true)
    private final String errorCode; // 脱敏错误分类码

    @Schema(description = "面向用户的脱敏错误提示", nullable = true)
    private final String errorMessage; // 面向用户的脱敏错误提示

    @Schema(description = "下次允许领取时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime nextRunAt; // 下次允许领取时间

    @Schema(description = "首次开始处理时间", format = "date-time", nullable = true)
    private final OffsetDateTime startedAt; // 首次开始处理时间

    @Schema(description = "最终结束时间", format = "date-time", nullable = true)
    private final OffsetDateTime finishedAt; // 最终结束时间

    @Schema(description = "任务最近更新时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime updatedAt; // 任务最近更新时间
}
