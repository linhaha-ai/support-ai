package com.haha.supportai.conversation.entity.dto;

import java.util.UUID;

import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import lombok.Getter;

@Getter
public class PreparedChatRunResponse {

    private final UUID runId; // 已创建或已复用的运行标识

    private final UUID sessionId; // 运行所属会话标识

    private final UUID userId; // 服务端鉴权后确认的运行用户标识

    private final UUID userMessageId; // 用户消息标识

    private final UUID assistantMessageId; // 助手占位消息标识

    private final String question; // 已持久化的规范化用户问题

    private final ChatSessionModeEnum mode; // 服务端已确认的会话执行模式

    private final ChatRunStatusEnum status; // 运行当前状态

    private final boolean reused; // 是否命中同一幂等请求

    public PreparedChatRunResponse(UUID runId, UUID sessionId, UUID userId, UUID userMessageId,
            UUID assistantMessageId, String question, ChatSessionModeEnum mode, ChatRunStatusEnum status,
            boolean reused) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.userMessageId = userMessageId;
        this.assistantMessageId = assistantMessageId;
        this.question = question;
        this.mode = mode;
        this.status = status;
        this.reused = reused;
    }

    public PreparedChatRunResponse(UUID runId, UUID sessionId, UUID userId, UUID userMessageId,
            UUID assistantMessageId, String question, ChatRunStatusEnum status, boolean reused) {
        this(runId, sessionId, userId, userMessageId, assistantMessageId, question, ChatSessionModeEnum.RAG,
                status, reused);
    }

    public PreparedChatRunResponse(UUID runId, UUID sessionId, UUID userMessageId, UUID assistantMessageId,
            String question, ChatRunStatusEnum status, boolean reused) {
        this(runId, sessionId, null, userMessageId, assistantMessageId, question, ChatSessionModeEnum.RAG,
                status, reused);
    }
}
