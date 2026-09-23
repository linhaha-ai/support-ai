package com.haha.supportai.assistant.agent.tool.entity.dto;

import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AssistantToolContext {

    private final UUID runId; // 服务端已准备的问答运行标识

    private final UUID sessionId; // 服务端已确认的会话标识

    private final UUID userId; // 服务端鉴权后确认的用户标识

    private final UUID assistantMessageId; // 当前运行的助手占位消息标识

    public static AssistantToolContext from(PreparedChatRunResponse preparedRun) {
        if (preparedRun == null) {
            throw new IllegalArgumentException("运行上下文不能为空");
        }
        return new AssistantToolContext(preparedRun.getRunId(), preparedRun.getSessionId(), preparedRun.getUserId(),
                preparedRun.getAssistantMessageId());
    }
}
