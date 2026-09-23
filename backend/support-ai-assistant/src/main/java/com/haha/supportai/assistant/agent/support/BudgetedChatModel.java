package com.haha.supportai.assistant.agent.support;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

public class BudgetedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final AssistantAgentBudget budget;

    public BudgetedChatModel(ChatModel delegate, AssistantAgentBudget budget) {
        this.delegate = delegate;
        this.budget = budget;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        budget.beforeModel(prompt);
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        budget.beforeModel(prompt);
        return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
