package com.haha.supportai.conversation.service.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.conversation.config.RagProperties;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.dto.RagConversationContext;
import com.haha.supportai.conversation.entity.dto.RagHistorySource;
import com.haha.supportai.conversation.entity.dto.RagHistoryTurn;
import com.haha.supportai.conversation.support.CitationParser;
import com.haha.supportai.data.conversation.entity.po.CompletedChatTurn;
import com.haha.supportai.data.conversation.entity.po.HistoricalMessageSource;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import com.haha.supportai.knowledge.entity.vo.DocumentSourceResponse;
import com.haha.supportai.knowledge.service.source.DocumentSourceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationContextService {

    private static final int CANDIDATE_MULTIPLIER = 4;
    private static final int MIN_CANDIDATE_PAGE_SIZE = 20;
    private static final int MAX_CANDIDATE_PAGE_SIZE = 100;

    private final ChatMessageRepository chatMessageRepository;
    private final MessageSourceRepository messageSourceRepository;
    private final DocumentSourceQueryService documentSourceQueryService;
    private final RagProperties ragProperties;
    private final CitationParser citationParser;

    /**
     * 仅选择仍可验证全部来源的完整成功轮次，预算不足时不会截断问题或回答。
     */
    @Transactional(readOnly = true)
    public RagConversationContext load(PreparedChatRunResponse preparedRun) {
        int candidatePageSize = Math.min(MAX_CANDIDATE_PAGE_SIZE, Math.max(MIN_CANDIDATE_PAGE_SIZE,
                ragProperties.getHistoryMaxRounds() * CANDIDATE_MULTIPLIER));
        List<ValidatedTurn> selectedNewestFirst = new ArrayList<>();
        int usedCharacters = 0;
        Long beforeUserSequence = null;
        boolean exhausted = false;
        while (!exhausted && selectedNewestFirst.size() < ragProperties.getHistoryMaxRounds()
                && usedCharacters < ragProperties.getHistoryMaxCharacters()) {
            List<CompletedChatTurn> candidates = chatMessageRepository.listCompletedTurnsBefore(
                    preparedRun.getSessionId(), preparedRun.getUserId(), preparedRun.getUserMessageId(),
                    beforeUserSequence, candidatePageSize);
            if (candidates.isEmpty()) {
                break;
            }
            for (CompletedChatTurn candidate : candidates) {
                Optional<ValidatedTurn> validated = validateTurn(preparedRun, candidate);
                if (validated.isEmpty()) {
                    continue;
                }
                int turnCharacters = candidate.getQuestion().length() + candidate.getAnswer().length();
                if (turnCharacters > ragProperties.getHistoryMaxCharacters() - usedCharacters) {
                    continue;
                }
                selectedNewestFirst.add(validated.get());
                usedCharacters += turnCharacters;
                if (selectedNewestFirst.size() == ragProperties.getHistoryMaxRounds()
                        || usedCharacters == ragProperties.getHistoryMaxCharacters()) {
                    break;
                }
            }
            beforeUserSequence = candidates.getLast().getUserSequence();
            exhausted = candidates.size() < candidatePageSize;
        }
        if (selectedNewestFirst.isEmpty()) {
            return RagConversationContext.empty();
        }

        Collections.reverse(selectedNewestFirst);
        return assembleContext(selectedNewestFirst);
    }

    private Optional<ValidatedTurn> validateTurn(PreparedChatRunResponse preparedRun, CompletedChatTurn turn) {
        List<HistoricalMessageSource> sources = messageSourceRepository.listHistoricalSources(
                turn.getAssistantMessageId(), preparedRun.getSessionId(), preparedRun.getUserId());
        if (sources.isEmpty() || sources.stream().anyMatch(source -> !source.isLineageValid()
                || source.getRootSourceId() == null || source.getKnowledgeBaseId() == null
                || source.getChunkId() == null)) {
            return Optional.empty();
        }

        List<ValidatedSource> validatedSources = new ArrayList<>(sources.size());
        for (HistoricalMessageSource source : sources) {
            Optional<DocumentSourceResponse> readable;
            try {
                readable = documentSourceQueryService.findReadableSource(preparedRun.getUserId(), source.getChunkId());
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
            if (source.getSourceType() != MessageSourceTypeEnum.DOCUMENT || readable.isEmpty()
                    || !readable.get().isCurrent()
                    || !source.getKnowledgeBaseId().equals(readable.get().getKnowledgeBaseId())) {
                return Optional.empty();
            }
            validatedSources.add(new ValidatedSource(source));
        }
        return Optional.of(new ValidatedTurn(turn, List.copyOf(validatedSources)));
    }

    private RagConversationContext assembleContext(List<ValidatedTurn> turns) {
        Map<UUID, RagHistorySource> sourceByRootId = new LinkedHashMap<>();
        List<RagHistoryTurn> historyTurns = new ArrayList<>(turns.size());
        int usedCharacters = 0;
        for (ValidatedTurn validatedTurn : turns) {
            Map<UUID, RagHistorySource> candidateSources = new LinkedHashMap<>(sourceByRootId);
            Map<Integer, Integer> citationMapping = new LinkedHashMap<>();
            for (ValidatedSource validatedSource : validatedTurn.sources()) {
                HistoricalMessageSource source = validatedSource.source();
                RagHistorySource inherited = candidateSources.computeIfAbsent(source.getRootSourceId(), rootSourceId ->
                        new RagHistorySource(candidateSources.size() + 1, rootSourceId,
                                source.getKnowledgeBaseId(), source.getChunkId(), source.getLocator(),
                                source.getSnapshot()));
                citationMapping.putIfAbsent(source.getCitationNo(), inherited.getCitationNumber());
            }
            String question = validatedTurn.turn().getQuestion();
            String answer = citationParser.remapRegisteredCitations(validatedTurn.turn().getAnswer(), citationMapping);
            // 范围展开和编号重映射可能增加字符，入模前按实际文本再次核对整轮预算。
            int turnCharacters = question.length() + answer.length();
            if (turnCharacters > ragProperties.getHistoryMaxCharacters() - usedCharacters) {
                continue;
            }
            sourceByRootId = candidateSources;
            usedCharacters += turnCharacters;
            historyTurns.add(new RagHistoryTurn(question, answer));
        }
        return new RagConversationContext(List.copyOf(historyTurns), List.copyOf(sourceByRootId.values()));
    }

    private record ValidatedTurn(CompletedChatTurn turn, List<ValidatedSource> sources) {
    }

    private record ValidatedSource(HistoricalMessageSource source) {
    }
}
