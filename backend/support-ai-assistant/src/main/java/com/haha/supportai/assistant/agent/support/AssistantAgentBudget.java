package com.haha.supportai.assistant.agent.support;

import java.util.concurrent.atomic.AtomicInteger;

import com.haha.supportai.assistant.agent.exception.AssistantAgentBudgetException;
import com.haha.supportai.conversation.config.AssistantAgentProperties;
import com.haha.supportai.conversation.exception.ChatRunCancelledException;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.chat.prompt.Prompt;

public class AssistantAgentBudget {

    private static final Encoding TOKEN_ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    private final AssistantAgentProperties properties;
    private final AtomicInteger modelCallCount = new AtomicInteger();
    private final AtomicInteger toolCallCount = new AtomicInteger();
    private final AtomicInteger maxObservedContextTokens = new AtomicInteger();

    public AssistantAgentBudget(AssistantAgentProperties properties) {
        this.properties = properties;
    }

    public void beforeModel(Prompt prompt) {
        ensureActive();
        if (modelCallCount.incrementAndGet() > properties.getMaxModelCalls()) {
            throw new AssistantAgentBudgetException("AGENT_MODEL_CALL_LIMIT", "助手模型调用次数已达到上限");
        }
        int contextTokens = TOKEN_ENCODING.countTokensOrdinary(prompt == null ? "" : prompt.getContents());
        maxObservedContextTokens.accumulateAndGet(contextTokens, Math::max);
        if (contextTokens > properties.getMaxContextTokens()) {
            throw new AssistantAgentBudgetException("AGENT_CONTEXT_LIMIT", "助手上下文已超过预算");
        }
    }

    public void beforeTool() {
        ensureActive();
        if (toolCallCount.incrementAndGet() > properties.getMaxToolCalls()) {
            throw new AssistantAgentBudgetException("AGENT_TOOL_CALL_LIMIT", "助手工具调用次数已达到上限");
        }
    }

    public void ensureActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ChatRunCancelledException();
        }
    }

    public int getModelCallCount() {
        return modelCallCount.get();
    }

    public int getToolCallCount() {
        return toolCallCount.get();
    }

    public int getMaxObservedContextTokens() {
        return maxObservedContextTokens.get();
    }
}
