package com.haha.supportai.conversation.entity.dto;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.conversation.entity.vo.RagSourceResponse;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class RagAnswerResult {

    private final UUID runId; // 本次问答运行标识

    private final UUID assistantMessageId; // 助手消息标识

    private final String content; // 已持久化的完整回答

    private final List<Integer> citedSourceNumbers; // 回答实际引用且已登记的来源编号

    private final ChatRunStatusEnum status; // 本次运行终态

    private final boolean noEvidence; // 是否因没有可用证据而返回资料不足

    private final List<RagSourceResponse> sources; // 发送前复核后仍可展示的已引用来源

    private final ChatModelUsage usage; // 模型用量，提供方未返回时为空

    private final RagMetrics metrics; // 已持久化的非敏感执行指标
}
