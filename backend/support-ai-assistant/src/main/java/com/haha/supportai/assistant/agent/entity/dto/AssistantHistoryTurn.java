package com.haha.supportai.assistant.agent.entity.dto;

import java.util.Set;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AssistantHistoryTurn {

    private final String question; // 已完成历史轮次的用户问题
    private final String answer; // 已转换为本轮可信来源句柄的历史回答
    private final Set<String> ticketNumbers; // 本轮历史直接涉及的工单编号，供指代消歧
}
