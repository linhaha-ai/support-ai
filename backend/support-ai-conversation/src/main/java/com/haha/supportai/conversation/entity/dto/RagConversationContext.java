package com.haha.supportai.conversation.entity.dto;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class RagConversationContext {

    private final List<RagHistoryTurn> turns; // 按时间正序排列的完整历史问答轮次

    private final List<RagHistorySource> sources; // 本轮需要继承并重新登记的历史根来源

    public static RagConversationContext empty() {
        return new RagConversationContext(List.of(), List.of());
    }
}
