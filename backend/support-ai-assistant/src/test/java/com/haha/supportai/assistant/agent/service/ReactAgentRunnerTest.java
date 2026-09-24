package com.haha.supportai.assistant.agent.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.assistant.agent.entity.dto.AssistantConversationContext;
import com.haha.supportai.assistant.agent.entity.dto.AssistantHistoryTurn;
import com.haha.supportai.assistant.agent.exception.AssistantAgentBudgetException;
import com.haha.supportai.assistant.agent.support.AssistantPromptFactory;
import com.haha.supportai.assistant.agent.tool.entity.dto.TicketStatusToolResult;
import com.haha.supportai.assistant.agent.tool.service.SearchKnowledgeToolAdapter;
import com.haha.supportai.assistant.agent.tool.service.TicketStatusToolAdapter;
import com.haha.supportai.conversation.config.AssistantAgentProperties;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.service.assistant.AssistantStreamObserver;
import com.haha.supportai.data.assistant.enums.TicketStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReactAgentRunnerTest {

    private final ChatModel model = mock(ChatModel.class);
    private final SearchKnowledgeToolAdapter knowledge = mock(SearchKnowledgeToolAdapter.class);
    private final TicketStatusToolAdapter tickets = mock(TicketStatusToolAdapter.class);
    private final AssistantHistoryService historyService = mock(AssistantHistoryService.class);
    private final ReactAgentRunner runner = new ReactAgentRunner(model, knowledge, tickets,
            new AssistantAgentProperties(), historyService, new AssistantPromptFactory(new ObjectMapper()));
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

    private ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }
}
