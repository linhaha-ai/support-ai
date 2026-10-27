package com.haha.supportai.assistant.agent.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.assistant.agent.entity.dto.AssistantAgentRunResult;
import com.haha.supportai.assistant.agent.entity.dto.AssistantConversationContext;
import com.haha.supportai.assistant.agent.entity.dto.AssistantHistoryTurn;
import com.haha.supportai.assistant.agent.exception.AssistantAgentBudgetException;
import com.haha.supportai.assistant.agent.support.AssistantCitationParser;
import com.haha.supportai.assistant.agent.support.AssistantTicketFreshnessPolicy;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
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

    @Mock
    AssistantHistoryService historyService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SupportAssistantAnswerService service;

    @BeforeEach
    void setUp() {
        service = new SupportAssistantAnswerService(agentRunner, new AssistantCitationParser(), persistenceService,
                messageSourceRepository, sessionKnowledgeBaseRepository, knowledgeScopeAccess,
                sourceResponseFactory, new AssistantAgentProperties(), objectMapper, historyService,
                new AssistantTicketFreshnessPolicy());
        when(historyService.load(any())).thenReturn(AssistantConversationContext.empty());
    }

    @Test
    void completesCurrentTicketAnswerWithFreshToolSourceAndMetrics() throws Exception {
        PreparedChatRunResponse preparedRun = preparedRun();
        MessageSource source = ticketSource();
        String rawAnswer = "工单仍在处理中[[source:" + SOURCE_ID + "]]";
        when(agentRunner.run(eq(preparedRun), any(), eq(Set.of("T10086")), eq(observer))).thenReturn(new AssistantAgentRunResult(rawAnswer,
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
        when(agentRunner.run(eq(preparedRun), any(), anySet(), eq(observer))).thenReturn(new AssistantAgentRunResult(
                "工单仍在处理中", 1, 0, 120, Set.of()));

        assertThatThrownBy(() -> service.generateStreaming(preparedRun, observer))
                .isInstanceOfSatisfying(AssistantGenerationException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo("AGENT_REQUIRED_TOOL_NOT_CALLED"));

        verify(persistenceService).fail(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), isNull(),
                eq("AGENT_REQUIRED_TOOL_NOT_CALLED"), eq("当前工单状态必须实时查询"), isNull(), isNull());
        verify(messageSourceRepository, never()).listByMessageId(ASSISTANT_MESSAGE_ID);
    }

    @Test
    void rejectsCurrentTicketAnswerWithoutTicketCitation() throws Exception {
        PreparedChatRunResponse preparedRun = preparedRun();
        when(agentRunner.run(eq(preparedRun), any(), anySet(), eq(observer))).thenReturn(new AssistantAgentRunResult(
                "工单仍在处理中", 2, 1, 120, Set.of(AssistantToolNameConstant.GET_TICKET_STATUS)));
        when(messageSourceRepository.listByMessageId(ASSISTANT_MESSAGE_ID)).thenReturn(List.of(ticketSource()));

        assertThatThrownBy(() -> service.generateStreaming(preparedRun, observer))
                .isInstanceOfSatisfying(AssistantGenerationException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_INVALID_CITATION"));

        ArgumentCaptor<String> metricsCaptor = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).fail(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), isNull(),
                eq("AGENT_INVALID_CITATION"), eq("助手引用校验失败: REQUIRED_TICKET_NOT_CITED"), isNull(), metricsCaptor.capture());
        JsonNode metrics = objectMapper.readTree(metricsCaptor.getValue());
        assertThat(metrics.path("modelCallCount").asInt()).isEqualTo(2);
        assertThat(metrics.path("toolCallCount").asInt()).isEqualTo(1);
        assertThat(metrics.path("maxObservedContextTokens").asInt()).isEqualTo(120);
        assertThat(metrics.path("evidenceCount").asInt()).isEqualTo(1);
        assertThat(metrics.path("noEvidence").asBoolean()).isFalse();
        verify(persistenceService, never()).complete(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID),
                anyString(), eq(List.of()), isNull(), anyString());
    }

    @Test
    void rejectsInheritedTicketEvenWhenAgentClaimsItCalledTheTool() {
        MessageSource old = ticketSource();
        old.setOriginSourceId(UUID.randomUUID());
        when(agentRunner.run(any(), any(), anySet(), eq(observer))).thenReturn(new AssistantAgentRunResult(
                "当前状态[[source:" + SOURCE_ID + "]]", 1, 1, 120,
                Set.of(AssistantToolNameConstant.GET_TICKET_STATUS)));
        when(messageSourceRepository.listByMessageId(ASSISTANT_MESSAGE_ID)).thenReturn(List.of(old));

        assertThatThrownBy(() -> service.generateStreaming(preparedRun(), observer))
                .isInstanceOfSatisfying(AssistantGenerationException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_INVALID_CITATION"));
        verify(persistenceService, never()).append(any(), any(), anyString());
    }

    @Test
    void rejectsFreshLookupForWrongTicket() {
        MessageSource other = ticketSource();
        other.setSnapshot("{\"ticketNo\":\"T10087\"}");
        when(agentRunner.run(any(), any(), anySet(), eq(observer))).thenReturn(new AssistantAgentRunResult(
                "当前状态[[source:" + SOURCE_ID + "]]", 1, 1, 120,
                Set.of(AssistantToolNameConstant.GET_TICKET_STATUS)));
        when(messageSourceRepository.listByMessageId(ASSISTANT_MESSAGE_ID)).thenReturn(List.of(other));

        assertThatThrownBy(() -> service.generateStreaming(preparedRun(), observer))
                .isInstanceOfSatisfying(AssistantGenerationException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_INVALID_CITATION"));
    }

    @Test
    void unfinishedExecutionKeepsMetricsUnknownInsteadOfClaimingZeroCalls() {
        when(agentRunner.run(any(), any(), anySet(), eq(observer))).thenThrow(
                new AssistantAgentBudgetException("AGENT_TOOL_CALL_LIMIT", "助手工具调用次数已达到上限"));

        assertThatThrownBy(() -> service.generateStreaming(preparedRun(), observer))
                .isInstanceOfSatisfying(AssistantGenerationException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_TOOL_CALL_LIMIT"));

        verify(persistenceService).fail(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), isNull(),
                eq("AGENT_TOOL_CALL_LIMIT"), eq("助手工具调用次数已达到上限"), isNull(), isNull());
        verify(observer, never()).onDelta(anyString());
    }

    @Test
    void implicitFollowUpRequiresFreshLookupForAuthorizedHistoryTicket() {
        PreparedChatRunResponse prepared = new PreparedChatRunResponse(RUN_ID, SESSION_ID, USER_ID,
                USER_MESSAGE_ID, ASSISTANT_MESSAGE_ID, "现在呢？", ChatSessionModeEnum.ASSISTANT,
                ChatRunStatusEnum.QUEUED, false);
        AssistantConversationContext history = new AssistantConversationContext(List.of(
                new AssistantHistoryTurn("T10086 状态", "先前处理中", Set.of("T10086"))), List.of());
        when(historyService.load(prepared)).thenReturn(history);
        when(agentRunner.run(prepared, history, Set.of("T10086"), observer)).thenReturn(
                new AssistantAgentRunResult("还是处理中", 1, 0, 120, Set.of()));

        assertThatThrownBy(() -> service.generateStreaming(prepared, observer))
                .isInstanceOfSatisfying(AssistantGenerationException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_REQUIRED_TOOL_NOT_CALLED"));
        verify(historyService).register(prepared, history);
    }

    @Test
    void doesNotPersistOrSendAnswerWhenEvidenceIsRevokedAfterModelCall() {
        when(agentRunner.run(any(), any(), anySet(), eq(observer))).thenReturn(new AssistantAgentRunResult(
                "处理中[[source:" + SOURCE_ID + "]]", 2, 1, 120,
                Set.of(AssistantToolNameConstant.GET_TICKET_STATUS)));
        when(messageSourceRepository.listByMessageId(ASSISTANT_MESSAGE_ID)).thenReturn(List.of(ticketSource()));
        when(sessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(SESSION_ID))
                .thenReturn(List.of(KNOWLEDGE_BASE_ID));
        when(knowledgeScopeAccess.allReadable(USER_ID, List.of(KNOWLEDGE_BASE_ID))).thenReturn(true);
        doThrow(new AssistantAgentBudgetException("AGENT_SOURCE_REVOKED", "助手历史或来源权限已失效"))
                .when(historyService).ensureReadable(any());

        assertThatThrownBy(() -> service.generateStreaming(preparedRun(), observer))
                .isInstanceOfSatisfying(AssistantGenerationException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_SOURCE_REVOKED"));
        verify(persistenceService, never()).append(any(), any(), anyString());
        verify(observer, never()).onDelta(anyString());
    }

    @Test
    void rejectsUngroundedKnowledgeAnswerWithoutAnyLookup() {
        PreparedChatRunResponse prepared = knowledgeRun();
        when(agentRunner.run(eq(prepared), any(), eq(Set.of()), eq(observer))).thenReturn(
                new AssistantAgentRunResult("重试3至5次，每次等待200毫秒", 1, 0, 420, Set.of()));
        when(messageSourceRepository.listByMessageId(ASSISTANT_MESSAGE_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.generateStreaming(prepared, observer))
                .isInstanceOfSatisfying(AssistantGenerationException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_INVALID_CITATION"));

        verify(persistenceService).fail(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), isNull(),
                eq("AGENT_INVALID_CITATION"), eq("助手引用校验失败: NO_CITED_SOURCE"), isNull(), anyString());
        verify(persistenceService, never()).append(any(), any(), anyString());
        verify(persistenceService, never()).complete(any(), any(), anyString(), any(), any(), any());
        verify(observer, never()).onDelta(anyString());
    }

    @Test
    void rejectsAnswerThatIgnoresRegisteredKnowledgeSources() {
        MessageSource source = ticketSource();
        source.setSourceType(MessageSourceTypeEnum.DOCUMENT);
        when(agentRunner.run(any(), any(), eq(Set.of()), eq(observer))).thenReturn(
                new AssistantAgentRunResult("重试3至5次", 2, 1, 500, Set.of(AssistantToolNameConstant.SEARCH_KNOWLEDGE)));
        when(messageSourceRepository.listByMessageId(ASSISTANT_MESSAGE_ID)).thenReturn(List.of(source));

        assertThatThrownBy(() -> service.generateStreaming(knowledgeRun(), observer))
                .isInstanceOfSatisfying(AssistantGenerationException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_INVALID_CITATION"));

        verify(persistenceService).fail(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), isNull(),
                eq("AGENT_INVALID_CITATION"), eq("助手引用校验失败: REQUIRED_DOCUMENT_NOT_CITED"), isNull(), anyString());
        verify(persistenceService, never()).append(any(), any(), anyString());
        verify(persistenceService, never()).complete(any(), any(), anyString(), any(), any(), any());
        verify(observer, never()).onDelta(anyString());
    }

    @Test
    void replacesModelHallucinationWithFixedInsufficientAnswerAfterGenuineEmptySearch() throws Exception {
        PreparedChatRunResponse prepared = knowledgeRun();
        when(agentRunner.run(eq(prepared), any(), eq(Set.of()), eq(observer))).thenReturn(
                new AssistantAgentRunResult("凭常识建议重试3至5次", 2, 1, 500,
                        Set.of(AssistantToolNameConstant.SEARCH_KNOWLEDGE)));
        when(messageSourceRepository.listByMessageId(ASSISTANT_MESSAGE_ID)).thenReturn(List.of());
        when(sessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(SESSION_ID))
                .thenReturn(List.of(KNOWLEDGE_BASE_ID));
        when(knowledgeScopeAccess.allReadable(USER_ID, List.of(KNOWLEDGE_BASE_ID))).thenReturn(true);

        var result = service.generateStreaming(prepared, observer);

        String refusal = "资料不足：未找到可用于回答该问题的授权资料。";
        assertThat(result.getContent()).isEqualTo(refusal);
        assertThat(result.getSources()).isEmpty();
        verify(persistenceService).append(RUN_ID, ASSISTANT_MESSAGE_ID, refusal);
        verify(observer).onDelta(refusal);
        ArgumentCaptor<String> metrics = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).complete(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), eq(refusal), eq(List.of()),
                isNull(), metrics.capture());
        assertThat(objectMapper.readTree(metrics.getValue()).path("noEvidence").asBoolean()).isTrue();
        verify(historyService).ensureReadable(prepared);
    }

    @Test
    void emptySearchCannotBypassUnknownHandleValidation() {
        when(agentRunner.run(any(), any(), eq(Set.of()), eq(observer))).thenReturn(
                new AssistantAgentRunResult("伪造事实[[source:" + SOURCE_ID + "]]", 2, 1, 500,
                        Set.of(AssistantToolNameConstant.SEARCH_KNOWLEDGE)));
        when(messageSourceRepository.listByMessageId(ASSISTANT_MESSAGE_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.generateStreaming(knowledgeRun(), observer))
                .isInstanceOf(AssistantGenerationException.class);

        verify(persistenceService).fail(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), isNull(),
                eq("AGENT_INVALID_CITATION"), eq("助手引用校验失败: UNKNOWN_HANDLE"), isNull(), anyString());
        verify(persistenceService, never()).append(any(), any(), anyString());
        verify(observer, never()).onDelta(anyString());
    }

    @Test
    void rejectsTicketOnlyCitationWhenCurrentSearchReturnedDocuments() {
        MessageSource document = documentSource(UUID.randomUUID(), 2);
        when(agentRunner.run(any(), any(), eq(Set.of("T10086")), eq(observer))).thenReturn(
                new AssistantAgentRunResult("工单处理中[[source:" + SOURCE_ID + "]]，请按手册重试", 3, 2, 1200,
                        Set.of(AssistantToolNameConstant.GET_TICKET_STATUS, AssistantToolNameConstant.SEARCH_KNOWLEDGE)));
        when(messageSourceRepository.listByMessageId(ASSISTANT_MESSAGE_ID))
                .thenReturn(List.of(ticketSource(), document));

        assertThatThrownBy(() -> service.generateStreaming(preparedRun(), observer))
                .isInstanceOfSatisfying(AssistantGenerationException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_INVALID_CITATION"));

        verify(persistenceService).fail(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), isNull(),
                eq("AGENT_INVALID_CITATION"), eq("助手引用校验失败: REQUIRED_DOCUMENT_NOT_CITED"), isNull(), anyString());
        verify(persistenceService, never()).append(any(), any(), anyString());
        verify(persistenceService, never()).complete(any(), any(), anyString(), any(), any(), any());
        verify(observer, never()).onDelta(anyString());
    }

    @Test
    void historicalDocumentCitationCannotReplaceCurrentSearchDocument() {
        MessageSource historical = documentSource(UUID.randomUUID(), 2);
        historical.setOriginSourceId(UUID.randomUUID());
        MessageSource current = documentSource(UUID.randomUUID(), 3);
        when(agentRunner.run(any(), any(), eq(Set.of("T10086")), eq(observer))).thenReturn(
                new AssistantAgentRunResult("工单处理中[[source:" + SOURCE_ID + "]]，按历史说明[[source:"
                        + historical.getId() + "]]", 3, 2, 1200,
                        Set.of(AssistantToolNameConstant.GET_TICKET_STATUS, AssistantToolNameConstant.SEARCH_KNOWLEDGE)));
        when(messageSourceRepository.listByMessageId(ASSISTANT_MESSAGE_ID))
                .thenReturn(List.of(ticketSource(), historical, current));

        assertThatThrownBy(() -> service.generateStreaming(preparedRun(), observer))
                .isInstanceOf(AssistantGenerationException.class);

        verify(persistenceService).fail(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), isNull(),
                eq("AGENT_INVALID_CITATION"), eq("助手引用校验失败: REQUIRED_DOCUMENT_NOT_CITED"), isNull(), anyString());
        verify(persistenceService, never()).append(any(), any(), anyString());
        verify(observer, never()).onDelta(anyString());
    }

    @Test
    void completesCombinedAnswerCitingTicketAndOneActuallyUsedCurrentDocument() {
        MessageSource ticket = ticketSource();
        MessageSource used = documentSource(UUID.randomUUID(), 2);
        MessageSource unused = documentSource(UUID.randomUUID(), 3);
        when(agentRunner.run(any(), any(), eq(Set.of("T10086")), eq(observer))).thenReturn(
                new AssistantAgentRunResult("工单处理中[[source:" + SOURCE_ID + "]]，重试2次[[source:"
                        + used.getId() + "]]", 3, 2, 1200,
                        Set.of(AssistantToolNameConstant.GET_TICKET_STATUS, AssistantToolNameConstant.SEARCH_KNOWLEDGE)));
        when(messageSourceRepository.listByMessageId(ASSISTANT_MESSAGE_ID))
                .thenReturn(List.of(ticket, used, unused));
        allowReadableSources(ticket, used);

        var result = service.generateStreaming(preparedRun(), observer);

        assertThat(result.getContent()).isEqualTo("工单处理中[1]，重试2次[2]");
        assertThat(result.getSources()).hasSize(2);
        assertThat(result.getMetrics().getEvidenceCount()).isEqualTo(3);
        assertThat(result.getMetrics().getCitedCount()).isEqualTo(2);
        verify(persistenceService).complete(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), eq(result.getContent()),
                eq(List.of(1, 2)), isNull(), anyString());
        verify(sourceResponseFactory, never()).create(USER_ID, unused);
    }

    @ParameterizedTest
    @ValueSource(strings = { "NO_SEARCH", "INHERITED_DOCUMENT", "NO_TOOL_CALL", "EMPTY_SEARCH" })
    void doesNotRequireDocumentCitationWithoutCurrentSearchDocument(String scenario) {
        MessageSource ticket = ticketSource();
        MessageSource document = documentSource(UUID.randomUUID(), 2);
        if ("INHERITED_DOCUMENT".equals(scenario)) {
            document.setOriginSourceId(UUID.randomUUID());
        } else if ("NO_TOOL_CALL".equals(scenario)) {
            document.setToolCallId(null);
        }
        Set<String> calledTools = "NO_SEARCH".equals(scenario)
                ? Set.of(AssistantToolNameConstant.GET_TICKET_STATUS)
                : Set.of(AssistantToolNameConstant.GET_TICKET_STATUS, AssistantToolNameConstant.SEARCH_KNOWLEDGE);
        when(agentRunner.run(any(), any(), eq(Set.of("T10086")), eq(observer))).thenReturn(
                new AssistantAgentRunResult("工单处理中[[source:" + SOURCE_ID + "]]", 3, calledTools.size(), 1200,
                        calledTools));
        when(messageSourceRepository.listByMessageId(ASSISTANT_MESSAGE_ID))
                .thenReturn("EMPTY_SEARCH".equals(scenario) ? List.of(ticket) : List.of(ticket, document));
        allowReadableSources(ticket);

        var result = service.generateStreaming(preparedRun(), observer);

        assertThat(result.getContent()).isEqualTo("工单处理中[1]");
        assertThat(result.getSources()).hasSize(1);
        verify(observer).onDelta("工单处理中[1]");
    }

    private void allowReadableSources(MessageSource... sources) {
        when(sessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(SESSION_ID))
                .thenReturn(List.of(KNOWLEDGE_BASE_ID));
        when(knowledgeScopeAccess.allReadable(USER_ID, List.of(KNOWLEDGE_BASE_ID))).thenReturn(true);
        for (MessageSource source : sources) {
            when(sourceResponseFactory.create(USER_ID, source)).thenReturn(Optional.of(new RagSourceResponse(
                    source.getCitationNo(), source.getSourceType(), null, null, null, "授权来源", null,
                    true, true, null)));
        }
    }

    private MessageSource documentSource(UUID sourceId, int citationNo) {
        MessageSource source = ticketSource();
        source.setId(sourceId);
        source.setCitationNo(citationNo);
        source.setSourceType(MessageSourceTypeEnum.DOCUMENT);
        source.setSnapshot("{}");
        return source;
    }

    private PreparedChatRunResponse knowledgeRun() {
        return new PreparedChatRunResponse(RUN_ID, SESSION_ID, USER_ID, USER_MESSAGE_ID, ASSISTANT_MESSAGE_ID,
                "REG_TIMEOUT 应如何排查？重试几次、间隔多久？", ChatSessionModeEnum.ASSISTANT,
                ChatRunStatusEnum.QUEUED, false);
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
