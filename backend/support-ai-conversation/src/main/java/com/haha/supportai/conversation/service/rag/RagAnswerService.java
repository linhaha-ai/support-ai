package com.haha.supportai.conversation.service.rag;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.conversation.config.RagProperties;
import com.haha.supportai.conversation.entity.dto.ChatModelChunk;
import com.haha.supportai.conversation.entity.dto.ChatModelUsage;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.dto.RagAnswerResult;
import com.haha.supportai.conversation.entity.dto.RagConversationContext;
import com.haha.supportai.conversation.entity.dto.RagHistorySource;
import com.haha.supportai.conversation.entity.dto.RagMetrics;
import com.haha.supportai.conversation.entity.dto.RetrievalQueryDiagnostics;
import com.haha.supportai.conversation.entity.vo.RagSourceResponse;
import com.haha.supportai.conversation.enums.RetrievalQueryRewriteStatusEnum;
import com.haha.supportai.conversation.exception.ChatRunCancelledException;
import com.haha.supportai.conversation.exception.ChatSessionInvalidRequestException;
import com.haha.supportai.conversation.exception.RagGenerationException;
import com.haha.supportai.conversation.exception.RagPersistenceException;
import com.haha.supportai.conversation.service.context.ConversationContextService;
import com.haha.supportai.conversation.service.rewrite.RetrievalQueryRewriteService;
import com.haha.supportai.conversation.support.CitationParser;
import com.haha.supportai.conversation.support.RagPromptFactory;
import com.haha.supportai.conversation.support.RagPromptFactory.NumberedEvidence;
import com.haha.supportai.conversation.support.RagPromptFactory.RagPrompt;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.knowledge.entity.vo.DocumentSourceResponse;
import com.haha.supportai.knowledge.entity.vo.KnowledgeRetrievalEvidenceResponse;
import com.haha.supportai.knowledge.service.retrieval.KnowledgeRetrievalService;
import com.haha.supportai.knowledge.service.source.DocumentSourceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RagAnswerService {

    private static final String NO_EVIDENCE_ANSWER = "资料不足：未找到可用于回答该问题的授权资料。";
    private static final String RETRIEVAL_ERROR_CODE = "RETRIEVAL_FAILED";
    private static final String RETRIEVAL_ERROR_DETAIL = "授权资料检索失败";
    private static final String CONTEXT_ERROR_CODE = "CONTEXT_LOAD_FAILED";
    private static final String CONTEXT_ERROR_DETAIL = "有限历史上下文加载失败";
    private static final String MODEL_ERROR_CODE = "MODEL_UNAVAILABLE";
    private static final String MODEL_ERROR_DETAIL = "问答模型调用失败";
    private static final String EMPTY_MODEL_ERROR_CODE = "MODEL_EMPTY_RESPONSE";
    private static final String EMPTY_MODEL_ERROR_DETAIL = "问答模型未返回有效内容";
    private static final String PERSISTENCE_ERROR_CODE = "RAG_PERSISTENCE_FAILED";
    private static final String PERSISTENCE_ERROR_DETAIL = "问答运行状态保存失败";
    private static final String SOURCE_REVOKED_ERROR_CODE = "SOURCE_REVOKED";
    private static final String SOURCE_REVOKED_ERROR_DETAIL = "模型调用前授权资料已失效";

    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final DocumentSourceQueryService documentSourceQueryService;
    private final ConversationContextService conversationContextService;
    private final RetrievalQueryRewriteService retrievalQueryRewriteService;
    private final ChatModelGateway chatModelGateway;
    private final RagRunPersistenceService persistenceService;
    private final RagPromptFactory promptFactory;
    private final CitationParser citationParser;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public RagAnswerResult generate(PreparedChatRunResponse preparedRun) {
        return generateStreaming(preparedRun, RagStreamObserver.NONE);
    }

    public RagAnswerResult generateStreaming(PreparedChatRunResponse preparedRun, RagStreamObserver observer) {
        validate(preparedRun);
        RagStreamObserver safeObserver = observer == null ? RagStreamObserver.NONE : observer;
        GenerationContext context = new GenerationContext(preparedRun.getQuestion());
        try {
            persistenceService.start(preparedRun);
        } catch (RuntimeException exception) {
            throw failureOrCancellation(preparedRun, context, exception, PERSISTENCE_ERROR_CODE,
                    PERSISTENCE_ERROR_DETAIL);
        }
        notifyStarted(safeObserver);

        RagConversationContext conversationContext;
        try {
            conversationContext = loadConversationContext(preparedRun);
        } catch (RuntimeException exception) {
            throw failureOrCancellation(preparedRun, context, exception, CONTEXT_ERROR_CODE, CONTEXT_ERROR_DETAIL);
        }
        context.queryRewrite = rewriteRetrievalQuery(preparedRun.getQuestion(), conversationContext);

        List<KnowledgeRetrievalEvidenceResponse> evidenceList;
        try {
            evidenceList = retrieveAndRevalidate(preparedRun.getUserId(), preparedRun.getSessionId(),
                    context.queryRewrite.getRetrievalQuestion());
        } catch (RuntimeException exception) {
            throw failureOrCancellation(preparedRun, context, exception, RETRIEVAL_ERROR_CODE,
                    RETRIEVAL_ERROR_DETAIL);
        }

        ModelInput modelInput;
        try {
            modelInput = createModelInput(conversationContext, evidenceList);
        } catch (RuntimeException exception) {
            throw failureOrCancellation(preparedRun, context, exception, PERSISTENCE_ERROR_CODE,
                    PERSISTENCE_ERROR_DETAIL);
        }
        if (modelInput.sources().isEmpty()) {
            return completeWithoutEvidence(preparedRun, safeObserver, context);
        }

        context.evidenceCount = modelInput.sources().size();
        try {
            persistenceService.registerEvidence(preparedRun,
                    createMessageSources(preparedRun.getAssistantMessageId(), modelInput.sources()));
        } catch (RuntimeException exception) {
            throw failureOrCancellation(preparedRun, context, exception, PERSISTENCE_ERROR_CODE,
                    PERSISTENCE_ERROR_DETAIL);
        }
        try {
            revalidateRegisteredSources(preparedRun, modelInput.sources());
        } catch (RuntimeException exception) {
            throw failureOrCancellation(preparedRun, context, exception, SOURCE_REVOKED_ERROR_CODE,
                    SOURCE_REVOKED_ERROR_DETAIL);
        }

        RagPrompt prompt;
        try {
            prompt = promptFactory.create(preparedRun.getQuestion(), conversationContext.getTurns(),
                    modelInput.evidence());
        } catch (RuntimeException exception) {
            throw failureOrCancellation(preparedRun, context, exception, PERSISTENCE_ERROR_CODE,
                    PERSISTENCE_ERROR_DETAIL);
        }
        try {
            chatModelGateway.stream(prompt.systemPrompt(), prompt.userPrompt(),
                    chunk -> acceptChunk(preparedRun, safeObserver, context, chunk));
        } catch (RagPersistenceException exception) {
            throw failureOrCancellation(preparedRun, context, exception, PERSISTENCE_ERROR_CODE,
                    PERSISTENCE_ERROR_DETAIL);
        } catch (RuntimeException exception) {
            throw failureOrCancellation(preparedRun, context, exception, MODEL_ERROR_CODE, MODEL_ERROR_DETAIL);
        }
        String answer = context.content.toString();
        if (answer == null || answer.isBlank()) {
            throw failureOrCancellation(preparedRun, context, null, EMPTY_MODEL_ERROR_CODE,
                    EMPTY_MODEL_ERROR_DETAIL);
        }

        List<Integer> citedSourceNumbers;
        RagMetrics metrics;
        List<RagSourceResponse> sources;
        try {
            citedSourceNumbers = citationParser.findRegisteredCitations(answer, modelInput.sources().size());
            sources = readableSources(preparedRun.getUserId(), modelInput.sources(), citedSourceNumbers);
            metrics = metrics(context, citedSourceNumbers.size(), false);
            persistenceService.complete(preparedRun.getRunId(), preparedRun.getAssistantMessageId(), answer,
                    citedSourceNumbers, serializeNullable(context.usage), serialize(metrics));
        } catch (RuntimeException exception) {
            throw failureOrCancellation(preparedRun, context, exception, PERSISTENCE_ERROR_CODE,
                    PERSISTENCE_ERROR_DETAIL);
        }
        return new RagAnswerResult(preparedRun.getRunId(), preparedRun.getAssistantMessageId(), answer,
                citedSourceNumbers, ChatRunStatusEnum.COMPLETED, false, sources, context.usage, metrics);
    }

    private List<KnowledgeRetrievalEvidenceResponse> retrieveAndRevalidate(UUID userId, UUID sessionId,
            String question) {
        List<KnowledgeRetrievalEvidenceResponse> candidates = knowledgeRetrievalService
                .retrieveReadableWithinSession(userId, sessionId, question, ragProperties.getTopK());
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<UUID> chunkIds = candidates.stream()
                .map(KnowledgeRetrievalEvidenceResponse::getChunkId)
                .toList();
        Map<UUID, Double> retrievalScores = new HashMap<>();
        candidates.forEach(evidence -> retrievalScores.putIfAbsent(evidence.getChunkId(), evidence.getScore()));
        Map<UUID, KnowledgeRetrievalEvidenceResponse> revalidatedByChunkId = new HashMap<>();
        knowledgeRetrievalService.revalidateReadableWithinSession(userId, sessionId, chunkIds)
                .forEach(evidence -> revalidatedByChunkId.putIfAbsent(evidence.getChunkId(), evidence));
        List<KnowledgeRetrievalEvidenceResponse> revalidatedEvidence = new ArrayList<>();
        for (UUID chunkId : chunkIds) {
            KnowledgeRetrievalEvidenceResponse evidence = revalidatedByChunkId.get(chunkId);
            if (evidence != null) {
                revalidatedEvidence.add(withRetrievalScore(evidence,
                        retrievalScores.getOrDefault(chunkId, evidence.getScore())));
            }
        }
        return List.copyOf(revalidatedEvidence);
    }

    private RagConversationContext loadConversationContext(PreparedChatRunResponse preparedRun) {
        RagConversationContext loaded = conversationContextService.load(preparedRun);
        return loaded == null ? RagConversationContext.empty() : loaded;
    }

    /**
     * 改写异常只影响检索问题质量，不得让本轮问答失败或改变用户原始问题。
     */
    private RetrievalQueryDiagnostics rewriteRetrievalQuery(String originalQuestion,
            RagConversationContext conversationContext) {
        try {
            RetrievalQueryDiagnostics diagnostics = retrievalQueryRewriteService.rewrite(originalQuestion,
                    conversationContext.getTurns());
            if (diagnostics != null && originalQuestion.equals(diagnostics.getOriginalQuestion())
                    && diagnostics.getRetrievalQuestion() != null
                    && !diagnostics.getRetrievalQuestion().isBlank()
                    && diagnostics.getStatus() != null) {
                return diagnostics;
            }
        } catch (RuntimeException ignored) {
            // 改写属于可降级能力，任何异常均使用已持久化的原始问题继续授权检索。
        }
        return fallbackQuery(originalQuestion);
    }

    private RetrievalQueryDiagnostics fallbackQuery(String originalQuestion) {
        return new RetrievalQueryDiagnostics(originalQuestion, originalQuestion,
                RetrievalQueryRewriteStatusEnum.FALLBACK);
    }

    private KnowledgeRetrievalEvidenceResponse withRetrievalScore(KnowledgeRetrievalEvidenceResponse evidence,
            double retrievalScore) {
        return new KnowledgeRetrievalEvidenceResponse(evidence.getKnowledgeBaseId(), evidence.getDocumentId(),
                evidence.getDocumentName(), evidence.getVersionId(), evidence.getVersionNo(), evidence.getChunkId(),
                evidence.getChunkIndex(), evidence.getSection(), evidence.getPageNo(), evidence.getStartOffset(),
                evidence.getEndOffset(), evidence.getContent(), retrievalScore);
    }

    private RagAnswerResult completeWithoutEvidence(PreparedChatRunResponse preparedRun, RagStreamObserver observer,
            GenerationContext context) {
        RagMetrics metrics;
        try {
            append(preparedRun, observer, context, NO_EVIDENCE_ANSWER);
            metrics = metrics(context, 0, true);
            persistenceService.complete(preparedRun.getRunId(), preparedRun.getAssistantMessageId(),
                    NO_EVIDENCE_ANSWER, List.of(), null, serialize(metrics));
        } catch (RuntimeException exception) {
            throw failureOrCancellation(preparedRun, context, exception, PERSISTENCE_ERROR_CODE,
                    PERSISTENCE_ERROR_DETAIL);
        }
        return new RagAnswerResult(preparedRun.getRunId(), preparedRun.getAssistantMessageId(), NO_EVIDENCE_ANSWER,
                List.of(), ChatRunStatusEnum.COMPLETED, true, List.of(), null, metrics);
    }

    private void acceptChunk(PreparedChatRunResponse preparedRun, RagStreamObserver observer,
            GenerationContext context, ChatModelChunk chunk) {
        if (chunk == null) {
            return;
        }
        if (chunk.getUsage() != null) {
            context.usage = chunk.getUsage();
        }
        String delta = chunk.getContent();
        if (delta == null || delta.isEmpty()) {
            return;
        }
        try {
            append(preparedRun, observer, context, delta);
        } catch (ChatRunCancelledException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RagPersistenceException();
        }
    }

    private void append(PreparedChatRunResponse preparedRun, RagStreamObserver observer, GenerationContext context,
            String delta) {
        persistenceService.append(preparedRun.getRunId(), preparedRun.getAssistantMessageId(), delta);
        context.content.append(delta);
        if (context.firstDeltaMs == null) {
            context.firstDeltaMs = context.elapsedMillis();
        }
        notifyDelta(observer, delta);
    }

    private void notifyStarted(RagStreamObserver observer) {
        try {
            observer.onStarted();
        } catch (RuntimeException ignored) {
            // 客户端断开不应中止后台生成，最终状态仍以数据库为准。
        }
    }

    private void notifyDelta(RagStreamObserver observer, String delta) {
        try {
            observer.onDelta(delta);
        } catch (RuntimeException ignored) {
            // 增量已经持久化，发送失败时继续生成，客户端可在后续查询运行结果。
        }
    }

    private ModelInput createModelInput(RagConversationContext context,
            List<KnowledgeRetrievalEvidenceResponse> evidenceList) {
        List<RegisteredSource> sources = new ArrayList<>();
        Map<UUID, RegisteredSource> sourceByChunkId = new LinkedHashMap<>();
        Set<UUID> inheritedRootSourceIds = new HashSet<>();
        for (RagHistorySource inherited : context.getSources()) {
            if (inherited.getCitationNumber() != sources.size() + 1
                    || inherited.getRootSourceId() == null || inherited.getKnowledgeBaseId() == null
                    || inherited.getChunkId() == null || inherited.getLocator() == null
                    || inherited.getSnapshot() == null
                    || !inheritedRootSourceIds.add(inherited.getRootSourceId())) {
                throw new IllegalStateException("历史来源上下文无效");
            }
            RegisteredSource registered = new RegisteredSource(inherited.getCitationNumber(),
                    inherited.getRootSourceId(), inherited.getKnowledgeBaseId(), inherited.getChunkId(),
                    inherited.getLocator(), inherited.getSnapshot());
            sources.add(registered);
            sourceByChunkId.putIfAbsent(registered.chunkId(), registered);
        }

        Map<UUID, NumberedEvidence> numberedByChunkId = new LinkedHashMap<>();
        for (KnowledgeRetrievalEvidenceResponse evidence : evidenceList) {
            RegisteredSource registered = sourceByChunkId.get(evidence.getChunkId());
            if (registered == null) {
                registered = new RegisteredSource(sources.size() + 1, null, evidence.getKnowledgeBaseId(),
                        evidence.getChunkId(), serialize(locator(evidence)), serialize(snapshot(evidence)));
                sources.add(registered);
                sourceByChunkId.put(registered.chunkId(), registered);
            }
            numberedByChunkId.putIfAbsent(evidence.getChunkId(),
                    new NumberedEvidence(registered.citationNumber(), evidence));
        }
        return new ModelInput(List.copyOf(numberedByChunkId.values()), List.copyOf(sources));
    }

    /**
     * 来源登记提交后紧邻模型调用再次复核，避免撤权或 active 版本切换后的内容进入模型。
     */
    private void revalidateRegisteredSources(PreparedChatRunResponse preparedRun, List<RegisteredSource> sources) {
        List<UUID> chunkIds = sources.stream()
                .map(RegisteredSource::chunkId)
                .distinct()
                .toList();
        Map<UUID, KnowledgeRetrievalEvidenceResponse> readableByChunkId = new HashMap<>();
        knowledgeRetrievalService.revalidateReadableWithinSession(preparedRun.getUserId(),
                        preparedRun.getSessionId(), chunkIds)
                .forEach(evidence -> readableByChunkId.putIfAbsent(evidence.getChunkId(), evidence));
        for (RegisteredSource source : sources) {
            KnowledgeRetrievalEvidenceResponse readable = readableByChunkId.get(source.chunkId());
            if (readable == null || !source.knowledgeBaseId().equals(readable.getKnowledgeBaseId())) {
                throw new IllegalStateException("授权资料已失效");
            }
        }
    }

    private List<MessageSource> createMessageSources(UUID assistantMessageId, List<RegisteredSource> sources) {
        OffsetDateTime now = OffsetDateTime.now();
        return sources.stream()
                .map(source -> toMessageSource(assistantMessageId, source, now))
                .toList();
    }

    private MessageSource toMessageSource(UUID assistantMessageId, RegisteredSource source,
            OffsetDateTime createdAt) {
        return new MessageSource(UUID.randomUUID(), assistantMessageId, source.originSourceId(),
                source.citationNumber(), false, MessageSourceTypeEnum.DOCUMENT, source.knowledgeBaseId(),
                source.chunkId(), source.locator(), source.snapshot(), createdAt);
    }

    private Map<String, Object> locator(KnowledgeRetrievalEvidenceResponse evidence) {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("section", evidence.getSection());
        locator.put("pageNo", evidence.getPageNo());
        locator.put("startOffset", evidence.getStartOffset());
        locator.put("endOffset", evidence.getEndOffset());
        return locator;
    }

    private Map<String, Object> snapshot(KnowledgeRetrievalEvidenceResponse evidence) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", evidence.getDocumentId());
        snapshot.put("documentName", evidence.getDocumentName());
        snapshot.put("versionId", evidence.getVersionId());
        snapshot.put("versionNo", evidence.getVersionNo());
        snapshot.put("chunkIndex", evidence.getChunkIndex());
        snapshot.put("score", evidence.getScore());
        return snapshot;
    }

    private List<RagSourceResponse> readableSources(UUID userId, List<RegisteredSource> registeredSources,
            List<Integer> citedSourceNumbers) {
        Map<Integer, RegisteredSource> sourceByNumber = new HashMap<>();
        registeredSources.forEach(source -> sourceByNumber.put(source.citationNumber(), source));
        List<RagSourceResponse> sources = new ArrayList<>();
        for (Integer citationNumber : citedSourceNumbers) {
            RegisteredSource registered = sourceByNumber.get(citationNumber);
            if (registered == null) {
                continue;
            }
            try {
                documentSourceQueryService.findReadableSource(userId, registered.chunkId())
                        .map(source -> toSourceResponse(citationNumber, source))
                        .ifPresent(sources::add);
            } catch (RuntimeException ignored) {
                // 发送来源前无法确认读取权限时直接隐藏，不使用生成前快照降级。
            }
        }
        return List.copyOf(sources);
    }

    /**
     * SSE 真正发送来源前再次复核权限，避免使用生成完成时缓存的授权结果。
     */
    public List<RagSourceResponse> revalidateReadableSources(UUID userId, List<RagSourceResponse> sources) {
        if (userId == null || sources == null || sources.isEmpty()) {
            return List.of();
        }
        List<RagSourceResponse> readableSources = new ArrayList<>();
        for (RagSourceResponse source : sources) {
            if (source == null || source.getType() != MessageSourceTypeEnum.DOCUMENT || source.getChunkId() == null) {
                continue;
            }
            try {
                documentSourceQueryService.findReadableSource(userId, source.getChunkId())
                        .map(documentSource -> toSourceResponse(source.getNumber(), documentSource))
                        .ifPresent(readableSources::add);
            } catch (RuntimeException ignored) {
                // 发送瞬间无法确认读取权限时直接隐藏，不使用先前缓存的来源信息。
            }
        }
        return List.copyOf(readableSources);
    }

    private RagSourceResponse toSourceResponse(int citationNumber, DocumentSourceResponse source) {
        return new RagSourceResponse(citationNumber, MessageSourceTypeEnum.DOCUMENT, source.getDocumentId(),
                source.getVersionId(), source.getChunkId(), source.getDocumentName(), source.getSection(),
                source.isCurrent(), "/api/chunks/" + source.getChunkId() + "/source");
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RAG 元数据序列化失败", exception);
        }
    }

    private String serializeNullable(Object value) {
        return value == null ? null : serialize(value);
    }

    private void validate(PreparedChatRunResponse preparedRun) {
        if (preparedRun == null || preparedRun.getRunId() == null || preparedRun.getSessionId() == null
                || preparedRun.getUserId() == null || preparedRun.getUserMessageId() == null
                || preparedRun.getAssistantMessageId() == null
                || preparedRun.getQuestion() == null || preparedRun.getQuestion().isBlank() || preparedRun.isReused()
                || preparedRun.getStatus() != ChatRunStatusEnum.QUEUED) {
            throw new ChatSessionInvalidRequestException();
        }
        if (!preparedRun.getQuestion().equals(preparedRun.getQuestion().trim())) {
            throw new ChatSessionInvalidRequestException();
        }
    }

    private RagMetrics metrics(GenerationContext context, int citedCount, boolean noEvidence) {
        return new RagMetrics(context.evidenceCount, citedCount, noEvidence, context.elapsedMillis(),
                context.firstDeltaMs, context.queryRewrite);
    }

    private RuntimeException failureOrCancellation(PreparedChatRunResponse preparedRun, GenerationContext context,
            RuntimeException cause, String errorCode, String errorDetail) {
        if (cause instanceof ChatRunCancelledException || persistenceService.isCancelled(preparedRun.getRunId())) {
            return new ChatRunCancelledException();
        }
        return failAndCreateException(preparedRun, context, errorCode, errorDetail);
    }

    private RuntimeException failAndCreateException(PreparedChatRunResponse preparedRun,
            GenerationContext context, String errorCode, String errorDetail) {
        try {
            persistenceService.fail(preparedRun.getRunId(), preparedRun.getAssistantMessageId(),
                    context.content.toString(), errorCode, errorDetail, serializeFailureUsage(context.usage),
                    serializeFailureMetrics(context));
        } catch (ChatRunCancelledException exception) {
            return exception;
        } catch (RagGenerationException exception) {
            // 总超时已先取得数据库终态时，流式通道必须沿用同一个稳定错误码。
            return exception;
        } catch (RuntimeException ignored) {
            // 失败收口本身异常时仍只向上返回稳定错误，不泄露底层数据库或供应商信息。
        }
        return new RagGenerationException(errorCode, "本次问答生成失败，请稍后重试");
    }

    private String serializeFailureMetrics(GenerationContext context) {
        try {
            return serialize(metrics(context, 0, false));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String serializeFailureUsage(ChatModelUsage usage) {
        try {
            return serializeNullable(usage);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record ModelInput(List<NumberedEvidence> evidence, List<RegisteredSource> sources) {
    }

    private record RegisteredSource(int citationNumber, UUID originSourceId, UUID knowledgeBaseId, UUID chunkId,
                                    String locator, String snapshot) {
    }

    private static class GenerationContext {

        private final long startedNanos = System.nanoTime();
        private final StringBuilder content = new StringBuilder();
        private ChatModelUsage usage;
        private Long firstDeltaMs;
        private int evidenceCount;
        private RetrievalQueryDiagnostics queryRewrite;

        private GenerationContext(String originalQuestion) {
            this.queryRewrite = new RetrievalQueryDiagnostics(originalQuestion, originalQuestion,
                    RetrievalQueryRewriteStatusEnum.NOT_ATTEMPTED);
        }

        private long elapsedMillis() {
            return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        }
    }
}
