package com.haha.supportai.assistant.agent.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.haha.supportai.assistant.agent.entity.dto.AssistantConversationContext;
import com.haha.supportai.assistant.agent.entity.dto.AssistantHistoryTurn;
import com.haha.supportai.assistant.agent.exception.AssistantAgentBudgetException;
import com.haha.supportai.assistant.agent.support.AssistantPromptFactory;
import com.haha.supportai.assistant.agent.tool.entity.dto.TicketStatusToolResult;
import com.haha.supportai.assistant.agent.tool.entity.dto.KnowledgeToolSource;
import com.haha.supportai.assistant.agent.tool.entity.dto.SearchKnowledgeToolRequest;
import com.haha.supportai.assistant.agent.tool.entity.dto.SearchKnowledgeToolResult;
import com.haha.supportai.assistant.agent.tool.service.SearchKnowledgeToolAdapter;
import com.haha.supportai.assistant.agent.tool.service.TicketStatusToolAdapter;
import com.haha.supportai.conversation.config.AssistantAgentProperties;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.exception.ChatRunCancelledException;
import com.haha.supportai.conversation.service.assistant.AssistantStreamObserver;
import com.haha.supportai.data.assistant.enums.TicketStatusEnum;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReactAgentRunnerTest {

    private final ChatModel model = mock(ChatModel.class);
    private final SearchKnowledgeToolAdapter knowledge = mock(SearchKnowledgeToolAdapter.class);
    private final TicketStatusToolAdapter tickets = mock(TicketStatusToolAdapter.class);
    private final AssistantHistoryService historyService = mock(AssistantHistoryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ReactAgentRunner runner = new ReactAgentRunner(model, knowledge, tickets,
            new AssistantAgentProperties(), historyService, new AssistantPromptFactory(objectMapper), objectMapper);
    private final PreparedChatRunResponse prepared = new PreparedChatRunResponse(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "现在呢？", ChatSessionModeEnum.ASSISTANT,
            ChatRunStatusEnum.QUEUED, false);

    @Test
    void actualReactAgentInjectsHistoryOnceAndExecutesFreshTicketTool() {
        UUID handle = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        when(tickets.execute(any(), any())).thenReturn(new TicketStatusToolResult(handle, "T10086", "工单", null,
                TicketStatusEnum.RESOLVED, null, now, now));
        AtomicInteger calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> calls.getAndIncrement() == 0
                ? response(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "getTicketStatus", "{\"ticketNo\":\"T10086\"}"))).build())
                : response(new AssistantMessage("已解决[[source:" + handle + "]]")));
        AssistantConversationContext history = new AssistantConversationContext(List.of(
                new AssistantHistoryTurn("历史唯一问题", "先前 OPEN", Set.of("T10086"))), List.of());

        var result = runner.run(prepared, history, Set.of("T10086"), AssistantStreamObserver.NONE);

        assertThat(result.getAnswer()).contains(handle.toString());
        assertThat(result.getToolCallCount()).isEqualTo(1);
        assertThat(result.getCalledToolNames()).containsExactly("getTicketStatus");
        verify(historyService, times(2)).ensureReadable(prepared);
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        for (Prompt prompt : prompts.getAllValues()) {
            assertThat(prompt.getContents().split("历史唯一问题", -1)).hasSize(2);
            assertThat(prompt.getContents()).contains("先前查询结果", "T10086", "现在呢？");
        }
    }

    @Test
    void twoSequentialToolsStillAllowFinalAnswerWithinThreeModelCalls() {
        UUID ticketHandle = UUID.randomUUID();
        UUID documentHandle = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        when(tickets.execute(any(), any())).thenReturn(new TicketStatusToolResult(ticketHandle, "T10086", "工单", null,
                TicketStatusEnum.OPEN, null, now, now));
        when(knowledge.execute(any(), any())).thenReturn(new SearchKnowledgeToolResult(List.of(
                new KnowledgeToolSource(documentHandle, "手册", null, null, "核对网络后重试"))));
        String finalAnswer = "工单待处理[[source:" + ticketHandle + "]]，核对网络后重试[[source:" + documentHandle + "]]";
        when(model.call(any(Prompt.class))).thenReturn(
                toolResponse("call-1", "getTicketStatus", "{\"ticketNo\":\"T10086\"}"),
                toolResponse("call-2", "searchKnowledge", "{\"query\":\"回调超时处理\"}"),
                response(new AssistantMessage(finalAnswer)));

        var result = runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                AssistantStreamObserver.NONE);

        assertThat(result.getAnswer()).isEqualTo(finalAnswer);
        assertThat(result.getModelCallCount()).isEqualTo(3);
        assertThat(result.getToolCallCount()).isEqualTo(2);
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(3)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(0).getContents()).doesNotContain("本轮已查询的可信来源清单");
        assertThat(prompts.getAllValues().get(2).getContents()).contains("本轮已查询的可信来源清单",
                "\"T10086\":\"[[source:" + ticketHandle + "]]\"", "最终答复必须逐条复制",
                "\"citation\":\"[[source:" + documentHandle + "]]\"", "\"title\":\"手册\"",
                "\"sourceId\":\"" + documentHandle + "\"");
        String reminder = prompts.getAllValues().get(2).getInstructions().getLast().getText();
        assertThat(reminder).doesNotContain("核对网络后重试");
        assertThat(reminder).contains("都必须由已授权工具原文明示", "资料未说明", "示例数值或自行推算");
    }

    @Test
    void twoToolsRequestedTogetherStillAllowFinalAnswerWithinTwoModelCalls() throws Exception {
        UUID ticketHandle = UUID.randomUUID();
        UUID documentHandle = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        when(tickets.execute(any(), any())).thenReturn(new TicketStatusToolResult(ticketHandle, "T10086", "工单", null,
                TicketStatusEnum.OPEN, null, now, now));
        when(knowledge.execute(any(), any())).thenReturn(new SearchKnowledgeToolResult(List.of(
                new KnowledgeToolSource(documentHandle, "手册", null, null, "核对网络后重试"))));
        String finalAnswer = "工单待处理[[source:" + ticketHandle + "]]，核对网络后重试[[source:" + documentHandle + "]]";
        when(model.call(any(Prompt.class))).thenReturn(
                response(AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "getTicketStatus", "{\"ticketNo\":\"T10086\"}"),
                        new AssistantMessage.ToolCall("call-2", "function", "searchKnowledge", "{\"query\":\"回调超时处理\"}")))
                        .build()), response(new AssistantMessage(finalAnswer)));

        var result = runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                AssistantStreamObserver.NONE);

        assertThat(result.getAnswer()).isEqualTo(finalAnswer);
        assertThat(result.getModelCallCount()).isEqualTo(2);
        assertThat(result.getToolCallCount()).isEqualTo(2);
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        Prompt finalPrompt = prompts.getAllValues().get(1);
        assertThat(finalPrompt.getContents()).contains("逐字复制对应 citation", "不得自行拼接",
                "对应段落复制 searchKnowledge 返回的文档 citation", "仅引用实际使用的来源",
                "必须分别引用工单结果和知识文档", "不得用工单引用代替文档依据");
        var toolResults = finalPrompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream()).toList();
        assertThat(toolResults).hasSize(2);
        ObjectMapper mapper = new ObjectMapper();
        for (var toolResult : toolResults) {
            var payload = mapper.readTree(toolResult.responseData());
            var source = "getTicketStatus".equals(toolResult.name()) ? payload : payload.path("sources").get(0);
            assertThat(source.path("citation").asText())
                    .isEqualTo("[[source:" + source.path("sourceHandle").asText() + "]]");
        }
    }

    @Test
    void repairsMissingTicketCitationOnceUsingSameBudgetAndActualToolResults() {
        UUID ticketHandle = UUID.randomUUID();
        UUID documentHandle = UUID.randomUUID();
        stubCombinedTools(ticketHandle, documentHandle);
        String correctedAnswer = "待处理[[source:" + ticketHandle + "]]，核对网络[[source:" + documentHandle + "]]";
        when(model.call(any(Prompt.class))).thenReturn(combinedToolResponse(),
                response(new AssistantMessage("工单待处理，核对网络[[source:" + documentHandle + "]]")),
                response(new AssistantMessage(correctedAnswer)));

        var result = runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                AssistantStreamObserver.NONE);

        assertThat(result.getAnswer()).isEqualTo(correctedAnswer);
        assertThat(result.getModelCallCount()).isEqualTo(3);
        assertThat(result.getToolCallCount()).isEqualTo(2);
        verify(tickets).execute(any(), any());
        verify(knowledge).execute(any(), any());
        verify(historyService, times(3)).ensureReadable(prepared);
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(3)).call(prompts.capture());
        Prompt correction = prompts.getAllValues().get(2);
        assertThat(correction.getContents()).contains(prepared.getQuestion(), "T10086", "OPEN", "核对网络后重试",
                "[[source:" + ticketHandle + "]]", "[[source:" + documentHandle + "]]", "不允许调用任何工具");
        assertThat(correction.getOptions()).isInstanceOfSatisfying(ToolCallingChatOptions.class, options -> {
            assertThat(options.getInternalToolExecutionEnabled()).isFalse();
            assertThat(options.getToolCallbacks()).isEmpty();
            assertThat(options.getToolNames()).isEmpty();
        });
    }

    @Test
    void doesNotAppendCitationOrRetryAgainWhenCorrectionStillOmitsIt() {
        stubCombinedTools(UUID.randomUUID(), UUID.randomUUID());
        when(model.call(any(Prompt.class))).thenReturn(combinedToolResponse(),
                response(new AssistantMessage("工单待处理，核对网络")),
                response(new AssistantMessage("纠正后仍未引用")));

        var result = runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                AssistantStreamObserver.NONE);

        // 无效答复原样交给既有严格校验器，不能在此自动补引用或无限重试。
        assertThat(result.getAnswer()).isEqualTo("纠正后仍未引用");
        assertThat(result.getModelCallCount()).isEqualTo(3);
        verify(model, times(3)).call(any(Prompt.class));
    }

    @Test
    void doesNotCorrectAfterSequentialToolsHaveConsumedModelBudget() {
        UUID ticketHandle = UUID.randomUUID();
        stubCombinedTools(ticketHandle, UUID.randomUUID());
        String incompleteAnswer = "工单待处理[[source:" + ticketHandle + "]]，核对网络";
        when(model.call(any(Prompt.class))).thenReturn(
                toolResponse("call-1", "getTicketStatus", "{\"ticketNo\":\"T10086\"}"),
                toolResponse("call-2", "searchKnowledge", "{\"query\":\"回调超时处理\"}"),
                response(new AssistantMessage(incompleteAnswer)));

        var result = runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                AssistantStreamObserver.NONE);

        assertThat(result.getAnswer()).isEqualTo(incompleteAnswer);
        assertThat(result.getModelCallCount()).isEqualTo(3);
        verify(model, times(3)).call(any(Prompt.class));
    }

    @Test
    void doesNotCorrectWhenOnlyAnotherTicketWasActuallyQueried() {
        OffsetDateTime now = OffsetDateTime.now();
        when(tickets.execute(any(), any())).thenReturn(new TicketStatusToolResult(UUID.randomUUID(), "T10087",
                "另一工单", null, TicketStatusEnum.OPEN, null, now, now));
        when(model.call(any(Prompt.class))).thenReturn(
                toolResponse("call-1", "getTicketStatus", "{\"ticketNo\":\"T10087\"}"),
                response(new AssistantMessage("工单待处理")));

        var result = runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                AssistantStreamObserver.NONE);

        assertThat(result.getModelCallCount()).isEqualTo(2);
        assertThat(result.getAnswer()).isEqualTo("工单待处理");
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    void doesNotTreatHistoricalTicketAsSuccessfulCurrentLookupForCorrection() {
        AssistantConversationContext history = new AssistantConversationContext(List.of(
                new AssistantHistoryTurn("工单 T10086", "先前处理中", Set.of("T10086"))), List.of());
        when(model.call(any(Prompt.class))).thenReturn(response(new AssistantMessage("还是处理中")));

        var result = runner.run(prepared, history, Set.of("T10086"), AssistantStreamObserver.NONE);

        assertThat(result.getModelCallCount()).isEqualTo(1);
        assertThat(result.getToolCallCount()).isZero();
        verify(model).call(any(Prompt.class));
        verify(tickets, never()).execute(any(), any());
    }

    @Test
    void rechecksSourcesBeforeSendingToolResultsForCorrection() {
        stubCombinedTools(UUID.randomUUID(), UUID.randomUUID());
        when(model.call(any(Prompt.class))).thenReturn(combinedToolResponse(),
                response(new AssistantMessage("工单待处理")));
        doNothing().doNothing()
                .doThrow(new AssistantAgentBudgetException("AGENT_SOURCE_REVOKED", "来源已失效"))
                .when(historyService).ensureReadable(prepared);

        assertThatThrownBy(() -> runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                AssistantStreamObserver.NONE)).satisfies(exception ->
                        assertThat(budgetErrorCode(exception)).isEqualTo("AGENT_SOURCE_REVOKED"));

        verify(model, times(2)).call(any(Prompt.class));
        verify(historyService, times(3)).ensureReadable(prepared);
    }

    @Test
    void correctionCannotRequestAnotherToolCall() {
        stubCombinedTools(UUID.randomUUID(), UUID.randomUUID());
        when(model.call(any(Prompt.class))).thenReturn(combinedToolResponse(),
                response(new AssistantMessage("工单待处理")),
                toolResponse("call-3", "getTicketStatus", "{\"ticketNo\":\"T10086\"}"));

        assertThatThrownBy(() -> runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                AssistantStreamObserver.NONE)).isInstanceOf(IllegalStateException.class)
                .hasMessage("引用纠正不能执行工具调用");

        verify(model, times(3)).call(any(Prompt.class));
        verify(tickets).execute(any(), any());
        verify(knowledge).execute(any(), any());
    }

    @Test
    void cancellationDuringCorrectionCannotReturnSuccessfulAnswer() {
        UUID ticketHandle = UUID.randomUUID();
        stubCombinedTools(ticketHandle, UUID.randomUUID());
        AtomicInteger calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> switch (calls.incrementAndGet()) {
            case 1 -> combinedToolResponse();
            case 2 -> response(new AssistantMessage("工单待处理"));
            default -> {
                Thread.currentThread().interrupt();
                yield response(new AssistantMessage("待处理[[source:" + ticketHandle + "]]"));
            }
        });

        try {
            assertThatThrownBy(() -> runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                    AssistantStreamObserver.NONE)).isInstanceOf(ChatRunCancelledException.class);
            verify(model, times(3)).call(any(Prompt.class));
        } finally {
            // 清除本用例模拟的中断标记，避免污染同一测试线程中的其他用例。
            Thread.interrupted();
        }
    }

    @Test
    void requiresRegisteredKnowledgeLookupInsteadOfAcceptingUngroundedFirstDraft() {
        UUID documentHandle = UUID.randomUUID();
        PreparedChatRunResponse question = knowledgeQuestion("REG_TIMEOUT 的 \"重试\" 次数？\n间隔多久？");
        when(knowledge.execute(any(), any())).thenReturn(new SearchKnowledgeToolResult(List.of(
                new KnowledgeToolSource(documentHandle, "手册", null, null, "重试 2 次，每次间隔 7 秒"))));
        String finalAnswer = "重试 2 次，每次间隔 7 秒[[source:" + documentHandle + "]]";
        when(model.call(any(Prompt.class))).thenReturn(response(new AssistantMessage("凭空猜测重试 3-5 次")),
                response(new AssistantMessage(finalAnswer)));

        var result = runner.run(question, AssistantConversationContext.empty(), Set.of(), AssistantStreamObserver.NONE);

        assertThat(result.getAnswer()).isEqualTo(finalAnswer);
        assertThat(result.getModelCallCount()).isEqualTo(2);
        assertThat(result.getToolCallCount()).isEqualTo(1);
        assertThat(result.getCalledToolNames()).containsExactly("searchKnowledge");
        ArgumentCaptor<SearchKnowledgeToolRequest> query = ArgumentCaptor.forClass(SearchKnowledgeToolRequest.class);
        verify(knowledge).execute(any(), query.capture());
        assertThat(query.getValue().getQuery()).isEqualTo(question.getQuestion());
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        Prompt groundedPrompt = prompts.getAllValues().get(1);
        assertThat(groundedPrompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData)).anyMatch(data -> data.contains("间隔 7 秒"));
        assertThat(groundedPrompt.getContents()).doesNotContain("凭空猜测重试 3-5 次");
        verify(tickets, never()).execute(any(), any());
    }

    @Test
    void doesNotRepeatForcedLookupWhenSearchReturnsNoEvidence() {
        when(knowledge.execute(any(), any())).thenReturn(new SearchKnowledgeToolResult(List.of()));
        when(model.call(any(Prompt.class))).thenReturn(response(new AssistantMessage("直接猜测")),
                response(new AssistantMessage("检索无结果后仍猜测")));

        var result = runner.run(knowledgeQuestion("REG_TIMEOUT 如何排查？"), AssistantConversationContext.empty(),
                Set.of(), AssistantStreamObserver.NONE);

        assertThat(result.getModelCallCount()).isEqualTo(2);
        assertThat(result.getToolCallCount()).isEqualTo(1);
        verify(knowledge).execute(any(), any());
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    void forcedKnowledgeToolFailureStopsBeforeAnotherModelCall() {
        when(model.call(any(Prompt.class))).thenReturn(response(new AssistantMessage("直接猜测")));
        when(knowledge.execute(any(), any())).thenThrow(new IllegalStateException("检索失败"));

        assertThatThrownBy(() -> runner.run(knowledgeQuestion("REG_TIMEOUT 如何排查？"),
                AssistantConversationContext.empty(), Set.of(), AssistantStreamObserver.NONE))
                .isInstanceOf(RuntimeException.class);

        verify(model).call(any(Prompt.class));
        verify(knowledge).execute(any(), any());
    }

    @Test
    void doesNotStartForcedLookupWithoutRemainingModelBudget() {
        AssistantAgentProperties limits = new AssistantAgentProperties();
        limits.setMaxModelCalls(1);
        ReactAgentRunner limited = new ReactAgentRunner(model, knowledge, tickets, limits, historyService,
                new AssistantPromptFactory(objectMapper), objectMapper);
        when(model.call(any(Prompt.class))).thenReturn(response(new AssistantMessage("未检索的答案")));

        var result = limited.run(knowledgeQuestion("REG_TIMEOUT 如何排查？"), AssistantConversationContext.empty(),
                Set.of(), AssistantStreamObserver.NONE);

        assertThat(result.getModelCallCount()).isEqualTo(1);
        assertThat(result.getToolCallCount()).isZero();
        verify(knowledge, never()).execute(any(), any());
    }

    @Test
    void doesNotForceKnowledgeLookupWhenAuthorizedHistoryAlreadyProvidesSource() {
        MessageSource inherited = new MessageSource();
        inherited.setId(UUID.randomUUID());
        inherited.setOriginSourceId(UUID.randomUUID());
        inherited.setSourceType(com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum.DOCUMENT);
        inherited.setSnapshot("{}");
        AssistantConversationContext history = new AssistantConversationContext(List.of(
                new AssistantHistoryTurn("先前问题", "先前回答", Set.of())), List.of(inherited));
        when(model.call(any(Prompt.class))).thenReturn(response(new AssistantMessage(
                "历史结果[[source:" + inherited.getId() + "]]")));

        var result = runner.run(knowledgeQuestion("回顾上次说明"), history, Set.of(), AssistantStreamObserver.NONE);

        assertThat(result.getModelCallCount()).isEqualTo(1);
        verify(knowledge, never()).execute(any(), any());
    }

    @Test
    void uppercaseSourcePrefixDoesNotBypassMissingCitationCorrection() {
        UUID ticketHandle = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        when(tickets.execute(any(), any())).thenReturn(new TicketStatusToolResult(ticketHandle, "T10086", "工单",
                null, TicketStatusEnum.OPEN, null, now, now));
        String corrected = "待处理[[source:" + ticketHandle + "]]";
        when(model.call(any(Prompt.class))).thenReturn(
                toolResponse("call-1", "getTicketStatus", "{\"ticketNo\":\"T10086\"}"),
                response(new AssistantMessage("待处理[[SOURCE:" + ticketHandle + "]]")),
                response(new AssistantMessage(corrected)));

        var result = runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                AssistantStreamObserver.NONE);

        assertThat(result.getAnswer()).isEqualTo(corrected);
        assertThat(result.getModelCallCount()).isEqualTo(3);
    }

    @Test
    void uppercaseUuidWithExactSourcePrefixRemainsValidWithoutCorrection() {
        UUID ticketHandle = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        when(tickets.execute(any(), any())).thenReturn(new TicketStatusToolResult(ticketHandle, "T10086", "工单",
                null, TicketStatusEnum.OPEN, null, now, now));
        String answer = "待处理[[source:" + ticketHandle.toString().toUpperCase(java.util.Locale.ROOT) + "]]";
        when(model.call(any(Prompt.class))).thenReturn(
                toolResponse("call-1", "getTicketStatus", "{\"ticketNo\":\"T10086\"}"),
                response(new AssistantMessage(answer)));

        var result = runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                AssistantStreamObserver.NONE);

        assertThat(result.getAnswer()).isEqualTo(answer);
        assertThat(result.getModelCallCount()).isEqualTo(2);
    }

    @Test
    void finalTicketReminderOnlyAppearsAfterEveryRequiredTicketWasActuallyQueried() {
        UUID firstHandle = UUID.randomUUID();
        UUID secondHandle = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        when(tickets.execute(any(), any())).thenReturn(
                new TicketStatusToolResult(firstHandle, "T10086", "第一张工单", null, TicketStatusEnum.OPEN, null, now, now),
                new TicketStatusToolResult(secondHandle, "T10087", "第二张工单", null, TicketStatusEnum.RESOLVED, null, now, now));
        when(model.call(any(Prompt.class))).thenReturn(
                toolResponse("call-1", "getTicketStatus", "{\"ticketNo\":\"T10086\"}"),
                toolResponse("call-2", "getTicketStatus", "{\"ticketNo\":\"T10087\"}"),
                response(new AssistantMessage("第一张待处理[[source:" + firstHandle
                        + "]]，第二张已解决[[source:" + secondHandle + "]]")));

        var result = runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086", "T10087"),
                AssistantStreamObserver.NONE);

        assertThat(result.getModelCallCount()).isEqualTo(3);
        assertThat(result.getToolCallCount()).isEqualTo(2);
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(3)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getContents()).doesNotContain("本轮已查询的可信来源清单");
        assertThat(prompts.getAllValues().get(2).getContents()).contains("本轮已查询的可信来源清单",
                "\"T10086\":\"[[source:" + firstHandle + "]]\"",
                "\"T10087\":\"[[source:" + secondHandle + "]]\"");
    }

    @Test
    void requiredCitationReminderIsCountedBeforeEnforcingContextBudget() {
        UUID ticketHandle = UUID.randomUUID();
        UUID documentHandle = UUID.randomUUID();
        stubCombinedTools(ticketHandle, documentHandle);
        ChatResponse lookup = combinedToolResponse();
        ChatResponse answer = response(new AssistantMessage("待处理[[source:" + ticketHandle
                + "]]，核对网络[[source:" + documentHandle + "]]"));
        when(model.call(any(Prompt.class))).thenReturn(lookup, answer);
        var result = runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                AssistantStreamObserver.NONE);
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        var encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        int firstTokens = encoding.countTokensOrdinary(prompts.getAllValues().get(0).getContents());
        int finalTokens = encoding.countTokensOrdinary(prompts.getAllValues().get(1).getContents());
        assertThat(result.getMaxObservedContextTokens()).isEqualTo(finalTokens);
        assertThat(firstTokens).isLessThan(finalTokens - 1);

        // 使用同一问题与工具结果，门限只低于包含可信提醒的最终请求，第二次调用不得发往提供方。
        AssistantAgentProperties limits = new AssistantAgentProperties();
        limits.setMaxContextTokens(finalTokens - 1);
        ReactAgentRunner limited = new ReactAgentRunner(model, knowledge, tickets, limits, historyService,
                new AssistantPromptFactory(objectMapper), objectMapper);
        when(model.call(any(Prompt.class))).thenReturn(lookup, answer);

        assertThatThrownBy(() -> limited.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                AssistantStreamObserver.NONE)).satisfies(exception ->
                        assertThat(budgetErrorCode(exception)).isEqualTo("AGENT_CONTEXT_LIMIT"));
        verify(model, times(3)).call(any(Prompt.class));
    }

    @Test
    void repairsMissingDocumentOnceEvenWhenRequiredTicketAlreadyCited() {
        UUID ticketHandle = UUID.randomUUID();
        UUID documentHandle = UUID.randomUUID();
        stubCombinedTools(ticketHandle, documentHandle);
        String corrected = "待处理[[source:" + ticketHandle + "]]，核对网络[[source:" + documentHandle + "]]";
        when(model.call(any(Prompt.class))).thenReturn(combinedToolResponse(),
                response(new AssistantMessage("待处理[[source:" + ticketHandle + "]]，核对网络")),
                response(new AssistantMessage(corrected)));

        var result = runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                AssistantStreamObserver.NONE);

        assertThat(result.getAnswer()).isEqualTo(corrected);
        assertThat(result.getModelCallCount()).isEqualTo(3);
        assertThat(result.getToolCallCount()).isEqualTo(2);
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(3)).call(prompts.capture());
        Prompt correction = prompts.getAllValues().get(2);
        assertThat(correction.getContents()).contains("本轮已查询的可信来源清单", documentHandle.toString(),
                "至少一条", "不允许调用任何工具");
        var encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        assertThat(result.getMaxObservedContextTokens()).isEqualTo(prompts.getAllValues().stream()
                .mapToInt(prompt -> encoding.countTokensOrdinary(prompt.getContents())).max().orElseThrow());
        assertThat(correction.getOptions()).isInstanceOfSatisfying(ToolCallingChatOptions.class, options -> {
            assertThat(options.getToolCallbacks()).isEmpty();
            assertThat(options.getToolNames()).isEmpty();
            assertThat(options.getInternalToolExecutionEnabled()).isFalse();
        });
        verify(tickets).execute(any(), any());
        verify(knowledge).execute(any(), any());
    }

    @Test
    void repairsKnowledgeOnlyMissingDocumentUsingOneRemainingModelCall() {
        UUID documentHandle = UUID.randomUUID();
        when(knowledge.execute(any(), any())).thenReturn(new SearchKnowledgeToolResult(List.of(
                new KnowledgeToolSource(documentHandle, "手册", null, null, "重试2次，间隔7秒"))));
        String corrected = "重试2次，间隔7秒[[source:" + documentHandle + "]]";
        when(model.call(any(Prompt.class))).thenReturn(
                toolResponse("call-1", "searchKnowledge", "{\"query\":\"REG_TIMEOUT\"}"),
                response(new AssistantMessage("重试2次，间隔7秒")), response(new AssistantMessage(corrected)));

        var result = runner.run(knowledgeQuestion("REG_TIMEOUT 重试几次？"), AssistantConversationContext.empty(),
                Set.of(), AssistantStreamObserver.NONE);

        assertThat(result.getAnswer()).isEqualTo(corrected);
        assertThat(result.getModelCallCount()).isEqualTo(3);
        assertThat(result.getToolCallCount()).isEqualTo(1);
        verify(knowledge).execute(any(), any());
        verify(tickets, never()).execute(any(), any());
    }

    @Test
    void rejectsThirdToolBeforeExecutingItsAdapter() {
        when(knowledge.execute(any(), any())).thenReturn(new SearchKnowledgeToolResult(List.of()));
        AtomicInteger calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> toolResponse(
                "call-" + calls.incrementAndGet(), "searchKnowledge", "{\"query\":\"回调超时处理\"}"));

        assertThatThrownBy(() -> runner.run(prepared, AssistantConversationContext.empty(), Set.of(),
                AssistantStreamObserver.NONE)).satisfies(exception ->
                        assertThat(budgetErrorCode(exception)).isEqualTo("AGENT_TOOL_CALL_LIMIT"));

        verify(model, times(3)).call(any(Prompt.class));
        verify(knowledge, times(2)).execute(any(), any());
    }

    @Test
    void rejectsFourthModelCallBeforeInvokingProvider() {
        // 仅此测试放宽工具次数，独立触达默认三次模型预算，生产配置不变。
        AssistantAgentProperties modelBudgetProperties = new AssistantAgentProperties();
        modelBudgetProperties.setMaxToolCalls(3);
        ReactAgentRunner modelBudgetRunner = new ReactAgentRunner(model, knowledge, tickets,
                modelBudgetProperties, historyService, new AssistantPromptFactory(objectMapper), objectMapper);
        when(knowledge.execute(any(), any())).thenReturn(new SearchKnowledgeToolResult(List.of()));
        AtomicInteger calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> toolResponse(
                "call-" + calls.incrementAndGet(), "searchKnowledge", "{\"query\":\"回调超时处理\"}"));

        assertThatThrownBy(() -> modelBudgetRunner.run(prepared, AssistantConversationContext.empty(), Set.of(),
                AssistantStreamObserver.NONE)).satisfies(exception ->
                        assertThat(budgetErrorCode(exception)).isEqualTo("AGENT_MODEL_CALL_LIMIT"));

        verify(model, times(3)).call(any(Prompt.class));
        verify(knowledge, times(3)).execute(any(), any());
    }

    @Test
    void stopsBeforeModelReceivesRevokedHistory() {
        doThrow(new AssistantAgentBudgetException("AGENT_SOURCE_REVOKED", "来源已失效"))
                .when(historyService).ensureReadable(prepared);
        assertThatThrownBy(() -> runner.run(prepared, AssistantConversationContext.empty(),
                Set.of("T10086"), AssistantStreamObserver.NONE)).isInstanceOf(RuntimeException.class);
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void toolFailureCannotBecomeSuccessfulAnswerOrTriggerAnotherModelCall() {
        when(model.call(any(Prompt.class))).thenReturn(response(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "getTicketStatus",
                        "{\"ticketNo\":\"T10086\"}"))).build()));
        when(tickets.execute(any(), any())).thenThrow(new IllegalStateException("工单查询失败"));

        assertThatThrownBy(() -> runner.run(prepared, AssistantConversationContext.empty(),
                Set.of("T10086"), AssistantStreamObserver.NONE)).isInstanceOf(RuntimeException.class);

        verify(model, times(1)).call(any(Prompt.class));
        verify(tickets, times(1)).execute(any(), any());
    }

    @Test
    void malformedToolArgumentsCannotTurnUnfinishedToolMessageIntoFinalAnswer() {
        when(model.call(any(Prompt.class))).thenReturn(response(AssistantMessage.builder()
                .content("尚未完成查询的答复")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "getTicketStatus",
                        "{invalid"))).build()));

        assertThatThrownBy(() -> runner.run(prepared, AssistantConversationContext.empty(),
                Set.of(), AssistantStreamObserver.NONE)).isInstanceOf(RuntimeException.class);

        verify(model, times(1)).call(any(Prompt.class));
        verify(tickets, never()).execute(any(), any());
    }

    @Test
    void serializesTicketToolDatesAsIsoStringsWithOriginalNanosecondPrecision() throws Exception {
        UUID ticketHandle = UUID.randomUUID();
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-09-29T14:44:28.744107Z");
        OffsetDateTime queriedAt = OffsetDateTime.parse("2026-09-30T05:35:16.609350547Z");
        when(tickets.execute(any(), any())).thenReturn(new TicketStatusToolResult(ticketHandle, "T10086", "工单",
                null, TicketStatusEnum.OPEN, null, updatedAt, queriedAt));
        when(model.call(any(Prompt.class))).thenReturn(
                toolResponse("call-1", "getTicketStatus", "{\"ticketNo\":\"T10086\"}"),
                response(new AssistantMessage("待处理[[source:" + ticketHandle + "]]")));

        runner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"), AssistantStreamObserver.NONE);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        var response = prompts.getAllValues().get(1).getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream()).findFirst().orElseThrow();
        var payload = objectMapper.readTree(response.responseData());
        assertThat(payload.path("updatedAt").isTextual()).isTrue();
        assertThat(payload.path("queriedAt").isTextual()).isTrue();
        assertThat(payload.path("updatedAt").asText()).isEqualTo(updatedAt.toString());
        assertThat(payload.path("queriedAt").asText()).isEqualTo(queriedAt.toString());
        assertThat(payload.path("citation").asText()).isEqualTo("[[source:" + ticketHandle + "]]");
        for (Prompt prompt : prompts.getAllValues()) {
            assertThat(prompt.getContents()).contains("都必须由已授权工具原文明示", "资料未说明");
        }
    }

    @Test
    void toolResultSerializationFailureStopsBeforeNextModelCall() throws Exception {
        ObjectMapper failingMapper = spy(objectMapper);
        doThrow(new JsonProcessingException("合成序列化失败") { })
                .when(failingMapper).writeValueAsString(any(TicketStatusToolResult.class));
        ReactAgentRunner failingRunner = new ReactAgentRunner(model, knowledge, tickets,
                new AssistantAgentProperties(), historyService, new AssistantPromptFactory(failingMapper),
                failingMapper);
        OffsetDateTime now = OffsetDateTime.now();
        when(tickets.execute(any(), any())).thenReturn(new TicketStatusToolResult(UUID.randomUUID(), "T10086", "工单",
                null, TicketStatusEnum.OPEN, null, now, now));
        when(model.call(any(Prompt.class))).thenReturn(
                toolResponse("call-1", "getTicketStatus", "{\"ticketNo\":\"T10086\"}"));

        assertThatThrownBy(() -> failingRunner.run(prepared, AssistantConversationContext.empty(), Set.of("T10086"),
                AssistantStreamObserver.NONE)).isInstanceOf(RuntimeException.class);

        verify(model).call(any(Prompt.class));
        verify(tickets).execute(any(), any());
    }

    private ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private PreparedChatRunResponse knowledgeQuestion(String question) {
        return new PreparedChatRunResponse(prepared.getRunId(), prepared.getSessionId(), prepared.getUserId(),
                prepared.getUserMessageId(), prepared.getAssistantMessageId(), question, ChatSessionModeEnum.ASSISTANT,
                ChatRunStatusEnum.QUEUED, false);
    }

    private void stubCombinedTools(UUID ticketHandle, UUID documentHandle) {
        OffsetDateTime now = OffsetDateTime.now();
        when(tickets.execute(any(), any())).thenReturn(new TicketStatusToolResult(ticketHandle, "T10086", "工单",
                null, TicketStatusEnum.OPEN, null, now, now));
        when(knowledge.execute(any(), any())).thenReturn(new SearchKnowledgeToolResult(List.of(
                new KnowledgeToolSource(documentHandle, "手册", null, null, "核对网络后重试"))));
    }

    private ChatResponse combinedToolResponse() {
        return response(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "getTicketStatus", "{\"ticketNo\":\"T10086\"}"),
                new AssistantMessage.ToolCall("call-2", "function", "searchKnowledge", "{\"query\":\"回调超时处理\"}")))
                .build());
    }

    private ChatResponse toolResponse(String callId, String name, String arguments) {
        return response(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(callId, "function", name, arguments))).build());
    }

    private String budgetErrorCode(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof AssistantAgentBudgetException budgetException) {
                return budgetException.getErrorCode();
            }
        }
        return null;
    }
}
