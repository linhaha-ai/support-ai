package com.haha.supportai.conversation.entity.dto;

import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class SubmitMessageCommand {

    private UUID clientRequestId; // 客户端幂等请求标识

    private String content; // 用户消息正文

    public SubmitMessageCommand(UUID clientRequestId, String content) {
        this.clientRequestId = clientRequestId;
        this.content = content;
    }
}
