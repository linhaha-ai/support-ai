package com.haha.supportai.assistant.agent.tool.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketStatusToolAdapter {

    private final TicketQueryService ticketQueryService;
    private final ToolCallPersistenceService persistenceService;
    private final ToolSourceFactory sourceFactory;

    public TicketStatusToolResult execute(AssistantToolContext context, GetTicketStatusToolRequest request) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("ticketNo", request == null ? null : request.getTicketNo());
        ToolCall toolCall = persistenceService.start(context, AssistantToolNameConstant.GET_TICKET_STATUS, arguments);
        try {
            TicketToolQueryResult ticket = ticketQueryService.getByTicketNoWithinSession(
                    context.getUserId(), context.getSessionId(), request == null ? null : request.getTicketNo());
            MessageSource source = sourceFactory.ticket(context, toolCall.getId(), ticket);
            TicketStatusToolResult result = new TicketStatusToolResult(source.getId(), ticket.getTicketNo(),
                    ticket.getTitle(), ticket.getDescription(), ticket.getStatus(), ticket.getOwnerName(),
                    ticket.getUpdatedAt(), ticket.getQueriedAt());
            persistenceService.succeed(context, toolCall.getId(), result, List.of(source));
            return result;
        } catch (TicketInvalidRequestException exception) {
            throw fail(context, toolCall, AssistantToolErrorCodeConstant.INVALID_REQUEST,
                    "工单编号格式不正确", exception);
        } catch (TicketNotFoundException exception) {
            throw fail(context, toolCall, AssistantToolErrorCodeConstant.RESOURCE_NOT_FOUND,
                    "工单不存在或当前会话无权查看", exception);
        } catch (AssistantToolException exception) {
            throw fail(context, toolCall, exception.getErrorCode(), exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            throw fail(context, toolCall, AssistantToolErrorCodeConstant.TOOL_UNAVAILABLE,
                    "工单查询工具暂不可用", exception);
        }
    }

    private AssistantToolException fail(AssistantToolContext context, ToolCall toolCall, String errorCode,
            String message, RuntimeException cause) {
        try {
            persistenceService.fail(context, toolCall.getId(), errorCode);
            return new AssistantToolException(errorCode, message, cause);
        } catch (RuntimeException auditException) {
            return new AssistantToolException(AssistantToolErrorCodeConstant.TOOL_AUDIT_FAILED,
                    "工具失败状态保存失败", auditException);
        }
    }
}
