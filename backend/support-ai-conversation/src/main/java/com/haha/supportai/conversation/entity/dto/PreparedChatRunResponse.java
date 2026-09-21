package com.haha.supportai.conversation.entity.dto;

import java.util.UUID;

import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class PreparedChatRunResponse {

    private final UUID runId; // 已创建或已复用的运行标识

    private final UUID userMessageId; // 用户消息标识

    private final UUID assistantMessageId; // 助手占位消息标识

    private final ChatRunStatusEnum status; // 运行当前状态

    private final boolean reused; // 是否命中同一幂等请求
}
