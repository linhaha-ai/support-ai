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
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.dto.RagAnswerResult;
import com.haha.supportai.conversation.exception.ChatSessionInvalidRequestException;
import com.haha.supportai.conversation.exception.RagGenerationException;
import com.haha.supportai.conversation.support.CitationParser;
import com.haha.supportai.conversation.support.RagPromptFactory;
import com.haha.supportai.conversation.support.RagPromptFactory.NumberedEvidence;
import com.haha.supportai.conversation.support.RagPromptFactory.RagPrompt;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.knowledge.entity.vo.KnowledgeRetrievalEvidenceResponse;
import com.haha.supportai.knowledge.service.retrieval.KnowledgeRetrievalService;
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
    private final ChatModelGateway chatModelGateway;
    private final RagRunPersistenceService persistenceService;
    private final RagPromptFactory promptFactory;
    private final CitationParser citationParser;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public RagAnswerResult generate(PreparedChatRunResponse preparedRun) {
        validate(preparedRun);
        UUID sessionId = preparedRun.getSessionId();
        String normalizedQuestion = preparedRun.getQuestion();
        List<KnowledgeRetrievalEvidenceResponse> evidenceList;
        try {
            evidenceList = retrieveAndRevalidate(sessionId, normalizedQuestion);
        } catch (RuntimeException exception) {
            fail(preparedRun, RETRIEVAL_ERROR_CODE, RETRIEVAL_ERROR_DETAIL);
            throw new RagGenerationException();
        }

        if (evidenceList.isEmpty()) {
            return completeWithoutEvidence(preparedRun);
        }

        List<NumberedEvidence> numberedEvidence = numberEvidence(evidenceList);
        try {
            persistenceService.startWithEvidence(preparedRun,
                    createMessageSources(preparedRun.getAssistantMessageId(), numberedEvidence));
        } catch (RuntimeException exception) {
            fail(preparedRun, PERSISTENCE_ERROR_CODE, PERSISTENCE_ERROR_DETAIL);
            throw new RagGenerationException();
        }

        RagPrompt prompt;
        try {
            prompt = promptFactory.create(normalizedQuestion, numberedEvidence);
        } catch (RuntimeException exception) {
            fail(preparedRun, PERSISTENCE_ERROR_CODE, PERSISTENCE_ERROR_DETAIL);
            throw new RagGenerationException();
        }
        String answer;
        try {
            answer = chatModelGateway.generate(prompt.systemPrompt(), prompt.userPrompt());
        } catch (RuntimeException exception) {
            fail(preparedRun, MODEL_ERROR_CODE, MODEL_ERROR_DETAIL);
            throw new RagGenerationException();
        }
        if (answer == null || answer.isBlank()) {
            fail(preparedRun, EMPTY_MODEL_ERROR_CODE, EMPTY_MODEL_ERROR_DETAIL);
            throw new RagGenerationException();
        }

        List<Integer> citedSourceNumbers;
        try {
            citedSourceNumbers = citationParser.findRegisteredCitations(answer, numberedEvidence.size());
            persistenceService.complete(preparedRun.getRunId(), preparedRun.getAssistantMessageId(), answer,
                    citedSourceNumbers, metrics(numberedEvidence.size(), citedSourceNumbers.size(), false));
        } catch (RuntimeException exception) {
            fail(preparedRun, PERSISTENCE_ERROR_CODE, PERSISTENCE_ERROR_DETAIL);
            throw new RagGenerationException();
        }
        return new RagAnswerResult(preparedRun.getRunId(), preparedRun.getAssistantMessageId(), answer,
                citedSourceNumbers, ChatRunStatusEnum.COMPLETED, false);
    }

    private List<KnowledgeRetrievalEvidenceResponse> retrieveAndRevalidate(UUID sessionId, String question) {
        List<KnowledgeRetrievalEvidenceResponse> candidates = knowledgeRetrievalService
                .retrieveReadableWithinSession(sessionId, question, ragProperties.getTopK());
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<UUID> chunkIds = candidates.stream()
                .map(KnowledgeRetrievalEvidenceResponse::getChunkId)
                .toList();
        Map<UUID, Double> retrievalScores = new HashMap<>();
        candidates.forEach(evidence -> retrievalScores.putIfAbsent(evidence.getChunkId(), evidence.getScore()));
        Map<UUID, KnowledgeRetrievalEvidenceResponse> revalidatedByChunkId = new HashMap<>();
        knowledgeRetrievalService.revalidateReadableWithinSession(sessionId, chunkIds)
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

    private RagAnswerResult completeWithoutEvidence(PreparedChatRunResponse preparedRun) {
        try {
            persistenceService.completeWithoutEvidence(preparedRun, NO_EVIDENCE_ANSWER, metrics(0, 0, true));
        } catch (RuntimeException exception) {
            fail(preparedRun, PERSISTENCE_ERROR_CODE, PERSISTENCE_ERROR_DETAIL);
            throw new RagGenerationException();
        }
        return new RagAnswerResult(preparedRun.getRunId(), preparedRun.getAssistantMessageId(), NO_EVIDENCE_ANSWER,
                List.of(), ChatRunStatusEnum.COMPLETED, true);
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

    private String serialize(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RAG 元数据序列化失败", exception);
        }
    }

    private void validate(PreparedChatRunResponse preparedRun) {
        if (preparedRun == null || preparedRun.getRunId() == null || preparedRun.getSessionId() == null
                || preparedRun.getUserMessageId() == null || preparedRun.getAssistantMessageId() == null
                || preparedRun.getQuestion() == null || preparedRun.getQuestion().isBlank() || preparedRun.isReused()
                || preparedRun.getStatus() != ChatRunStatusEnum.QUEUED) {
            throw new ChatSessionInvalidRequestException();
        }
        if (!preparedRun.getQuestion().equals(preparedRun.getQuestion().trim())) {
            throw new ChatSessionInvalidRequestException();
        }
    }

    private String metrics(int evidenceCount, int citedCount, boolean noEvidence) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("evidenceCount", evidenceCount);
        metrics.put("citedCount", citedCount);
        metrics.put("noEvidence", noEvidence);
        return serialize(metrics);
    }

    private void fail(PreparedChatRunResponse preparedRun, String errorCode, String errorDetail) {
        persistenceService.fail(preparedRun.getRunId(), preparedRun.getAssistantMessageId(), errorCode, errorDetail);
    }
}
