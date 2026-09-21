package com.haha.supportai.conversation.entity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class RagHistoryTurn {

    private final String question; // 历史轮次中的完整用户问题

    private final String answer; // 已将旧来源编号映射为本轮编号的完整助手回答
}
