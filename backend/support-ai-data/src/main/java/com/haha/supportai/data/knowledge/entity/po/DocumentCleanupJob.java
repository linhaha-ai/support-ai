package com.haha.supportai.data.knowledge.entity.po;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.haha.supportai.data.knowledge.enums.DocumentCleanupStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.EnumTypeHandler;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "document_cleanup_job", autoResultMap = true)
public class DocumentCleanupJob {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id; // 清理任务唯一标识

    @TableField("document_id")
    private UUID documentId; // 待清理文档标识

    @TableField(value = "status", typeHandler = EnumTypeHandler.class)
    private DocumentCleanupStatusEnum status; // 清理任务状态

    @TableField("attempt")
    private int attempt; // 累计领取次数

    @TableField("lease_owner")
    private String leaseOwner; // 当前清理执行者标识

    @TableField("lease_token")
    private UUID leaseToken; // 当前清理租约令牌

    @TableField("lease_until")
    private OffsetDateTime leaseUntil; // 当前租约失效时间

    @TableField("next_run_at")
    private OffsetDateTime nextRunAt; // 下一次允许领取时间

    @TableField("error_code")
    private String errorCode; // 最近一次失败的稳定错误码

    @TableField("error_detail")
    private String errorDetail; // 最近一次失败的脱敏详情

    @TableField("started_at")
    private OffsetDateTime startedAt; // 首次开始清理时间

    @TableField("finished_at")
    private OffsetDateTime finishedAt; // 成功完成清理时间

    @TableField("created_at")
    private OffsetDateTime createdAt; // 清理任务创建时间

    @TableField("updated_at")
    private OffsetDateTime updatedAt; // 清理任务最近更新时间
}
