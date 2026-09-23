package com.haha.supportai.assistant.agent.entity.dto;

import java.util.Set;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AssistantAgentRunResult {

    private final String answer; // ReactAgent 返回的原始最终回答

    private final int modelCallCount; // 实际模型调用次数

    private final int toolCallCount; // 实际工具调用次数

    private final int maxObservedContextTokens; // 单次模型调用观察到的最大上下文 Token 数

    private final Set<String> calledToolNames; // 本轮实际执行过的服务端工具名称
}
