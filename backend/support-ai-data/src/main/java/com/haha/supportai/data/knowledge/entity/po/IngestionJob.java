package com.haha.supportai.data.knowledge.entity.po;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.haha.supportai.data.knowledge.enums.IngestionJobStatusEnum;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.EnumTypeHandler;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ingestion_job", autoResultMap = true)
public class IngestionJob {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id; // 入库任务标识，由业务层生成 UUID

    @TableField("version_id")
    private UUID versionId; // 待处理文档版本标识

    @TableField(value = "status", typeHandler = EnumTypeHandler.class)
    private IngestionJobStatusEnum status; // 入库任务状态

    @TableField(value = "stage", typeHandler = EnumTypeHandler.class)
    private IngestionStageEnum stage; // 当前入库处理阶段

    @TableField("attempt")
    private int attempt; // 已发起处理的次数

    @TableField("max_attempts")
    private int maxAttempts; // 最大处理次数

    @TableField("lease_owner")
    private String leaseOwner; // 当前任务租约持有者标识

    @TableField("lease_token")
    private UUID leaseToken; // 当前任务租约令牌

    @TableField("lease_until")
    private OffsetDateTime leaseUntil; // 当前任务租约失效时间

    @TableField("heartbeat_at")
    private OffsetDateTime heartbeatAt; // 当前任务工作者最近心跳时间

    @TableField("next_run_at")
    private OffsetDateTime nextRunAt; // 下次可领取执行时间

    @TableField("completed_chunks")
    private Integer completedChunks; // 已完成处理的文本分块数

    @TableField("total_chunks")
    private Integer totalChunks; // 预计处理的文本分块总数

    @TableField("error_code")
    private String errorCode; // 最近一次失败的脱敏错误码

    @TableField("error_detail")
    private String errorDetail; // 最近一次失败的脱敏错误详情

    @TableField("cancel_requested_at")
    private OffsetDateTime cancelRequestedAt; // 取消请求接收时间

    @TableField("started_at")
    private OffsetDateTime startedAt; // 首次开始执行时间

    @TableField("finished_at")
    private OffsetDateTime finishedAt; // 最终结束时间

    @TableField("created_at")
    private OffsetDateTime createdAt; // 创建时间

    @TableField("updated_at")
    private OffsetDateTime updatedAt; // 最近更新时间
}
