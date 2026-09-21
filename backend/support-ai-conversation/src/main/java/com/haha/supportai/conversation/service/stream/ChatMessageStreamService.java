package com.haha.supportai.conversation.service.stream;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.haha.supportai.conversation.config.ChatStreamConfiguration;
import com.haha.supportai.conversation.config.ChatStreamProperties;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.dto.RagAnswerResult;
import com.haha.supportai.conversation.entity.dto.RagMetrics;
import com.haha.supportai.conversation.entity.vo.ChatStreamDeltaResponse;
import com.haha.supportai.conversation.entity.vo.ChatStreamDoneResponse;
import com.haha.supportai.conversation.entity.vo.ChatStreamErrorResponse;
import com.haha.supportai.conversation.entity.vo.ChatStreamSourcesResponse;
import com.haha.supportai.conversation.entity.vo.ChatStreamStartResponse;
import com.haha.supportai.conversation.entity.vo.RagSourceResponse;
import com.haha.supportai.conversation.exception.ChatSessionInvalidRequestException;
import com.haha.supportai.conversation.exception.ChatRunCancelledException;
import com.haha.supportai.conversation.exception.ChatStreamCapacityException;
import com.haha.supportai.conversation.exception.RagGenerationException;
import com.haha.supportai.conversation.service.rag.RagAnswerService;
import com.haha.supportai.conversation.service.rag.RagRunPersistenceService;
import com.haha.supportai.conversation.service.rag.RagStreamObserver;
import com.haha.supportai.conversation.service.run.ChatRunExecutionRegistry;
import com.haha.supportai.conversation.support.ChatStreamEventNameConstant;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.knowledge.contract.KnowledgeScopeAccess;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatMessageStreamService {

    private static final String REQUEST_LIMITED_CODE = "REQUEST_LIMITED";
    private static final String REQUEST_LIMITED_DETAIL = "流式问答执行队列已满";
    private static final String INTERNAL_ERROR_CODE = "RAG_GENERATION_FAILED";
    private static final String INTERNAL_ERROR_DETAIL = "问答生成流程异常";
    private static final String PUBLIC_ERROR_MESSAGE = "本次问答生成失败，请稍后重试";
    private static final String MODEL_TIMEOUT_CODE = "MODEL_TIMEOUT";
    private static final String MODEL_TIMEOUT_DETAIL = "问答生成超过总执行时间";
    private static final String PUBLIC_TIMEOUT_MESSAGE = "回答生成超时，请稍后重试";

    private final RagAnswerService ragAnswerService;
    private final RagRunPersistenceService persistenceService;
    private final ChatSessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    private final KnowledgeScopeAccess knowledgeScopeAccess;
    private final ChatStreamProperties properties;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final ThreadPoolTaskScheduler timeoutScheduler;
    private final ChatRunExecutionRegistry executionRegistry;

    public ChatMessageStreamService(RagAnswerService ragAnswerService, RagRunPersistenceService persistenceService,
            ChatSessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
            KnowledgeScopeAccess knowledgeScopeAccess, ChatStreamProperties properties,
            @Qualifier(ChatStreamConfiguration.TASK_EXECUTOR_BEAN_NAME) ThreadPoolTaskExecutor taskExecutor,
            @Qualifier(ChatStreamConfiguration.TIMEOUT_SCHEDULER_BEAN_NAME) ThreadPoolTaskScheduler timeoutScheduler,
            ChatRunExecutionRegistry executionRegistry) {
        this.ragAnswerService = ragAnswerService;
        this.persistenceService = persistenceService;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.knowledgeScopeAccess = knowledgeScopeAccess;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
        this.timeoutScheduler = timeoutScheduler;
        this.executionRegistry = executionRegistry;
    }

    public SseEmitter open(PreparedChatRunResponse preparedRun, String traceId) {
        validate(preparedRun);
        SseEmitter emitter = new SseEmitter(properties.getTimeout().toMillis());
        AtomicBoolean connected = new AtomicBoolean(true);
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean terminalSent = new AtomicBoolean(false);
        emitter.onCompletion(() -> connected.set(false));
        emitter.onTimeout(() -> connected.set(false));
        emitter.onError(error -> connected.set(false));
        AtomicReference<FutureTask<Void>> taskReference = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> timeoutReference = new AtomicReference<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            generate(preparedRun, traceId, emitter, connected, started, terminalSent, taskReference.get(),
                    timeoutReference.get());
            return null;
        });
        taskReference.set(task);
        executionRegistry.register(preparedRun.getRunId(), task,
                () -> {
                    cancelScheduledTimeout(timeoutReference.get());
                    cancelStream(preparedRun, traceId, emitter, connected, started, terminalSent);
                });
        ScheduledFuture<?> timeoutTask;
        try {
            timeoutTask = timeoutScheduler.schedule(
                    () -> timeoutRun(preparedRun, traceId, emitter, connected, started, terminalSent),
                    Instant.now().plus(properties.getExecutionTimeout()));
            if (timeoutTask == null) {
                throw new IllegalStateException("问答超时任务创建失败");
            }
            timeoutReference.set(timeoutTask);
        } catch (RuntimeException exception) {
            executionRegistry.unregister(preparedRun.getRunId(), task);
            connected.set(false);
            emitter.complete();
            failUnexpectedly(preparedRun);
            throw exception;
        }
        try {
            taskExecutor.execute(task);
        } catch (TaskRejectedException exception) {
            timeoutTask.cancel(false);
            executionRegistry.unregister(preparedRun.getRunId(), task);
            connected.set(false);
            emitter.complete();
            failForCapacity(preparedRun);
            throw new ChatStreamCapacityException();
        }
        return emitter;
    }

    private void generate(PreparedChatRunResponse preparedRun, String traceId, SseEmitter emitter,
            AtomicBoolean connected, AtomicBoolean started, AtomicBoolean terminalSent, FutureTask<Void> task,
            ScheduledFuture<?> timeoutTask) {
        RagStreamObserver observer = new RagStreamObserver() {
            @Override
            public void onStarted() {
                sendStart(preparedRun, emitter, connected, started);
            }

            @Override
            public void onDelta(String delta) {
                send(emitter, connected, ChatStreamEventNameConstant.DELTA, new ChatStreamDeltaResponse(delta));
            }
        };
        try {
            RagAnswerResult result = ragAnswerService.generateStreaming(preparedRun, observer);
            if (terminalSent.compareAndSet(false, true)) {
                List<RagSourceResponse> sources = sourcesForDelivery(preparedRun, result);
                send(emitter, connected, ChatStreamEventNameConstant.SOURCES,
                        new ChatStreamSourcesResponse(sources));
                RagMetrics metrics = metricsForDelivery(preparedRun, result.getMetrics());
                send(emitter, connected, ChatStreamEventNameConstant.DONE,
                        new ChatStreamDoneResponse(result.getRunId(), result.getStatus(), result.getUsage(),
                                metrics));
            }
        } catch (ChatRunCancelledException exception) {
            cancelStream(preparedRun, traceId, emitter, connected, started, terminalSent);
        } catch (RagGenerationException exception) {
            sendTerminalError(emitter, connected, terminalSent,
                    new ChatStreamErrorResponse(exception.getErrorCode(), exception.getMessage(),
                            ChatRunStatusEnum.FAILED, normalizeTraceId(traceId)));
        } catch (RuntimeException exception) {
            failUnexpectedly(preparedRun);
            sendTerminalError(emitter, connected, terminalSent,
                    new ChatStreamErrorResponse(INTERNAL_ERROR_CODE, PUBLIC_ERROR_MESSAGE,
                            ChatRunStatusEnum.FAILED, normalizeTraceId(traceId)));
        } finally {
            cancelScheduledTimeout(timeoutTask);
            executionRegistry.unregister(preparedRun.getRunId(), task);
            complete(emitter, connected);
        }
    }

    private void cancelScheduledTimeout(ScheduledFuture<?> timeoutTask) {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }
    }

    private List<RagSourceResponse> sourcesForDelivery(PreparedChatRunResponse preparedRun, RagAnswerResult result) {
        if (result.getSources() == null || result.getSources().isEmpty()) {
            return List.of();
        }
        try {
            return ragAnswerService.revalidateReadableSources(preparedRun.getUserId(), result.getSources());
        } catch (RuntimeException ignored) {
            // 运行已完成时来源展示必须失败关闭，不能因复核异常泄露缓存元数据或改写终态。
            return List.of();
        }
    }

    private RagMetrics metricsForDelivery(PreparedChatRunResponse preparedRun, RagMetrics metrics) {
        if (metrics == null || metrics.getQueryRewrite() == null) {
            return metrics;
        }
        try {
            List<UUID> knowledgeBaseIds = sessionKnowledgeBaseRepository
                    .listKnowledgeBaseIdsBySessionId(preparedRun.getSessionId());
            if (knowledgeScopeAccess.allReadable(preparedRun.getUserId(), knowledgeBaseIds)) {
                return metrics;
            }
        } catch (RuntimeException ignored) {
            // 无法确认当前会话知识范围仍可读时，必须隐藏可能由历史内容生成的检索问题。
        }
        return metrics.withoutQueryRewriteText();
    }

    private void timeoutRun(PreparedChatRunResponse preparedRun, String traceId, SseEmitter emitter,
            AtomicBoolean connected, AtomicBoolean started, AtomicBoolean terminalSent) {
        try {
            persistenceService.fail(preparedRun.getRunId(), preparedRun.getAssistantMessageId(), null,
                    MODEL_TIMEOUT_CODE, MODEL_TIMEOUT_DETAIL, null, null);
        } catch (RuntimeException ignored) {
            // 完成、取消或其他失败已先取得终态时，超时任务不能覆盖实际结果。
            return;
        }
        executionRegistry.cancelWithCallback(preparedRun.getRunId(),
                () -> timeoutStream(preparedRun, traceId, emitter, connected, started, terminalSent));
    }

    private void timeoutStream(PreparedChatRunResponse preparedRun, String traceId, SseEmitter emitter,
            AtomicBoolean connected, AtomicBoolean started, AtomicBoolean terminalSent) {
        sendStart(preparedRun, emitter, connected, started);
        sendTerminalError(emitter, connected, terminalSent,
                new ChatStreamErrorResponse(MODEL_TIMEOUT_CODE, PUBLIC_TIMEOUT_MESSAGE, ChatRunStatusEnum.FAILED,
                        normalizeTraceId(traceId)));
        complete(emitter, connected);
    }

    private void cancelStream(PreparedChatRunResponse preparedRun, String traceId, SseEmitter emitter,
            AtomicBoolean connected, AtomicBoolean started, AtomicBoolean terminalSent) {
        sendStart(preparedRun, emitter, connected, started);
        sendTerminalError(emitter, connected, terminalSent,
                new ChatStreamErrorResponse("RUN_CANCELLED", "已停止生成", ChatRunStatusEnum.CANCELLED,
                        normalizeTraceId(traceId)));
        complete(emitter, connected);
    }

    private void sendStart(PreparedChatRunResponse preparedRun, SseEmitter emitter, AtomicBoolean connected,
            AtomicBoolean started) {
        if (started.compareAndSet(false, true)) {
            send(emitter, connected, ChatStreamEventNameConstant.START,
                    new ChatStreamStartResponse(preparedRun.getRunId(), preparedRun.getAssistantMessageId()));
        }
    }

    private void sendTerminalError(SseEmitter emitter, AtomicBoolean connected, AtomicBoolean terminalSent,
            ChatStreamErrorResponse error) {
        if (terminalSent.compareAndSet(false, true)) {
            send(emitter, connected, ChatStreamEventNameConstant.ERROR, error);
        }
    }

    void send(SseEmitter emitter, AtomicBoolean connected, String eventName, Object data) {
        if (!connected.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException exception) {
            connected.set(false);
        }
    }

    void complete(SseEmitter emitter, AtomicBoolean connected) {
        if (connected.compareAndSet(true, false)) {
            emitter.complete();
        }
    }

    private void failForCapacity(PreparedChatRunResponse preparedRun) {
        try {
            persistenceService.fail(preparedRun.getRunId(), preparedRun.getAssistantMessageId(), null,
                    REQUEST_LIMITED_CODE, REQUEST_LIMITED_DETAIL, null, null);
        } catch (RuntimeException ignored) {
            // 容量错误仍按统一 429 返回，数据库异常不得覆盖流前协议。
        }
    }

    private void failUnexpectedly(PreparedChatRunResponse preparedRun) {
        try {
            persistenceService.fail(preparedRun.getRunId(), preparedRun.getAssistantMessageId(), null,
                    INTERNAL_ERROR_CODE, INTERNAL_ERROR_DETAIL, null, null);
        } catch (RuntimeException ignored) {
            // 仅返回稳定错误，禁止将底层异常内容写入事件或运行详情。
        }
    }

    private void validate(PreparedChatRunResponse preparedRun) {
        if (preparedRun == null || preparedRun.isReused() || preparedRun.getStatus() != ChatRunStatusEnum.QUEUED
                || preparedRun.getRunId() == null || preparedRun.getAssistantMessageId() == null
                || preparedRun.getUserId() == null) {
            throw new ChatSessionInvalidRequestException();
        }
    }

    private String normalizeTraceId(String traceId) {
        return traceId == null || traceId.isBlank() ? "unknown" : traceId;
    }
}
