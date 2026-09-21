package com.haha.supportai.conversation.service.stream;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.haha.supportai.conversation.config.ChatStreamConfiguration;
import com.haha.supportai.conversation.config.ChatStreamProperties;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.dto.RagAnswerResult;
import com.haha.supportai.conversation.entity.vo.ChatStreamDeltaResponse;
import com.haha.supportai.conversation.entity.vo.ChatStreamDoneResponse;
import com.haha.supportai.conversation.entity.vo.ChatStreamErrorResponse;
import com.haha.supportai.conversation.entity.vo.ChatStreamSourcesResponse;
import com.haha.supportai.conversation.entity.vo.ChatStreamStartResponse;
import com.haha.supportai.conversation.exception.ChatSessionInvalidRequestException;
import com.haha.supportai.conversation.exception.ChatStreamCapacityException;
import com.haha.supportai.conversation.exception.RagGenerationException;
import com.haha.supportai.conversation.service.rag.RagAnswerService;
import com.haha.supportai.conversation.service.rag.RagRunPersistenceService;
import com.haha.supportai.conversation.service.rag.RagStreamObserver;
import com.haha.supportai.conversation.support.ChatStreamEventNameConstant;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatMessageStreamService {

    private static final String REQUEST_LIMITED_CODE = "REQUEST_LIMITED";
    private static final String REQUEST_LIMITED_DETAIL = "流式问答执行队列已满";
    private static final String INTERNAL_ERROR_CODE = "RAG_GENERATION_FAILED";
    private static final String INTERNAL_ERROR_DETAIL = "问答生成流程异常";
    private static final String PUBLIC_ERROR_MESSAGE = "本次问答生成失败，请稍后重试";

    private final RagAnswerService ragAnswerService;
    private final RagRunPersistenceService persistenceService;
    private final ChatStreamProperties properties;
    private final ThreadPoolTaskExecutor taskExecutor;

    public ChatMessageStreamService(RagAnswerService ragAnswerService, RagRunPersistenceService persistenceService,
            ChatStreamProperties properties,
            @Qualifier(ChatStreamConfiguration.TASK_EXECUTOR_BEAN_NAME) ThreadPoolTaskExecutor taskExecutor) {
        this.ragAnswerService = ragAnswerService;
        this.persistenceService = persistenceService;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
    }

    public SseEmitter open(PreparedChatRunResponse preparedRun, String traceId) {
        validate(preparedRun);
        SseEmitter emitter = new SseEmitter(properties.getTimeout().toMillis());
        AtomicBoolean connected = new AtomicBoolean(true);
        emitter.onCompletion(() -> connected.set(false));
        emitter.onTimeout(() -> connected.set(false));
        emitter.onError(error -> connected.set(false));
        try {
            taskExecutor.execute(() -> generate(preparedRun, traceId, emitter, connected));
        } catch (TaskRejectedException exception) {
            connected.set(false);
            emitter.complete();
            failForCapacity(preparedRun);
            throw new ChatStreamCapacityException();
        }
        return emitter;
    }

    private void generate(PreparedChatRunResponse preparedRun, String traceId, SseEmitter emitter,
            AtomicBoolean connected) {
        RagStreamObserver observer = new RagStreamObserver() {
            @Override
            public void onStarted() {
                send(emitter, connected, ChatStreamEventNameConstant.START,
                        new ChatStreamStartResponse(preparedRun.getRunId(), preparedRun.getAssistantMessageId()));
            }

            @Override
            public void onDelta(String delta) {
                send(emitter, connected, ChatStreamEventNameConstant.DELTA, new ChatStreamDeltaResponse(delta));
            }
        };
        try {
            RagAnswerResult result = ragAnswerService.generateStreaming(preparedRun, observer);
            send(emitter, connected, ChatStreamEventNameConstant.SOURCES,
                    new ChatStreamSourcesResponse(result.getSources()));
            send(emitter, connected, ChatStreamEventNameConstant.DONE,
                    new ChatStreamDoneResponse(result.getRunId(), result.getStatus(), result.getUsage(),
                            result.getMetrics()));
        } catch (RagGenerationException exception) {
            send(emitter, connected, ChatStreamEventNameConstant.ERROR,
                    new ChatStreamErrorResponse(exception.getErrorCode(), exception.getMessage(),
                            ChatRunStatusEnum.FAILED, normalizeTraceId(traceId)));
        } catch (RuntimeException exception) {
            failUnexpectedly(preparedRun);
            send(emitter, connected, ChatStreamEventNameConstant.ERROR,
                    new ChatStreamErrorResponse(INTERNAL_ERROR_CODE, PUBLIC_ERROR_MESSAGE,
                            ChatRunStatusEnum.FAILED, normalizeTraceId(traceId)));
        } finally {
            complete(emitter, connected);
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
