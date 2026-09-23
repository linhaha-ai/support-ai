package com.haha.supportai.conversation.service.rag;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.conversation.config.RagProperties;
import com.haha.supportai.conversation.entity.dto.ChatModelChunk;
import com.haha.supportai.conversation.entity.dto.ChatModelUsage;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.dto.RagAnswerResult;
import com.haha.supportai.conversation.entity.dto.RagMetrics;
import com.haha.supportai.conversation.entity.vo.RagSourceResponse;
import com.haha.supportai.conversation.exception.ChatSessionInvalidRequestException;
import com.haha.supportai.conversation.exception.RagGenerationException;
import com.haha.supportai.conversation.exception.RagPersistenceException;
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
    private static final String MODEL_ERROR_CODE = "MODEL_UNAVAILABLE";
    private static final String MODEL_ERROR_DETAIL = "问答模型调用失败";
    private static final String EMPTY_MODEL_ERROR_CODE = "MODEL_EMPTY_RESPONSE";
    private static final String EMPTY_MODEL_ERROR_DETAIL = "问答模型未返回有效内容";
    private static final String PERSISTENCE_ERROR_CODE = "RAG_PERSISTENCE_FAILED";
    private static final String PERSISTENCE_ERROR_DETAIL = "问答运行状态保存失败";

    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final DocumentSourceQueryService documentSourceQueryService;
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
        GenerationContext context = new GenerationContext();
        try {
            persistenceService.start(preparedRun);
        } catch (RuntimeException exception) {
            throw failAndCreateException(preparedRun, context, PERSISTENCE_ERROR_CODE, PERSISTENCE_ERROR_DETAIL);
        }
        notifyStarted(safeObserver);

        List<KnowledgeRetrievalEvidenceResponse> evidenceList;
        long retrievalStartedNanos = System.nanoTime();
        try {
            evidenceList = retrieveAndRevalidate(preparedRun.getUserId(), preparedRun.getSessionId(),
                    preparedRun.getQuestion());
        } catch (RuntimeException exception) {
            context.retrievalMs = elapsedMillis(retrievalStartedNanos);
            throw failAndCreateException(preparedRun, context, RETRIEVAL_ERROR_CODE, RETRIEVAL_ERROR_DETAIL);
        }
        context.retrievalMs = elapsedMillis(retrievalStartedNanos);

        if (evidenceList.isEmpty()) {
            return completeWithoutEvidence(preparedRun, safeObserver, context);
        }

        List<NumberedEvidence> numberedEvidence = numberEvidence(evidenceList);
        context.evidenceCount = numberedEvidence.size();
        try {
            persistenceService.registerEvidence(preparedRun,
                    createMessageSources(preparedRun.getAssistantMessageId(), numberedEvidence));
        } catch (RuntimeException exception) {
            throw failAndCreateException(preparedRun, context, PERSISTENCE_ERROR_CODE, PERSISTENCE_ERROR_DETAIL);
        }

        RagPrompt prompt;
        try {
            prompt = promptFactory.create(preparedRun.getQuestion(), numberedEvidence);
        } catch (RuntimeException exception) {
            throw failAndCreateException(preparedRun, context, PERSISTENCE_ERROR_CODE, PERSISTENCE_ERROR_DETAIL);
        }
        long generationStartedNanos = System.nanoTime();
        try {
            chatModelGateway.stream(prompt.systemPrompt(), prompt.userPrompt(),
                    chunk -> acceptChunk(preparedRun, safeObserver, context, chunk));
        } catch (RagPersistenceException exception) {
            context.generationMs = elapsedMillis(generationStartedNanos);
            throw failAndCreateException(preparedRun, context, PERSISTENCE_ERROR_CODE, PERSISTENCE_ERROR_DETAIL);
        } catch (RuntimeException exception) {
            context.generationMs = elapsedMillis(generationStartedNanos);
            throw failAndCreateException(preparedRun, context, MODEL_ERROR_CODE, MODEL_ERROR_DETAIL);
        }
        context.generationMs = elapsedMillis(generationStartedNanos);
        String answer = context.content.toString();
        if (answer == null || answer.isBlank()) {
            throw failAndCreateException(preparedRun, context, EMPTY_MODEL_ERROR_CODE, EMPTY_MODEL_ERROR_DETAIL);
        }

        List<Integer> citedSourceNumbers;
        RagMetrics metrics;
        List<RagSourceResponse> sources;
        try {
            citedSourceNumbers = citationParser.findRegisteredCitations(answer, numberedEvidence.size());
            sources = readableSources(preparedRun.getUserId(), numberedEvidence, citedSourceNumbers);
            metrics = metrics(context, citedSourceNumbers.size(), false);
            persistenceService.complete(preparedRun.getRunId(), preparedRun.getAssistantMessageId(), answer,
                    citedSourceNumbers, serializeNullable(context.usage), serialize(metrics));
        } catch (RuntimeException exception) {
            throw failAndCreateException(preparedRun, context, PERSISTENCE_ERROR_CODE, PERSISTENCE_ERROR_DETAIL);
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
            throw failAndCreateException(preparedRun, context, PERSISTENCE_ERROR_CODE, PERSISTENCE_ERROR_DETAIL);
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

    private List<NumberedEvidence> numberEvidence(List<KnowledgeRetrievalEvidenceResponse> evidenceList) {
        List<NumberedEvidence> numberedEvidence = new ArrayList<>(evidenceList.size());
        for (int index = 0; index < evidenceList.size(); index++) {
            numberedEvidence.add(new NumberedEvidence(index + 1, evidenceList.get(index)));
        }
        return List.copyOf(numberedEvidence);
    }

    private List<MessageSource> createMessageSources(UUID assistantMessageId,
            List<NumberedEvidence> numberedEvidence) {
        OffsetDateTime now = OffsetDateTime.now();
        return numberedEvidence.stream()
                .map(item -> toMessageSource(assistantMessageId, item, now))
                .toList();
    }

    private MessageSource toMessageSource(UUID assistantMessageId, NumberedEvidence numberedEvidence,
            OffsetDateTime createdAt) {
        KnowledgeRetrievalEvidenceResponse evidence = numberedEvidence.evidence();
        return new MessageSource(UUID.randomUUID(), assistantMessageId, null, numberedEvidence.citationNumber(), false,
                MessageSourceTypeEnum.DOCUMENT, evidence.getKnowledgeBaseId(), evidence.getChunkId(),
                serialize(locator(evidence)), serialize(snapshot(evidence)), createdAt);
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

    private List<RagSourceResponse> readableSources(UUID userId, List<NumberedEvidence> numberedEvidence,
            List<Integer> citedSourceNumbers) {
        List<RagSourceResponse> sources = new ArrayList<>();
        for (Integer citationNumber : citedSourceNumbers) {
            NumberedEvidence numbered = numberedEvidence.get(citationNumber - 1);
            try {
                documentSourceQueryService.findReadableSource(userId, numbered.evidence().getChunkId())
                        .map(source -> toSourceResponse(citationNumber, source))
                        .ifPresent(sources::add);
            } catch (RuntimeException ignored) {
                // 发送来源前无法确认读取权限时直接隐藏，不使用生成前快照降级。
            }
        }
        return List.copyOf(sources);
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
        return new RagMetrics(context.evidenceCount, citedCount, noEvidence, context.retrievalMs,
                context.generationMs, context.elapsedMillis(), context.firstDeltaMs);
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private RagGenerationException failAndCreateException(PreparedChatRunResponse preparedRun,
            GenerationContext context, String errorCode, String errorDetail) {
        try {
            persistenceService.fail(preparedRun.getRunId(), preparedRun.getAssistantMessageId(),
                    context.content.toString(), errorCode, errorDetail, serializeFailureUsage(context.usage),
                    serializeFailureMetrics(context));
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

    private static class GenerationContext {

        private final long startedNanos = System.nanoTime();
        private final StringBuilder content = new StringBuilder();
        private ChatModelUsage usage;
        private Long firstDeltaMs;
        private int evidenceCount;
        private long retrievalMs;
        private Long generationMs;

        private long elapsedMillis() {
            return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        }
    }
}
