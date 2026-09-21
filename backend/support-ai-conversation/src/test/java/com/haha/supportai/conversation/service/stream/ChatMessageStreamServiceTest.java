package com.haha.supportai.conversation.service.stream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.haha.supportai.conversation.config.ChatStreamProperties;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.dto.RagAnswerResult;
import com.haha.supportai.conversation.entity.dto.RagMetrics;
import com.haha.supportai.conversation.exception.ChatSessionInvalidRequestException;
import com.haha.supportai.conversation.exception.ChatStreamCapacityException;
import com.haha.supportai.conversation.exception.RagGenerationException;
import com.haha.supportai.conversation.service.rag.RagAnswerService;
import com.haha.supportai.conversation.service.rag.RagRunPersistenceService;
import com.haha.supportai.conversation.service.rag.RagStreamObserver;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatMessageStreamServiceTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID USER_MESSAGE_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID ASSISTANT_MESSAGE_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");

    @Mock
    RagAnswerService ragAnswerService;

    @Mock
    RagRunPersistenceService persistenceService;

    @Mock
    ThreadPoolTaskExecutor taskExecutor;

    private ChatMessageStreamService streamService;

    private List<String> eventNames;

    @BeforeEach
    void setUp() {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setTimeout(Duration.ofSeconds(30));
        eventNames = new ArrayList<>();
        streamService = new RecordingChatMessageStreamService(ragAnswerService, persistenceService, properties,
                taskExecutor, eventNames);
    }

    @Test
    void acceptedTaskUsesConfiguredTimeoutAndRunsGenerationOnce() {
        PreparedChatRunResponse preparedRun = preparedRun(false);
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        doAnswer(invocation -> {
            RagStreamObserver observer = invocation.getArgument(1);
            observer.onStarted();
            observer.onDelta("增量");
            return completedResult();
        }).when(ragAnswerService).generateStreaming(eq(preparedRun), any(RagStreamObserver.class));

        var emitter = streamService.open(preparedRun, "trace-test");
        verify(taskExecutor).execute(taskCaptor.capture());
        taskCaptor.getValue().run();

        assertThat(emitter.getTimeout()).isEqualTo(30_000L);
        assertThat(eventNames).containsExactly("start", "delta", "sources", "done");
        verify(ragAnswerService).generateStreaming(eq(preparedRun), any(RagStreamObserver.class));
    }

    @Test
    void generationFailureSendsErrorWithoutDoneEvent() {
        doAnswer(invocation -> {
            RagStreamObserver observer = invocation.getArgument(1);
            observer.onStarted();
            throw new RagGenerationException("MODEL_UNAVAILABLE", "本次问答生成失败，请稍后重试");
        }).when(ragAnswerService).generateStreaming(any(), any(RagStreamObserver.class));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));

        streamService.open(preparedRun(false), "trace-test");

        assertThat(eventNames).containsExactly("start", "error");
        assertThat(eventNames).doesNotContain("done");
    }

    @Test
    void unexpectedFailurePersistsSanitizedTerminalAndSendsOnlyError() {
        doAnswer(invocation -> {
            RagStreamObserver observer = invocation.getArgument(1);
            observer.onStarted();
            throw new IllegalStateException("sensitive internal detail");
        }).when(ragAnswerService).generateStreaming(any(), any(RagStreamObserver.class));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));

        streamService.open(preparedRun(false), "trace-test");

        verify(persistenceService).fail(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), isNull(),
                eq("RAG_GENERATION_FAILED"), eq("问答生成流程异常"), isNull(), isNull());
        assertThat(eventNames).containsExactly("start", "error");
        assertThat(eventNames).doesNotContain("done");
    }

    @Test
    void queueRejectionFailsPreparedRunAndReturnsFlowPreCapacityException() {
        doThrow(new TaskRejectedException("queue full")).when(taskExecutor).execute(any(Runnable.class));

        assertThatThrownBy(() -> streamService.open(preparedRun(false), "trace-test"))
                .isInstanceOf(ChatStreamCapacityException.class)
                .hasMessage("当前请求较多，请稍后重试");

        verify(persistenceService).fail(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), isNull(), eq("REQUEST_LIMITED"),
                eq("流式问答执行队列已满"), isNull(), isNull());
        verify(ragAnswerService, never()).generateStreaming(any(), any());
    }

    @Test
    void reusedRunCannotOpenAnotherGenerationStream() {
        assertThatThrownBy(() -> streamService.open(preparedRun(true), "trace-test"))
                .isInstanceOf(ChatSessionInvalidRequestException.class);

        verify(taskExecutor, never()).execute(any(Runnable.class));
        verify(ragAnswerService, never()).generateStreaming(any(), any());
    }

    private PreparedChatRunResponse preparedRun(boolean reused) {
        return new PreparedChatRunResponse(RUN_ID, SESSION_ID, USER_ID, USER_MESSAGE_ID, ASSISTANT_MESSAGE_ID,
                "如何排查？", ChatRunStatusEnum.QUEUED, reused);
    }

    private RagAnswerResult completedResult() {
        return new RagAnswerResult(RUN_ID, ASSISTANT_MESSAGE_ID, "回答", List.of(), ChatRunStatusEnum.COMPLETED,
                false, List.of(), null, new RagMetrics(1, 0, false, 10, 2L));
    }

    private static class RecordingChatMessageStreamService extends ChatMessageStreamService {

        private final List<String> eventNames;

        private RecordingChatMessageStreamService(RagAnswerService ragAnswerService,
                RagRunPersistenceService persistenceService, ChatStreamProperties properties,
                ThreadPoolTaskExecutor taskExecutor, List<String> eventNames) {
            super(ragAnswerService, persistenceService, properties, taskExecutor);
            this.eventNames = eventNames;
        }

        @Override
        void send(SseEmitter emitter, AtomicBoolean connected, String eventName, Object data) {
            eventNames.add(eventName);
        }

        @Override
        void complete(SseEmitter emitter, AtomicBoolean connected) {
            connected.set(false);
        }
    }
}
