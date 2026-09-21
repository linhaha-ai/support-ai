package com.haha.supportai.conversation.service.run;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;

import org.springframework.stereotype.Component;

@Component
public class ChatRunExecutionRegistry {

    private final ConcurrentMap<UUID, RunningExecution> executions = new ConcurrentHashMap<>();

    public void register(UUID runId, Future<?> future, Runnable cancellationCallback) {
        Objects.requireNonNull(runId, "运行标识不能为空");
        Objects.requireNonNull(future, "运行任务不能为空");
        Objects.requireNonNull(cancellationCallback, "取消回调不能为空");
        RunningExecution previous = executions.putIfAbsent(runId,
                new RunningExecution(future, cancellationCallback));
        if (previous != null) {
            throw new IllegalStateException("问答运行已经登记执行任务");
        }
    }

    public boolean cancel(UUID runId) {
        return cancel(runId, null);
    }

    public boolean cancelWithCallback(UUID runId, Runnable cancellationCallback) {
        Objects.requireNonNull(cancellationCallback, "取消回调不能为空");
        return cancel(runId, cancellationCallback);
    }

    private boolean cancel(UUID runId, Runnable callbackOverride) {
        RunningExecution execution = executions.remove(runId);
        if (execution == null) {
            return false;
        }
        execution.future().cancel(true);
        try {
            Runnable callback = callbackOverride == null ? execution.cancellationCallback() : callbackOverride;
            callback.run();
        } catch (RuntimeException ignored) {
            // 数据库已先完成取消，推送通道异常不得改变持久化终态。
        }
        return true;
    }

    public void unregister(UUID runId, Future<?> future) {
        executions.computeIfPresent(runId, (ignored, execution) -> execution.future() == future ? null : execution);
    }

    boolean contains(UUID runId) {
        return executions.containsKey(runId);
    }

    private record RunningExecution(Future<?> future, Runnable cancellationCallback) {
    }
}
