package com.haha.supportai.assistant.agent.tool.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.assistant.agent.tool.constant.AssistantToolErrorCodeConstant;
import com.haha.supportai.assistant.agent.tool.constant.AssistantToolNameConstant;
import com.haha.supportai.assistant.agent.tool.entity.dto.AssistantToolContext;
import com.haha.supportai.assistant.agent.tool.exception.AssistantToolException;
import com.haha.supportai.data.assistant.entity.po.ToolCall;
import com.haha.supportai.data.assistant.enums.ToolCallStatusEnum;
import com.haha.supportai.data.assistant.repository.ToolCallRepository;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ToolCallPersistenceService {

    private static final Set<String> ALLOWED_TOOL_NAMES = Set.of(
            AssistantToolNameConstant.SEARCH_KNOWLEDGE,
            AssistantToolNameConstant.GET_TICKET_STATUS);

    private final ToolCallRepository toolCallRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolCall start(AssistantToolContext context, String toolName, Object arguments) {
        validateContext(context);
        if (!ALLOWED_TOOL_NAMES.contains(toolName)) {
            throw new AssistantToolException(AssistantToolErrorCodeConstant.TOOL_CONTEXT_INVALID,
                    "工具不在服务端白名单中");
        }
        if (!toolCallRepository.lockStartContext(context.getRunId(), context.getSessionId(), context.getUserId(),
                context.getAssistantMessageId())) {
            throw new AssistantToolException(AssistantToolErrorCodeConstant.TOOL_CONTEXT_INVALID,
                    "工具执行上下文无效");
        }
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        ToolCall toolCall = new ToolCall(UUID.randomUUID(), context.getRunId(), null, null, toolName,
                serializeObject(arguments), null, ToolCallStatusEnum.RUNNING, null, startedAt, null);
        if (!toolCallRepository.insertStarted(toolCall, context.getSessionId(), context.getUserId(),
                context.getAssistantMessageId())) {
            throw new AssistantToolException(AssistantToolErrorCodeConstant.TOOL_CONTEXT_INVALID,
                    "工具执行上下文无效");
        }
        return toolCall;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(AssistantToolContext context, UUID toolCallId, Object result,
            List<MessageSource> sources) {
        validateContext(context);
        if (toolCallId == null || sources == null || sources.stream().anyMatch(source -> source == null)) {
            throw auditFailure("工具成功结果不完整");
        }
        List<MessageSource> immutableSources = List.copyOf(sources);
        validateSources(context, toolCallId, immutableSources);
        if (!toolCallRepository.lockExecutionContext(context.getRunId(), context.getSessionId(),
                context.getUserId(), context.getAssistantMessageId(), toolCallId)) {
            throw auditFailure("工具执行上下文已失效");
        }

        int citationNo = toolCallRepository.nextCitationNo(context.getAssistantMessageId());
        for (MessageSource source : immutableSources) {
            source.setCitationNo(citationNo++);
        }
        if (!toolCallRepository.completeSuccess(context.getRunId(), toolCallId, serializeObject(result),
                OffsetDateTime.now(ZoneOffset.UTC))) {
            throw auditFailure("工具成功状态保存失败");
        }
        if (!immutableSources.isEmpty()
                && toolCallRepository.insertSources(context.getRunId(), context.getSessionId(), context.getUserId(),
                        context.getAssistantMessageId(), immutableSources) != immutableSources.size()) {
            throw auditFailure("工具来源在返回前已失效");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(AssistantToolContext context, UUID toolCallId, String errorCode) {
        validateContext(context);
        if (toolCallId == null || errorCode == null || errorCode.isBlank() || errorCode.length() > 64) {
            throw auditFailure("工具失败状态不完整");
        }
        if (!toolCallRepository.lockExecutionContext(context.getRunId(), context.getSessionId(),
                context.getUserId(), context.getAssistantMessageId(), toolCallId)
                || !toolCallRepository.completeFailure(context.getRunId(), toolCallId, errorCode,
                        OffsetDateTime.now(ZoneOffset.UTC))) {
            throw auditFailure("工具失败状态保存失败");
        }
    }

    private void validateContext(AssistantToolContext context) {
        if (context == null || context.getRunId() == null || context.getSessionId() == null
                || context.getUserId() == null || context.getAssistantMessageId() == null) {
            throw new AssistantToolException(AssistantToolErrorCodeConstant.TOOL_CONTEXT_INVALID,
                    "工具执行上下文无效");
        }
    }

    private void validateSources(AssistantToolContext context, UUID toolCallId, List<MessageSource> sources) {
        for (MessageSource source : sources) {
            if (source.getId() == null || !context.getAssistantMessageId().equals(source.getMessageId())
                    || source.getOriginSourceId() != null || !toolCallId.equals(source.getToolCallId())
                    || source.getSourceType() == null || source.getKnowledgeBaseId() == null
                    || source.getLocator() == null || source.getSnapshot() == null
                    || source.getCreatedAt() == null) {
                throw auditFailure("工具来源映射不合法");
            }
        }
    }

    private String serializeObject(Object value) {
        JsonNode json = objectMapper.valueToTree(value);
        if (json == null || !json.isObject()) {
            throw auditFailure("工具审计数据必须是 JSON 对象");
        }
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException exception) {
            throw new AssistantToolException(AssistantToolErrorCodeConstant.TOOL_AUDIT_FAILED,
                    "工具审计数据序列化失败", exception);
        }
    }

    private AssistantToolException auditFailure(String message) {
        return new AssistantToolException(AssistantToolErrorCodeConstant.TOOL_AUDIT_FAILED, message);
    }
}
