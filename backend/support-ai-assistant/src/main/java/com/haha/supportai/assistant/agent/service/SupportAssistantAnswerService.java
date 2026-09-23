package com.haha.supportai.assistant.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.assistant.agent.entity.dto.AssistantAgentRunResult;
import com.haha.supportai.assistant.agent.entity.dto.AssistantCitationResult;
import com.haha.supportai.assistant.agent.exception.AssistantAgentBudgetException;
import com.haha.supportai.assistant.agent.exception.AssistantCitationException;
import com.haha.supportai.assistant.agent.support.AssistantCitationParser;
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

    private static final Pattern CURRENT_TICKET_QUESTION = Pattern.compile(
            "(?is).*(工单|状态|进度|当前|现在|latest|status).*(\\bT[0-9]{4,31}\\b).*"
                    + "|.*(\\bT[0-9]{4,31}\\b).*(工单|状态|进度|当前|现在|latest|status).*");
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

    @Override
    public AssistantAnswerResult generateStreaming(PreparedChatRunResponse preparedRun,
            AssistantStreamObserver observer) {
        validate(preparedRun);
        AssistantStreamObserver safeObserver = observer == null ? AssistantStreamObserver.NONE : observer;
        long startedNanos = System.nanoTime();
        try {
            persistenceService.start(preparedRun);
            notifyStarted(safeObserver);
            AssistantAgentRunResult runResult = agentRunner.run(preparedRun, safeObserver);
            ensureRequiredTicketLookup(preparedRun.getQuestion(), runResult.getCalledToolNames());
            List<MessageSource> registeredSources = messageSourceRepository
                    .listByMessageId(preparedRun.getAssistantMessageId());
            AssistantCitationResult citation = citationParser.parse(runResult.getAnswer(), registeredSources);
            ensureRequiredTicketCitation(preparedRun.getQuestion(), registeredSources,
                    citation.getCitationNumbers());
            ensureScopeReadable(preparedRun);
            List<RagSourceResponse> sources = readableSources(preparedRun.getUserId(), registeredSources,
                    citation.getCitationNumbers());
            if (sources.size() != citation.getCitationNumbers().size()) {
                throw new AssistantCitationException();
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
            throw fail(preparedRun, exception, startedNanos);
        }
    }

    private void ensureRequiredTicketLookup(String question, Set<String> calledToolNames) {
        if (requiresCurrentTicketLookup(question)
                && !calledToolNames.contains(AssistantToolNameConstant.GET_TICKET_STATUS)) {
            throw new AssistantAgentBudgetException("AGENT_REQUIRED_TOOL_NOT_CALLED",
                    "当前工单状态必须实时查询");
        }
    }

    private void ensureRequiredTicketCitation(String question, List<MessageSource> sources,
            List<Integer> citedNumbers) {
        if (!requiresCurrentTicketLookup(question)) {
            return;
        }
        boolean citedTicket = sources.stream()
                .anyMatch(source -> source.getSourceType() == MessageSourceTypeEnum.TICKET
                        && citedNumbers.contains(source.getCitationNo()));
        if (!citedTicket) {
            throw new AssistantCitationException();
        }
    }

    private boolean requiresCurrentTicketLookup(String question) {
        return CURRENT_TICKET_QUESTION.matcher(question).matches();
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
            long startedNanos) {
        if (isCausedBy(exception, ChatRunCancelledException.class)
                || persistenceService.isCancelled(preparedRun.getRunId())) {
            return new ChatRunCancelledException();
        }
        AssistantAgentBudgetException budgetException = cause(exception, AssistantAgentBudgetException.class);
        AssistantToolException toolException = cause(exception, AssistantToolException.class);
        String errorCode;
        String errorDetail;
        String publicMessage = PUBLIC_FAILURE_MESSAGE;
        if (budgetException != null) {
            errorCode = budgetException.getErrorCode();
            errorDetail = budgetException.getMessage();
        } else if (toolException != null) {
            errorCode = "AGENT_TOOL_FAILED";
            errorDetail = "白名单工具执行失败: " + toolException.getErrorCode();
            publicMessage = toolException.getMessage();
        } else if (isCausedBy(exception, AssistantCitationException.class)) {
            errorCode = "AGENT_INVALID_CITATION";
            errorDetail = "模型返回了未知或不可信的来源引用";
        } else {
            errorCode = "ASSISTANT_GENERATION_FAILED";
            errorDetail = "ReactAgent 执行失败";
        }
        try {
            AgentMetrics metrics = new AgentMetrics(0, 0, true, elapsedMillis(startedNanos), null,
                    0, 0, 0, properties.getMaxToolCalls(), properties.getMaxModelCalls());
            persistenceService.fail(preparedRun.getRunId(), preparedRun.getAssistantMessageId(), null,
                    errorCode, errorDetail, null, serialize(metrics));
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
