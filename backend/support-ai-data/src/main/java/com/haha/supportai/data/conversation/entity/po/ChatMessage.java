package com.haha.supportai.data.conversation.entity.po;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.haha.supportai.data.conversation.enums.ChatMessageRoleEnum;
import com.haha.supportai.data.conversation.enums.ChatMessageStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.EnumTypeHandler;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "chat_message", autoResultMap = true)
public class ChatMessage {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id; // 消息唯一标识，由业务层生成 UUID

    @TableField("run_id")
    private UUID runId; // 所属运行标识

    @TableField("session_id")
    private UUID sessionId; // 所属会话标识

    @TableField("seq")
    private long sequence; // 会话内递增消息序号

    @TableField(value = "role", typeHandler = EnumTypeHandler.class)
    private ChatMessageRoleEnum role; // 消息角色，按枚举名称存储

    @TableField("content")
    private String content; // 消息正文

    @TableField(value = "status", typeHandler = EnumTypeHandler.class)
    private ChatMessageStatusEnum status; // 消息状态，按枚举名称存储

    @TableField("created_at")
    private OffsetDateTime createdAt; // 消息创建时间

    @TableField("updated_at")
    private OffsetDateTime updatedAt; // 消息最近更新时间
}
