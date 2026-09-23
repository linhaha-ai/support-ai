package com.haha.supportai.assistant.agent.tool.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.assistant.agent.tool.constant.AssistantToolErrorCodeConstant;
import com.haha.supportai.assistant.agent.tool.constant.AssistantToolNameConstant;
import com.haha.supportai.assistant.agent.tool.entity.dto.AssistantToolContext;
import com.haha.supportai.assistant.agent.tool.entity.dto.GetTicketStatusToolRequest;
import com.haha.supportai.assistant.agent.tool.entity.dto.TicketStatusToolResult;
import com.haha.supportai.assistant.agent.tool.exception.AssistantToolException;
import com.haha.supportai.assistant.agent.tool.support.ToolSourceFactory;
import com.haha.supportai.assistant.ticket.entity.dto.TicketToolQueryResult;
import com.haha.supportai.assistant.ticket.exception.TicketInvalidRequestException;
import com.haha.supportai.assistant.ticket.exception.TicketNotFoundException;
import com.haha.supportai.assistant.ticket.service.TicketQueryService;
import com.haha.supportai.data.assistant.entity.po.ToolCall;
import com.haha.supportai.data.assistant.enums.TicketStatusEnum;
import com.haha.supportai.data.assistant.enums.ToolCallStatusEnum;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketStatusToolAdapterTest {

    private static final UUID RUN_ID = UUID.fromString("26100000-0000-4000-8000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("26100000-0000-4000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("26100000-0000-4000-8000-000000000003");
    private static final UUID MESSAGE_ID = UUID.fromString("26100000-0000-4000-8000-000000000004");
    private static final UUID TOOL_CALL_ID = UUID.fromString("26100000-0000-4000-8000-000000000005");
    private static final UUID SOURCE_ID = UUID.fromString("26100000-0000-4000-8000-000000000006");

    @Mock
    TicketQueryService ticketQueryService;

    @Mock
    ToolCallPersistenceService persistenceService;

    @Mock
    ToolSourceFactory sourceFactory;

    TicketStatusToolAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new TicketStatusToolAdapter(ticketQueryService, persistenceService, sourceFactory);
    }

    @Test
    void returnsFreshTicketResultWithServerGeneratedSourceHandle() {
        AssistantToolContext context = context();
        ToolCall toolCall = toolCall();
        TicketToolQueryResult ticket = ticket();
        MessageSource source = ticketSource();
        when(persistenceService.start(eq(context), eq(AssistantToolNameConstant.GET_TICKET_STATUS), any()))
                .thenReturn(toolCall);
        when(ticketQueryService.getByTicketNoWithinSession(USER_ID, SESSION_ID, "t10086")).thenReturn(ticket);
        when(sourceFactory.ticket(context, TOOL_CALL_ID, ticket)).thenReturn(source);

        TicketStatusToolResult result = adapter.execute(context, new GetTicketStatusToolRequest("t10086"));

        assertThat(result.getSourceHandle()).isEqualTo(SOURCE_ID);
        assertThat(result.getTicketNo()).isEqualTo("T10086");
        assertThat(result.getStatus()).isEqualTo(TicketStatusEnum.IN_PROGRESS);
        verify(persistenceService).succeed(context, TOOL_CALL_ID, result, List.of(source));
    }

    @Test
    void recordsInvalidTicketNumberAsFailedCall() {
        AssistantToolContext context = context();
        when(persistenceService.start(eq(context), eq(AssistantToolNameConstant.GET_TICKET_STATUS), any()))
                .thenReturn(toolCall());
        when(ticketQueryService.getByTicketNoWithinSession(USER_ID, SESSION_ID, "T/10086"))
                .thenThrow(new TicketInvalidRequestException());

        assertThatThrownBy(() -> adapter.execute(context, new GetTicketStatusToolRequest("T/10086")))
                .isInstanceOfSatisfying(AssistantToolException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AssistantToolErrorCodeConstant.INVALID_REQUEST));

        verify(persistenceService).fail(context, TOOL_CALL_ID, AssistantToolErrorCodeConstant.INVALID_REQUEST);
    }

    @Test
    void missingAndOutOfScopeTicketsUseTheSameFailureCode() {
        AssistantToolContext context = context();
        when(persistenceService.start(eq(context), eq(AssistantToolNameConstant.GET_TICKET_STATUS), any()))
                .thenReturn(toolCall());
        when(ticketQueryService.getByTicketNoWithinSession(USER_ID, SESSION_ID, "T99999"))
                .thenThrow(new TicketNotFoundException());

        assertThatThrownBy(() -> adapter.execute(context, new GetTicketStatusToolRequest("T99999")))
                .isInstanceOfSatisfying(AssistantToolException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AssistantToolErrorCodeConstant.RESOURCE_NOT_FOUND));

        verify(persistenceService).fail(context, TOOL_CALL_ID, AssistantToolErrorCodeConstant.RESOURCE_NOT_FOUND);
        verify(persistenceService, never()).succeed(any(), any(), any(), any());
    }

    private AssistantToolContext context() {
        return new AssistantToolContext(RUN_ID, SESSION_ID, USER_ID, MESSAGE_ID);
    }

    private ToolCall toolCall() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-23T10:00:00Z");
        return new ToolCall(TOOL_CALL_ID, RUN_ID, 1, null, AssistantToolNameConstant.GET_TICKET_STATUS,
                "{}", null, ToolCallStatusEnum.RUNNING, null, now, null);
    }

    private TicketToolQueryResult ticket() {
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-09-23T09:00:00Z");
        return new TicketToolQueryResult(UUID.randomUUID(), UUID.randomUUID(), "T10086", "订单状态异常",
                "订单关闭结果未同步", TicketStatusEnum.IN_PROGRESS, "支持工程师1", updatedAt,
                updatedAt.plusMinutes(2));
    }

    private MessageSource ticketSource() {
        return new MessageSource(SOURCE_ID, MESSAGE_ID, null, 0, false, MessageSourceTypeEnum.TICKET,
                UUID.randomUUID(), null, UUID.randomUUID(), TOOL_CALL_ID, "{}", "{}", OffsetDateTime.now());
    }
}
