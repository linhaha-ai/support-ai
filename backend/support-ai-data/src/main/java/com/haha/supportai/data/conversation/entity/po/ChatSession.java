package com.haha.supportai.data.conversation.entity.po;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.EnumTypeHandler;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "chat_session", autoResultMap = true)
public class ChatSession {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id; // 会话唯一标识，由业务层生成 UUID

    @TableField("user_id")
    private UUID userId; // 会话所属用户标识

    @TableField("title")
    private String title; // 会话展示标题

    @TableField(value = "mode", typeHandler = EnumTypeHandler.class)
    private ChatSessionModeEnum mode; // 会话模式，按枚举名称存储

    @TableField("next_message_seq")
    private long nextMessageSeq; // 下一个可分配的会话消息序号

    @TableField("deleted_at")
    private OffsetDateTime deletedAt; // 会话逻辑删除时间

    @TableField("created_at")
    private OffsetDateTime createdAt; // 会话创建时间

    @TableField("updated_at")
    private OffsetDateTime updatedAt; // 会话最近更新时间
}
