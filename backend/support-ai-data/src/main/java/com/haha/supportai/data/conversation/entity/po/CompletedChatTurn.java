package com.haha.supportai.data.conversation.entity.po;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CompletedChatTurn {

    private UUID runId; // 已完成运行标识

    private UUID userMessageId; // 本轮用户消息标识

    private UUID assistantMessageId; // 本轮助手消息标识

    private long userSequence; // 用户消息在会话内的序号

    private String question; // 已完成轮次的用户问题

    private String answer; // 已完成轮次的助手回答
}
