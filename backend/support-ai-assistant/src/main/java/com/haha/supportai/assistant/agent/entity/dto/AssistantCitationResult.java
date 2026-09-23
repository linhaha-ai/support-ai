package com.haha.supportai.assistant.agent.entity.dto;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AssistantCitationResult {

    private final String answer; // 已将可信来源句柄转换为数字引用的最终回答

    private final List<Integer> citationNumbers; // 最终回答实际使用的来源编号
}
