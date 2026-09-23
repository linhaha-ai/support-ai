package com.haha.supportai.conversation.service.stream;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.haha.supportai.conversation.config.ChatStreamProperties;
import com.haha.supportai.conversation.config.AssistantAgentProperties;
import com.haha.supportai.conversation.entity.dto.AgentMetrics;
import com.haha.supportai.conversation.entity.dto.AssistantAnswerResult;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.dto.RagAnswerResult;
import com.haha.supportai.conversation.entity.dto.RagMetrics;
import com.haha.supportai.conversation.entity.dto.RetrievalQueryDiagnostics;
import com.haha.supportai.conversation.entity.vo.ChatStreamDoneResponse;
import com.haha.supportai.conversation.entity.vo.ChatStreamErrorResponse;
import com.haha.supportai.conversation.entity.vo.ChatStreamToolResponse;
import com.haha.supportai.conversation.entity.vo.RagSourceResponse;
import com.haha.supportai.conversation.enums.RetrievalQueryRewriteStatusEnum;
import com.haha.supportai.conversation.enums.AssistantToolEventStatusEnum;
import com.haha.supportai.conversation.exception.ChatSessionInvalidRequestException;
import com.haha.supportai.conversation.exception.ChatStreamCapacityException;
import com.haha.supportai.conversation.exception.RagGenerationException;
import com.haha.supportai.conversation.service.rag.RagAnswerService;
import com.haha.supportai.conversation.service.rag.RagRunPersistenceService;
import com.haha.supportai.conversation.service.rag.RagStreamObserver;
import com.haha.supportai.conversation.service.run.ChatRunExecutionRegistry;
import com.haha.supportai.conversation.service.assistant.AssistantAnswerService;
import com.haha.supportai.conversation.service.assistant.AssistantStreamObserver;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.knowledge.contract.KnowledgeScopeAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatMessageStreamServiceTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID USER_MESSAGE_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID ASSISTANT_MESSAGE_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");

    @Mock
    RagAnswerService ragAnswerService;

    @Mock
    AssistantAnswerService assistantAnswerService;

    @Mock
    RagRunPersistenceService persistenceService;

    @Mock
    ChatSessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;

    @Mock
    KnowledgeScopeAccess knowledgeScopeAccess;

    @Mock
    ThreadPoolTaskExecutor taskExecutor;

    @Mock
    ThreadPoolTaskScheduler timeoutScheduler;

    @Mock
    ScheduledFuture<?> timeoutFuture;

    private ChatRunExecutionRegistry executionRegistry;

    private ChatMessageStreamService streamService;

    private List<String> eventNames;

    private List<Object> eventPayloads;

    @BeforeEach
    void setUp() {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setTimeout(Duration.ofSeconds(30));
        properties.setExecutionTimeout(Duration.ofSeconds(20));
        AssistantAgentProperties assistantProperties = new AssistantAgentProperties();
        lenient().doReturn(timeoutFuture).when(timeoutScheduler)
                .schedule(any(Runnable.class), any(Instant.class));
        eventNames = new ArrayList<>();
        eventPayloads = new ArrayList<>();
        executionRegistry = new ChatRunExecutionRegistry();
        streamService = new RecordingChatMessageStreamService(ragAnswerService, assistantAnswerService,
                persistenceService, sessionKnowledgeBaseRepository, knowledgeScopeAccess, properties,
                assistantProperties, taskExecutor, timeoutScheduler, executionRegistry, eventNames, eventPayloads);
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
        verify(timeoutFuture).cancel(false);
    }

    @Test
    void assistantRunEmitsWhitelistedToolProgressAndReusesTerminalProtocol() {
        PreparedChatRunResponse preparedRun = new PreparedChatRunResponse(RUN_ID, SESSION_ID, USER_ID,
                USER_MESSAGE_ID, ASSISTANT_MESSAGE_ID, "T10086 当前状态", ChatSessionModeEnum.ASSISTANT,
                ChatRunStatusEnum.QUEUED, false);
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        AgentMetrics metrics = new AgentMetrics(1, 1, false, 12, 8L, 2, 1, 300,
                2, 3);
        doAnswer(invocation -> {
            AssistantStreamObserver observer = invocation.getArgument(1);
            observer.onStarted();
            observer.onTool("getTicketStatus", AssistantToolEventStatusEnum.STARTED);
            observer.onTool("getTicketStatus", AssistantToolEventStatusEnum.SUCCEEDED);
            observer.onDelta("工单处理中。[1]");
            return new AssistantAnswerResult(RUN_ID, ASSISTANT_MESSAGE_ID, "工单处理中。[1]",
                    ChatRunStatusEnum.COMPLETED, List.of(), metrics);
        }).when(assistantAnswerService).generateStreaming(eq(preparedRun), any(AssistantStreamObserver.class));

        streamService.open(preparedRun, "trace-agent");
        verify(taskExecutor).execute(taskCaptor.capture());
        taskCaptor.getValue().run();

        assertThat(eventNames).containsExactly("start", "tool", "tool", "delta", "sources", "done");
        assertThat(eventPayloads).filteredOn(ChatStreamToolResponse.class::isInstance)
                .extracting(payload -> ((ChatStreamToolResponse) payload).getStatus())
                .containsExactly(AssistantToolEventStatusEnum.STARTED, AssistantToolEventStatusEnum.SUCCEEDED);
        verify(ragAnswerService, never()).generateStreaming(any(), any());
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
        verify(timeoutFuture).cancel(false);
        verify(ragAnswerService, never()).generateStreaming(any(), any());
    }

    @Test
    void reusedRunCannotOpenAnotherGenerationStream() {
        assertThatThrownBy(() -> streamService.open(preparedRun(true), "trace-test"))
                .isInstanceOf(ChatSessionInvalidRequestException.class);

        verify(taskExecutor, never()).execute(any(Runnable.class));
        verify(ragAnswerService, never()).generateStreaming(any(), any());
    }

    @Test
    void cancelledRegisteredTaskSendsOnlyCancelledTerminalEvent() {
        PreparedChatRunResponse preparedRun = preparedRun(false);
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);

        streamService.open(preparedRun, "trace-test");
        verify(taskExecutor).execute(taskCaptor.capture());

        assertThat(executionRegistry.cancel(RUN_ID)).isTrue();
        taskCaptor.getValue().run();

        assertThat(eventNames).containsExactly("start", "error");
        verify(timeoutFuture).cancel(false);
        verify(ragAnswerService, never()).generateStreaming(any(), any());
        verify(persistenceService, never()).fail(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void totalTimeoutPersistsFailureBeforeInterruptingExecution() {
        PreparedChatRunResponse preparedRun = preparedRun(false);
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> timeoutCaptor = ArgumentCaptor.forClass(Runnable.class);

        streamService.open(preparedRun, "trace-test");
        verify(taskExecutor).execute(taskCaptor.capture());
        verify(timeoutScheduler).schedule(timeoutCaptor.capture(), any(Instant.class));

        timeoutCaptor.getValue().run();
        taskCaptor.getValue().run();

        verify(persistenceService).fail(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), isNull(), eq("MODEL_TIMEOUT"),
                eq("问答生成超过总执行时间"), isNull(), isNull());
        assertThat(eventNames).containsExactly("start", "error");
        verify(ragAnswerService, never()).generateStreaming(any(), any());
    }

    @Test
    void runningTaskTimeoutAlwaysSendsTimeoutTerminal() throws InterruptedException {
        PreparedChatRunResponse preparedRun = preparedRun(false);
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> timeoutCaptor = ArgumentCaptor.forClass(Runnable.class);
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch waitForInterrupt = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.<RagStreamObserver>getArgument(1).onStarted();
            modelStarted.countDown();
            try {
                waitForInterrupt.await();
                return completedResult();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RagGenerationException("MODEL_TIMEOUT", "回答生成超时，请稍后重试");
            }
        }).when(ragAnswerService).generateStreaming(eq(preparedRun), any(RagStreamObserver.class));

        streamService.open(preparedRun, "trace-test");
        verify(taskExecutor).execute(taskCaptor.capture());
        verify(timeoutScheduler).schedule(timeoutCaptor.capture(), any(Instant.class));
        Thread worker = new Thread(taskCaptor.getValue());
        worker.start();
        assertThat(modelStarted.await(2, TimeUnit.SECONDS)).isTrue();

        timeoutCaptor.getValue().run();
        worker.join(2_000L);

        assertThat(worker.isAlive()).isFalse();
        assertThat(eventNames).containsExactly("start", "error");
        assertThat(eventPayloads).filteredOn(ChatStreamErrorResponse.class::isInstance)
                .extracting(payload -> ((ChatStreamErrorResponse) payload).getCode())
                .containsExactly("MODEL_TIMEOUT");
    }

    @Test
    void revalidatesCachedSourcesImmediatelyBeforeDelivery() {
        PreparedChatRunResponse preparedRun = preparedRun(false);
        RagSourceResponse cachedSource = cachedSource();
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        doAnswer(invocation -> {
            invocation.<RagStreamObserver>getArgument(1).onStarted();
            return completedResult(List.of(cachedSource));
        })
                .when(ragAnswerService).generateStreaming(eq(preparedRun), any(RagStreamObserver.class));
        doAnswer(invocation -> List.of())
                .when(ragAnswerService).revalidateReadableSources(USER_ID, List.of(cachedSource));

        streamService.open(preparedRun, "trace-test");
        verify(taskExecutor).execute(taskCaptor.capture());
        taskCaptor.getValue().run();

        verify(ragAnswerService).revalidateReadableSources(USER_ID, List.of(cachedSource));
        assertThat(eventNames).containsExactly("start", "sources", "done");
    }

    @Test
    void revokedScopeRedactsQueryRewriteDiagnosticsBeforeDone() {
        PreparedChatRunResponse preparedRun = preparedRun(false);
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        RetrievalQueryDiagnostics diagnostics = new RetrievalQueryDiagnostics("这个错误怎么解决",
                "ERR-1001 的处理步骤", RetrievalQueryRewriteStatusEnum.REWRITTEN);
        RagMetrics metrics = new RagMetrics(1, 0, false, 10, 2L, diagnostics);
        doAnswer(invocation -> {
            invocation.<RagStreamObserver>getArgument(1).onStarted();
            return completedResult(List.of(), metrics);
        }).when(ragAnswerService).generateStreaming(eq(preparedRun), any(RagStreamObserver.class));
        doAnswer(invocation -> List.of(KNOWLEDGE_BASE_ID))
                .when(sessionKnowledgeBaseRepository).listKnowledgeBaseIdsBySessionId(SESSION_ID);
        doAnswer(invocation -> false)
                .when(knowledgeScopeAccess).allReadable(USER_ID, List.of(KNOWLEDGE_BASE_ID));

        streamService.open(preparedRun, "trace-test");
        verify(taskExecutor).execute(taskCaptor.capture());
        taskCaptor.getValue().run();

        ChatStreamDoneResponse done = eventPayloads.stream()
                .filter(ChatStreamDoneResponse.class::isInstance)
                .map(ChatStreamDoneResponse.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(done.getMetrics().getQueryRewrite().getStatus())
                .isEqualTo(RetrievalQueryRewriteStatusEnum.REWRITTEN);
        assertThat(done.getMetrics().getQueryRewrite().getOriginalQuestion()).isNull();
        assertThat(done.getMetrics().getQueryRewrite().getRetrievalQuestion()).isNull();
        verify(knowledgeScopeAccess).allReadable(USER_ID, List.of(KNOWLEDGE_BASE_ID));
    }

    private PreparedChatRunResponse preparedRun(boolean reused) {
        return new PreparedChatRunResponse(RUN_ID, SESSION_ID, USER_ID, USER_MESSAGE_ID, ASSISTANT_MESSAGE_ID,
                "如何排查？", ChatRunStatusEnum.QUEUED, reused);
    }

    private RagAnswerResult completedResult() {
        return completedResult(List.of());
    }

    private RagAnswerResult completedResult(List<RagSourceResponse> sources) {
        return completedResult(sources, new RagMetrics(1, 0, false, 10, 2L));
    }

    private RagAnswerResult completedResult(List<RagSourceResponse> sources, RagMetrics metrics) {
        return new RagAnswerResult(RUN_ID, ASSISTANT_MESSAGE_ID, "回答", List.of(), ChatRunStatusEnum.COMPLETED,
                false, sources, null, metrics);
    }

    private RagSourceResponse cachedSource() {
        UUID documentId = UUID.fromString("60000000-0000-4000-8000-000000000001");
        UUID versionId = UUID.fromString("70000000-0000-4000-8000-000000000001");
        UUID chunkId = UUID.fromString("80000000-0000-4000-8000-000000000001");
        return new RagSourceResponse(1, MessageSourceTypeEnum.DOCUMENT, documentId, versionId, chunkId,
                "排障手册", "超时处理", true, true, "/api/chunks/" + chunkId + "/source");
    }

    private static class RecordingChatMessageStreamService extends ChatMessageStreamService {

        private final List<String> eventNames;

        private final List<Object> eventPayloads;

        private RecordingChatMessageStreamService(RagAnswerService ragAnswerService,
                AssistantAnswerService assistantAnswerService,
                RagRunPersistenceService persistenceService,
                ChatSessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                KnowledgeScopeAccess knowledgeScopeAccess, ChatStreamProperties properties,
                AssistantAgentProperties assistantAgentProperties,
                ThreadPoolTaskExecutor taskExecutor, ThreadPoolTaskScheduler timeoutScheduler,
                ChatRunExecutionRegistry executionRegistry,
                List<String> eventNames, List<Object> eventPayloads) {
            super(ragAnswerService, assistantAnswerService, persistenceService, sessionKnowledgeBaseRepository,
                    knowledgeScopeAccess, properties, assistantAgentProperties, taskExecutor, timeoutScheduler,
                    executionRegistry);
            this.eventNames = eventNames;
            this.eventPayloads = eventPayloads;
        }

        @Override
        void send(SseEmitter emitter, AtomicBoolean connected, String eventName, Object data) {
            eventNames.add(eventName);
            eventPayloads.add(data);
        }

        @Override
        void complete(SseEmitter emitter, AtomicBoolean connected) {
            connected.set(false);
        }
    }
}
