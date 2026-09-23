package com.haha.supportai.conversation.service.run;

import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRunExecutionRegistryTest {

    private static final UUID RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Test
    void cancellationInterruptsTaskRunsCallbackAndRemovesExecution() {
        ChatRunExecutionRegistry registry = new ChatRunExecutionRegistry();
        FutureTask<Void> task = new FutureTask<>(() -> null);
        AtomicInteger callbackCount = new AtomicInteger();
        registry.register(RUN_ID, task, callbackCount::incrementAndGet);

        assertThat(registry.cancel(RUN_ID)).isTrue();

        assertThat(task.isCancelled()).isTrue();
        assertThat(callbackCount).hasValue(1);
        assertThat(registry.contains(RUN_ID)).isFalse();
        assertThat(registry.cancel(RUN_ID)).isFalse();
    }

    @Test
    void sameRunCannotRegisterTwoLiveExecutions() {
        ChatRunExecutionRegistry registry = new ChatRunExecutionRegistry();
        FutureTask<Void> first = new FutureTask<>(() -> null);
        registry.register(RUN_ID, first, () -> { });

        assertThatThrownBy(() -> registry.register(RUN_ID, new FutureTask<>(() -> null), () -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("问答运行已经登记执行任务");
    }

    @Test
    void timeoutCanReplaceUserCancellationCallback() {
        ChatRunExecutionRegistry registry = new ChatRunExecutionRegistry();
        FutureTask<Void> task = new FutureTask<>(() -> null);
        AtomicInteger userCancellationCount = new AtomicInteger();
        AtomicInteger timeoutCount = new AtomicInteger();
        registry.register(RUN_ID, task, userCancellationCount::incrementAndGet);

        assertThat(registry.cancelWithCallback(RUN_ID, timeoutCount::incrementAndGet)).isTrue();

        assertThat(task.isCancelled()).isTrue();
        assertThat(userCancellationCount).hasValue(0);
        assertThat(timeoutCount).hasValue(1);
    }

    @Test
    void staleCompletionCannotRemoveNewExecution() {
        ChatRunExecutionRegistry registry = new ChatRunExecutionRegistry();
        FutureTask<Void> first = new FutureTask<>(() -> null);
        registry.register(RUN_ID, first, () -> { });
        registry.unregister(RUN_ID, first);

        FutureTask<Void> second = new FutureTask<>(() -> null);
        registry.register(RUN_ID, second, () -> { });
        registry.unregister(RUN_ID, first);

        assertThat(registry.contains(RUN_ID)).isTrue();
    }
}
