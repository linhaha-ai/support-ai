package com.haha.supportai.data.conversation.entity.po;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.EnumTypeHandler;
import org.apache.ibatis.type.JdbcType;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "chat_run", autoResultMap = true)
public class ChatRun {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id; // 运行唯一标识，由业务层生成 UUID

    @TableField("session_id")
    private UUID sessionId; // 所属会话标识

    @TableField("client_request_id")
    private UUID clientRequestId; // 客户端幂等请求 UUID

    @TableField("request_hash")
    private String requestHash; // 规范化请求内容 SHA-256 摘要

    @TableField(value = "status", typeHandler = EnumTypeHandler.class)
    private ChatRunStatusEnum status; // 运行状态，按枚举名称存储

    @TableField(value = "config_snapshot", jdbcType = JdbcType.VARCHAR)
    private String configSnapshot; // 不含密钥的配置 JSON 快照

    @TableField(value = "usage", jdbcType = JdbcType.VARCHAR)
    private String usage; // 模型用量 JSON

    @TableField(value = "metrics", jdbcType = JdbcType.VARCHAR)
    private String metrics; // 检索和生成指标 JSON

    @TableField("error_code")
    private String errorCode; // 稳定错误码

    @TableField("error_detail")
    private String errorDetail; // 脱敏错误详情

    @TableField("cancel_requested_at")
    private OffsetDateTime cancelRequestedAt; // 取消请求接收时间

    @TableField("started_at")
    private OffsetDateTime startedAt; // 运行开始时间

    @TableField("finished_at")
    private OffsetDateTime finishedAt; // 运行终态完成时间

    @TableField("created_at")
    private OffsetDateTime createdAt; // 运行创建时间

    @TableField("updated_at")
    private OffsetDateTime updatedAt; // 运行最近更新时间
}
