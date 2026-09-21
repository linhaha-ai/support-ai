package com.haha.supportai.data.conversation.entity.po;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_session_kb")
public class ChatSessionKnowledgeBase {

    @TableField("session_id")
    private UUID sessionId; // 会话标识

    @TableField("kb_id")
    private UUID knowledgeBaseId; // 固定知识库标识

    @TableField("created_at")
    private OffsetDateTime createdAt; // 固定范围创建时间
}
