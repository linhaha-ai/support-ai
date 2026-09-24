package com.haha.supportai.assistant.agent.support;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

public class BudgetedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final AssistantAgentBudget budget;
    private final Runnable sourceValidation;
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    public BudgetedChatModel(ChatModel delegate, AssistantAgentBudget budget) {
        this(delegate, budget, () -> { });
    }

    public BudgetedChatModel(ChatModel delegate, AssistantAgentBudget budget, Runnable sourceValidation) {
        this.delegate = delegate;
        this.budget = budget;
        this.sourceValidation = sourceValidation;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            throwIfFailed();
            budget.beforeModel(prompt);
            sourceValidation.run();
            return delegate.call(prompt);
        } catch (RuntimeException exception) {
            failure.compareAndSet(null, exception);
            throw exception;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        try {
            throwIfFailed();
            budget.beforeModel(prompt);
            sourceValidation.run();
            return delegate.stream(prompt)
                    .doOnError(RuntimeException.class, exception -> failure.compareAndSet(null, exception));
        } catch (RuntimeException exception) {
            failure.compareAndSet(null, exception);
            throw exception;
        }
    }

    public void throwIfFailed() {
        // 框架可能将节点异常转为结束结果，业务层仍需按首次失败收口。
        RuntimeException exception = failure.get();
        if (exception != null) {
            throw exception;
        }
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
