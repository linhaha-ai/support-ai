package com.haha.supportai.conversation.entity.dto;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.conversation.entity.vo.RagSourceResponse;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AssistantAnswerResult {

    private final UUID runId; // 本次助手运行标识

    private final UUID assistantMessageId; // 助手消息标识

    private final String content; // 已持久化的完整回答

    private final ChatRunStatusEnum status; // 本次运行终态

    private final List<RagSourceResponse> sources; // 发送前复核后仍可展示的已引用来源

    private final AgentMetrics metrics; // 已持久化的受控助手执行指标
}
