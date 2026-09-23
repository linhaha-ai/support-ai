package com.haha.supportai.assistant.agent.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.assistant.agent.entity.dto.AssistantAgentRunResult;
import com.haha.supportai.assistant.agent.support.AssistantCitationParser;
import com.haha.supportai.assistant.agent.tool.constant.AssistantToolNameConstant;
import com.haha.supportai.conversation.config.AssistantAgentProperties;
import com.haha.supportai.conversation.entity.dto.AssistantAnswerResult;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.vo.RagSourceResponse;
import com.haha.supportai.conversation.exception.AssistantGenerationException;
import com.haha.supportai.conversation.service.assistant.AssistantStreamObserver;
import com.haha.supportai.conversation.service.rag.RagRunPersistenceService;
import com.haha.supportai.conversation.support.StoredMessageSourceResponseFactory;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import com.haha.supportai.knowledge.contract.KnowledgeScopeAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportAssistantAnswerServiceTest {

    private static final UUID RUN_ID = UUID.fromString("27200000-0000-4000-8000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("27200000-0000-4000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("27200000-0000-4000-8000-000000000003");
    private static final UUID USER_MESSAGE_ID = UUID.fromString("27200000-0000-4000-8000-000000000004");
    private static final UUID ASSISTANT_MESSAGE_ID = UUID.fromString("27200000-0000-4000-8000-000000000005");
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("27200000-0000-4000-8000-000000000006");
    private static final UUID SOURCE_ID = UUID.fromString("27200000-0000-4000-8000-000000000007");

    @Mock
    AssistantAgentRunner agentRunner;

    @Mock
    RagRunPersistenceService persistenceService;

    @Mock
    MessageSourceRepository messageSourceRepository;

    @Mock
    ChatSessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;

    @Mock
    KnowledgeScopeAccess knowledgeScopeAccess;

    @Mock
    StoredMessageSourceResponseFactory sourceResponseFactory;

    @Mock
    AssistantStreamObserver observer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SupportAssistantAnswerService service;

    @BeforeEach
    void setUp() {
        service = new SupportAssistantAnswerService(agentRunner, new AssistantCitationParser(), persistenceService,
                messageSourceRepository, sessionKnowledgeBaseRepository, knowledgeScopeAccess,
                sourceResponseFactory, new AssistantAgentProperties(), objectMapper);
    }

    @Test
    void completesCurrentTicketAnswerWithFreshToolSourceAndMetrics() throws Exception {
        PreparedChatRunResponse preparedRun = preparedRun();
        MessageSource source = ticketSource();
        String rawAnswer = "工单仍在处理中[[source:" + SOURCE_ID + "]]";
        when(agentRunner.run(preparedRun, observer)).thenReturn(new AssistantAgentRunResult(rawAnswer,
                2, 1, 320, Set.of(AssistantToolNameConstant.GET_TICKET_STATUS)));
        when(messageSourceRepository.listByMessageId(ASSISTANT_MESSAGE_ID)).thenReturn(List.of(source));
        when(sessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(SESSION_ID))
                .thenReturn(List.of(KNOWLEDGE_BASE_ID));
        when(knowledgeScopeAccess.allReadable(USER_ID, List.of(KNOWLEDGE_BASE_ID))).thenReturn(true);
        RagSourceResponse response = new RagSourceResponse(1, MessageSourceTypeEnum.TICKET, null, null, null,
                "T10086 工单", null, true, true, null, "T10086", "IN_PROGRESS",
                OffsetDateTime.parse("2026-09-23T09:00:00Z"));
        when(sourceResponseFactory.create(USER_ID, source)).thenReturn(Optional.of(response));

        AssistantAnswerResult result = service.generateStreaming(preparedRun, observer);

        assertThat(result.getContent()).isEqualTo("工单仍在处理中[1]");
        assertThat(result.getSources()).containsExactly(response);
        assertThat(result.getMetrics().getModelCallCount()).isEqualTo(2);
        assertThat(result.getMetrics().getToolCallCount()).isEqualTo(1);
        verify(observer).onStarted();
        verify(observer).onDelta("工单仍在处理中[1]");
        verify(persistenceService).append(RUN_ID, ASSISTANT_MESSAGE_ID, "工单仍在处理中[1]");
        ArgumentCaptor<String> metricsCaptor = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).complete(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID),
                eq("工单仍在处理中[1]"), eq(List.of(1)), isNull(), metricsCaptor.capture());
        JsonNode metrics = objectMapper.readTree(metricsCaptor.getValue());
        assertThat(metrics.path("modelCallCount").asInt()).isEqualTo(2);
        assertThat(metrics.path("toolCallCount").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsCurrentTicketAnswerWithoutFreshLookup() {
        PreparedChatRunResponse preparedRun = preparedRun();
        when(agentRunner.run(preparedRun, observer)).thenReturn(new AssistantAgentRunResult(
                "工单仍在处理中", 1, 0, 120, Set.of()));

        assertThatThrownBy(() -> service.generateStreaming(preparedRun, observer))
                .isInstanceOfSatisfying(AssistantGenerationException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo("AGENT_REQUIRED_TOOL_NOT_CALLED"));

        verify(persistenceService).fail(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), isNull(),
                eq("AGENT_REQUIRED_TOOL_NOT_CALLED"), eq("当前工单状态必须实时查询"), isNull(), anyString());
        verify(messageSourceRepository, never()).listByMessageId(ASSISTANT_MESSAGE_ID);
    }

    @Test
    void rejectsCurrentTicketAnswerWithoutTicketCitation() {
        PreparedChatRunResponse preparedRun = preparedRun();
        when(agentRunner.run(preparedRun, observer)).thenReturn(new AssistantAgentRunResult(
                "工单仍在处理中", 2, 1, 120, Set.of(AssistantToolNameConstant.GET_TICKET_STATUS)));
        when(messageSourceRepository.listByMessageId(ASSISTANT_MESSAGE_ID)).thenReturn(List.of(ticketSource()));

        assertThatThrownBy(() -> service.generateStreaming(preparedRun, observer))
                .isInstanceOfSatisfying(AssistantGenerationException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_INVALID_CITATION"));

        verify(persistenceService).fail(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), isNull(),
                eq("AGENT_INVALID_CITATION"), eq("模型返回了未知或不可信的来源引用"), isNull(), anyString());
        verify(persistenceService, never()).complete(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID),
                anyString(), eq(List.of()), isNull(), anyString());
    }

    private PreparedChatRunResponse preparedRun() {
        return new PreparedChatRunResponse(RUN_ID, SESSION_ID, USER_ID, USER_MESSAGE_ID, ASSISTANT_MESSAGE_ID,
                "查询工单 T10086 当前状态", ChatSessionModeEnum.ASSISTANT, ChatRunStatusEnum.QUEUED, false);
    }

    private MessageSource ticketSource() {
        return new MessageSource(SOURCE_ID, ASSISTANT_MESSAGE_ID, null, 1, false,
                MessageSourceTypeEnum.TICKET, KNOWLEDGE_BASE_ID, null, UUID.randomUUID(), UUID.randomUUID(),
                "{\"ticketNo\":\"T10086\"}", "{\"ticketNo\":\"T10086\"}", OffsetDateTime.now());
    }
}
