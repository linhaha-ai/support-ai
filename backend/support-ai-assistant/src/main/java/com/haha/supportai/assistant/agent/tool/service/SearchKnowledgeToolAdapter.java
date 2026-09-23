package com.haha.supportai.assistant.agent.tool.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.haha.supportai.assistant.agent.tool.constant.AssistantToolErrorCodeConstant;
import com.haha.supportai.assistant.agent.tool.constant.AssistantToolNameConstant;
import com.haha.supportai.assistant.agent.tool.entity.dto.AssistantToolContext;
import com.haha.supportai.assistant.agent.tool.entity.dto.KnowledgeToolSource;
import com.haha.supportai.assistant.agent.tool.entity.dto.SearchKnowledgeToolRequest;
import com.haha.supportai.assistant.agent.tool.entity.dto.SearchKnowledgeToolResult;
import com.haha.supportai.assistant.agent.tool.exception.AssistantToolException;
import com.haha.supportai.assistant.agent.tool.support.ToolSourceFactory;
import com.haha.supportai.data.assistant.entity.po.ToolCall;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.knowledge.entity.vo.KnowledgeRetrievalEvidenceResponse;
import com.haha.supportai.knowledge.exception.KnowledgeRetrievalInvalidRequestException;
import com.haha.supportai.knowledge.service.retrieval.KnowledgeRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SearchKnowledgeToolAdapter {

    private static final int TOOL_TOP_K = 5;
    private static final int MAX_QUERY_LENGTH = 2000;

    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final ToolCallPersistenceService persistenceService;
    private final ToolSourceFactory sourceFactory;

    public SearchKnowledgeToolResult execute(AssistantToolContext context, SearchKnowledgeToolRequest request) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", request == null ? null : request.getQuery());
        ToolCall toolCall = persistenceService.start(context, AssistantToolNameConstant.SEARCH_KNOWLEDGE, arguments);
        try {
            String query = normalizeQuery(request);
            List<KnowledgeRetrievalEvidenceResponse> evidenceList = knowledgeRetrievalService
                    .retrieveReadableWithinSession(context.getUserId(), context.getSessionId(), query, TOOL_TOP_K);
            List<MessageSource> messageSources = new ArrayList<>(evidenceList.size());
            List<KnowledgeToolSource> resultSources = new ArrayList<>(evidenceList.size());
            for (KnowledgeRetrievalEvidenceResponse evidence : evidenceList) {
                MessageSource source = sourceFactory.document(context, toolCall.getId(), evidence);
                messageSources.add(source);
                resultSources.add(new KnowledgeToolSource(source.getId(), evidence.getDocumentName(),
                        evidence.getSection(), evidence.getPageNo(), evidence.getContent()));
            }
            SearchKnowledgeToolResult result = new SearchKnowledgeToolResult(List.copyOf(resultSources));
            persistenceService.succeed(context, toolCall.getId(), result, messageSources);
            return result;
        } catch (KnowledgeRetrievalInvalidRequestException exception) {
            throw fail(context, toolCall, AssistantToolErrorCodeConstant.INVALID_REQUEST,
                    "知识检索参数不合法", exception);
        } catch (AssistantToolException exception) {
            throw fail(context, toolCall, exception.getErrorCode(), exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            throw fail(context, toolCall, AssistantToolErrorCodeConstant.TOOL_UNAVAILABLE,
                    "知识检索工具暂不可用", exception);
        }
    }

    private String normalizeQuery(SearchKnowledgeToolRequest request) {
        if (request == null || request.getQuery() == null) {
            throw new KnowledgeRetrievalInvalidRequestException();
        }
        String query = request.getQuery().trim();
        if (query.isEmpty() || query.length() > MAX_QUERY_LENGTH) {
            throw new KnowledgeRetrievalInvalidRequestException();
        }
        return query;
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
