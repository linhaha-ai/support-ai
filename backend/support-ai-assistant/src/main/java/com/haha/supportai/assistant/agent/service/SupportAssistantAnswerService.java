package com.haha.supportai.assistant.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.assistant.agent.entity.dto.AssistantAgentRunResult;
import com.haha.supportai.assistant.agent.entity.dto.AssistantCitationResult;
import com.haha.supportai.assistant.agent.entity.dto.AssistantConversationContext;
import com.haha.supportai.assistant.agent.exception.AssistantAgentBudgetException;
import com.haha.supportai.assistant.agent.exception.AssistantCitationException;
import com.haha.supportai.assistant.agent.support.AssistantCitationParser;
import com.haha.supportai.assistant.agent.support.AssistantTicketFreshnessPolicy;
import com.haha.supportai.assistant.agent.tool.constant.AssistantToolNameConstant;
import com.haha.supportai.assistant.agent.tool.exception.AssistantToolException;
import com.haha.supportai.conversation.config.AssistantAgentProperties;
import com.haha.supportai.conversation.entity.dto.AgentMetrics;
import com.haha.supportai.conversation.entity.dto.AssistantAnswerResult;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.vo.RagSourceResponse;
import com.haha.supportai.conversation.exception.AssistantGenerationException;
import com.haha.supportai.conversation.exception.ChatRunCancelledException;
import com.haha.supportai.conversation.exception.ChatSessionInvalidRequestException;
import com.haha.supportai.conversation.exception.RagGenerationException;
import com.haha.supportai.conversation.service.assistant.AssistantAnswerService;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SupportAssistantAnswerService implements AssistantAnswerService {

    private static final String PUBLIC_FAILURE_MESSAGE = "智能助手本次未能完成，请稍后重试";

    private final AssistantAgentRunner agentRunner;
    private final AssistantCitationParser citationParser;
    private final RagRunPersistenceService persistenceService;
    private final MessageSourceRepository messageSourceRepository;
    private final ChatSessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    private final KnowledgeScopeAccess knowledgeScopeAccess;
    private final StoredMessageSourceResponseFactory sourceResponseFactory;
    private final AssistantAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final AssistantHistoryService historyService;
    private final AssistantTicketFreshnessPolicy freshnessPolicy;

    @Override
    public AssistantAnswerResult generateStreaming(PreparedChatRunResponse preparedRun,
            AssistantStreamObserver observer) {
        validate(preparedRun);
        AssistantStreamObserver safeObserver = observer == null ? AssistantStreamObserver.NONE : observer;
        long startedNanos = System.nanoTime();
        AssistantAgentRunResult runResult = null;
        List<MessageSource> registeredSources = null;
        try {
            persistenceService.start(preparedRun);
            notifyStarted(safeObserver);
            AssistantConversationContext history = historyService.load(preparedRun);
            Set<String> requiredTickets = freshnessPolicy.requiredTickets(preparedRun.getQuestion(), history);
            historyService.register(preparedRun, history);
            runResult = agentRunner.run(preparedRun, history, requiredTickets, safeObserver);
            ensureRequiredTicketLookup(requiredTickets, runResult.getCalledToolNames());
            registeredSources = messageSourceRepository
                    .listByMessageId(preparedRun.getAssistantMessageId());
            AssistantCitationResult citation = citationParser.parse(runResult.getAnswer(), registeredSources);
            ensureRequiredTicketCitation(requiredTickets, registeredSources,
                    citation.getCitationNumbers());
            ensureScopeReadable(preparedRun);
            historyService.ensureReadable(preparedRun);
            List<RagSourceResponse> sources = readableSources(preparedRun.getUserId(), registeredSources,
                    citation.getCitationNumbers());
            if (sources.size() != citation.getCitationNumbers().size()) {
                throw new AssistantCitationException(AssistantCitationException.Reason.SOURCE_UNAVAILABLE);
            }
            long firstDeltaMs = elapsedMillis(startedNanos);
            persistenceService.append(preparedRun.getRunId(), preparedRun.getAssistantMessageId(),
                    citation.getAnswer());
            notifyDelta(safeObserver, citation.getAnswer());
            AgentMetrics metrics = metrics(runResult, registeredSources.size(), citation.getCitationNumbers().size(),
                    startedNanos, firstDeltaMs);
            persistenceService.complete(preparedRun.getRunId(), preparedRun.getAssistantMessageId(),
                    citation.getAnswer(), citation.getCitationNumbers(), null, serialize(metrics));
            return new AssistantAnswerResult(preparedRun.getRunId(), preparedRun.getAssistantMessageId(),
                    citation.getAnswer(), ChatRunStatusEnum.COMPLETED, sources, metrics);
        } catch (ChatRunCancelledException | RagGenerationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw fail(preparedRun, exception, startedNanos, runResult, registeredSources);
        }
    }

    private void ensureRequiredTicketLookup(Set<String> requiredTickets, Set<String> calledToolNames) {
        if (!requiredTickets.isEmpty()
                && !calledToolNames.contains(AssistantToolNameConstant.GET_TICKET_STATUS)) {
            throw new AssistantAgentBudgetException("AGENT_REQUIRED_TOOL_NOT_CALLED",
                    "当前工单状态必须实时查询");
        }
    }

    private void ensureRequiredTicketCitation(Set<String> requiredTickets, List<MessageSource> sources,
            List<Integer> citedNumbers) {
        for (String ticketNo : requiredTickets) {
            boolean citedTicket = sources.stream()
                    .anyMatch(source -> source.getSourceType() == MessageSourceTypeEnum.TICKET
                            && source.getOriginSourceId() == null && source.getToolCallId() != null
                            && citedNumbers.contains(source.getCitationNo()) && matchesTicket(source, ticketNo));
            if (!citedTicket) {
                throw new AssistantCitationException(AssistantCitationException.Reason.REQUIRED_TICKET_NOT_CITED);
            }
        }
    }

    private boolean matchesTicket(MessageSource source, String ticketNo) {
        try {
            return source.getSnapshot() != null && ticketNo.equals(
                    objectMapper.readTree(source.getSnapshot()).path("ticketNo").asText());
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private void ensureScopeReadable(PreparedChatRunResponse preparedRun) {
        List<UUID> knowledgeBaseIds = sessionKnowledgeBaseRepository
                .listKnowledgeBaseIdsBySessionId(preparedRun.getSessionId());
        if (knowledgeBaseIds.isEmpty()
                || !knowledgeScopeAccess.allReadable(preparedRun.getUserId(), knowledgeBaseIds)) {
            throw new AssistantAgentBudgetException("AGENT_SOURCE_REVOKED", "助手来源权限已失效");
        }
    }

    private List<RagSourceResponse> readableSources(UUID userId, List<MessageSource> registeredSources,
            List<Integer> citedNumbers) {
        List<RagSourceResponse> sources = new ArrayList<>();
        for (MessageSource source : registeredSources) {
            if (!citedNumbers.contains(source.getCitationNo())) {
                continue;
            }
            sourceResponseFactory.create(userId, source).ifPresent(sources::add);
        }
        return List.copyOf(sources);
    }

    private AgentMetrics metrics(AssistantAgentRunResult result, int evidenceCount, int citedCount,
            long startedNanos, long firstDeltaMs) {
        return new AgentMetrics(evidenceCount, citedCount, evidenceCount == 0, elapsedMillis(startedNanos),
                firstDeltaMs, result.getModelCallCount(), result.getToolCallCount(),
                result.getMaxObservedContextTokens(), properties.getMaxToolCalls(), properties.getMaxModelCalls());
    }

    private RuntimeException fail(PreparedChatRunResponse preparedRun, RuntimeException exception,
            long startedNanos, AssistantAgentRunResult runResult, List<MessageSource> registeredSources) {
        if (isCausedBy(exception, ChatRunCancelledException.class)
                || persistenceService.isCancelled(preparedRun.getRunId())) {
            return new ChatRunCancelledException();
        }
        AssistantAgentBudgetException budgetException = cause(exception, AssistantAgentBudgetException.class);
        AssistantToolException toolException = cause(exception, AssistantToolException.class);
        AssistantCitationException citationException = cause(exception, AssistantCitationException.class);
        String errorCode;
        String errorDetail;
        String publicMessage = PUBLIC_FAILURE_MESSAGE;
        if (budgetException != null) {
            errorCode = budgetException.getErrorCode();
            errorDetail = budgetException.getMessage();
            publicMessage = errorDetail;
        } else if (toolException != null) {
            errorCode = "AGENT_TOOL_FAILED";
            errorDetail = "白名单工具执行失败: " + toolException.getErrorCode();
            publicMessage = toolException.getMessage();
        } else if (citationException != null) {
            errorCode = "AGENT_INVALID_CITATION";
            errorDetail = "助手引用校验失败: " + citationException.getReason().name();
        } else {
            errorCode = "ASSISTANT_GENERATION_FAILED";
            errorDetail = "ReactAgent 执行失败";
        }
        try {
            // 校验失败仍保留已完成的实际调用及证据数量；执行未返回时保持指标未知，不伪写为零。
            AgentMetrics metrics = runResult != null && registeredSources != null
                    ? new AgentMetrics(registeredSources.size(), 0, registeredSources.isEmpty(),
                            elapsedMillis(startedNanos), null, runResult.getModelCallCount(),
                            runResult.getToolCallCount(), runResult.getMaxObservedContextTokens(),
                            properties.getMaxToolCalls(), properties.getMaxModelCalls())
                    : null;
            persistenceService.fail(preparedRun.getRunId(), preparedRun.getAssistantMessageId(), null,
                    errorCode, errorDetail, null, metrics == null ? null : serialize(metrics));
        } catch (ChatRunCancelledException | RagGenerationException terminalException) {
            return terminalException;
        } catch (RuntimeException ignored) {
            // 失败收口异常时仍只返回稳定错误，禁止泄露模型、工具或数据库详情。
        }
        return new AssistantGenerationException(errorCode, publicMessage);
    }

    private void notifyStarted(AssistantStreamObserver observer) {
        try {
            observer.onStarted();
        } catch (RuntimeException ignored) {
            // 客户端连接状态不能改变后台运行结果。
        }
    }

    private void notifyDelta(AssistantStreamObserver observer, String delta) {
        try {
            observer.onDelta(delta);
        } catch (RuntimeException ignored) {
            // 回答已经持久化，客户端连接异常不能回滚运行。
        }
    }

    private void validate(PreparedChatRunResponse preparedRun) {
        if (preparedRun == null || preparedRun.getMode() != ChatSessionModeEnum.ASSISTANT
                || preparedRun.getRunId() == null || preparedRun.getSessionId() == null
                || preparedRun.getUserId() == null || preparedRun.getUserMessageId() == null
                || preparedRun.getAssistantMessageId() == null || preparedRun.getQuestion() == null
                || preparedRun.getQuestion().isBlank() || preparedRun.isReused()
                || preparedRun.getStatus() != ChatRunStatusEnum.QUEUED) {
            throw new ChatSessionInvalidRequestException();
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("助手运行指标序列化失败", exception);
        }
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private boolean isCausedBy(Throwable throwable, Class<? extends Throwable> type) {
        return cause(throwable, type) != null;
    }

    private <T extends Throwable> T cause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
