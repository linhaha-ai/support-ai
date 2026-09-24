package com.haha.supportai.assistant.agent.entity.dto;

import java.util.List;

import com.haha.supportai.data.conversation.entity.po.MessageSource;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AssistantConversationContext {

    private final List<AssistantHistoryTurn> turns; // 按时间正序排列的有限成功轮次
    private final List<MessageSource> sources; // 全部继承证据，包含未被答案显式引用的来源

    public static AssistantConversationContext empty() {
        return new AssistantConversationContext(List.of(), List.of());
    }
}
