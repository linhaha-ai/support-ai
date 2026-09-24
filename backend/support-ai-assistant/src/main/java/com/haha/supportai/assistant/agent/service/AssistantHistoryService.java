package com.haha.supportai.assistant.agent.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.assistant.agent.entity.dto.AssistantConversationContext;
import com.haha.supportai.assistant.agent.entity.dto.AssistantHistoryTurn;
import com.haha.supportai.assistant.agent.exception.AssistantAgentBudgetException;
import com.haha.supportai.conversation.config.AssistantAgentProperties;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.data.assistant.repository.AssistantHistoryRepository;
import com.haha.supportai.data.assistant.repository.ToolCallRepository;
import com.haha.supportai.data.conversation.entity.po.CompletedChatTurn;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import com.haha.supportai.knowledge.contract.KnowledgeScopeAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AssistantHistoryService {

    private static final int PAGE_SIZE = 20;
    private static final Pattern CITATION = Pattern.compile("\\[([1-9][0-9]*)]");

    private final ChatMessageRepository messageRepository;
    private final MessageSourceRepository sourceRepository;
    private final AssistantHistoryRepository historyRepository;
    private final ToolCallRepository toolCallRepository;
    private final ChatSessionKnowledgeBaseRepository scopeRepository;
    private final KnowledgeScopeAccess scopeAccess;
    private final AssistantAgentProperties properties;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public AssistantConversationContext load(PreparedChatRunResponse prepared) {
        ensureScopeReadable(prepared);
        List<ValidatedTurn> selected = new ArrayList<>();
        Long beforeSequence = null;
        int characters = 0;
        while (selected.size() < properties.getHistoryMaxRounds()
                && characters < properties.getHistoryMaxCharacters()) {
            List<CompletedChatTurn> candidates = messageRepository.listCompletedTurnsBefore(
                    prepared.getSessionId(), prepared.getUserId(), prepared.getUserMessageId(), beforeSequence,
                    PAGE_SIZE);
            if (candidates.isEmpty()) {
                break;
            }
            for (CompletedChatTurn candidate : candidates) {
                List<MessageSource> sources = sourceRepository.listByMessageId(candidate.getAssistantMessageId());
                if (sources.isEmpty() || sources.stream().anyMatch(source -> !usable(prepared, source))) {
                    continue;
                }
                // 来源说明同样占用预算，避免短回答携带大量未显式引用的工具结果。
                long size = (long) candidate.getQuestion().length() + candidate.getAnswer().length()
                        + sources.stream().mapToLong(source -> source.getSnapshot().length() + 80L).sum();
                if (size > properties.getHistoryMaxCharacters() - characters) {
                    continue;
                }
                selected.add(new ValidatedTurn(candidate, sources));
                characters += (int) size;
                if (selected.size() == properties.getHistoryMaxRounds()) {
                    break;
                }
            }
            long nextSequence = candidates.getLast().getUserSequence();
            if (candidates.size() < PAGE_SIZE || beforeSequence != null && nextSequence >= beforeSequence) {
                break;
            }
            beforeSequence = nextSequence;
        }
        Collections.reverse(selected);
        return assemble(prepared, selected);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void register(PreparedChatRunResponse prepared, AssistantConversationContext history) {
        if (history.getSources().isEmpty()) {
            return;
        }
        if (!toolCallRepository.lockStartContext(prepared.getRunId(), prepared.getSessionId(),
                prepared.getUserId(), prepared.getAssistantMessageId())) {
            throw sourceRevoked();
        }
        int number = toolCallRepository.nextCitationNo(prepared.getAssistantMessageId());
        for (MessageSource source : history.getSources()) {
            source.setCitationNo(number++);
            if (!historyRepository.inherit(source.getOriginSourceId(), prepared.getSessionId(),
                    prepared.getUserId(), prepared.getRunId(), prepared.getAssistantMessageId(), source.getId(),
                    source.getCitationNo())) {
                // 任一根来源失效时整批回滚，禁止只保留部分历史来源。
                throw sourceRevoked();
            }
        }
    }

    public void ensureReadable(PreparedChatRunResponse prepared) {
        ensureScopeReadable(prepared);
        for (MessageSource source : sourceRepository.listByMessageId(prepared.getAssistantMessageId())) {
            if (!usable(prepared, source)) {
                throw sourceRevoked();
            }
        }
    }

    private void ensureScopeReadable(PreparedChatRunResponse prepared) {
        List<UUID> scope = scopeRepository.listKnowledgeBaseIdsBySessionId(prepared.getSessionId());
        if (scope.isEmpty() || !scopeAccess.allReadable(prepared.getUserId(), scope)) {
            throw sourceRevoked();
        }
    }

    private boolean usable(PreparedChatRunResponse prepared, MessageSource source) {
        if (source == null || source.getSnapshot() == null || source.getToolCallId() == null) {
            return false;
        }
        return historyRepository.isUsable(source.getId(), prepared.getSessionId(), prepared.getUserId());
    }

    private AssistantConversationContext assemble(PreparedChatRunResponse prepared, List<ValidatedTurn> selected) {
        Map<UUID, MessageSource> inheritedByRoot = new LinkedHashMap<>();
        List<AssistantHistoryTurn> turns = new ArrayList<>();
        for (ValidatedTurn turn : selected) {
            Map<Integer, UUID> handles = new LinkedHashMap<>();
            Set<String> tickets = new LinkedHashSet<>();
            Set<String> directTickets = new LinkedHashSet<>();
            for (MessageSource source : turn.sources()) {
                UUID rootId = source.getOriginSourceId() == null ? source.getId() : source.getOriginSourceId();
                MessageSource inherited = inheritedByRoot.computeIfAbsent(rootId, id -> new MessageSource(
                        UUID.randomUUID(), prepared.getAssistantMessageId(), id, inheritedByRoot.size() + 1,
                        false, source.getSourceType(), source.getKnowledgeBaseId(), source.getChunkId(),
                        source.getTicketId(), source.getToolCallId(), source.getLocator(), source.getSnapshot(),
                        OffsetDateTime.now()));
                handles.put(source.getCitationNo(), inherited.getId());
                if (source.getSourceType() == MessageSourceTypeEnum.TICKET) {
                    String ticketNo = ticketNumber(source);
                    tickets.add(ticketNo);
                    if (source.getOriginSourceId() == null) {
                        directTickets.add(ticketNo);
                    }
                }
            }
            turns.add(new AssistantHistoryTurn(turn.turn().getQuestion(),
                    rewriteCitations(turn.turn().getAnswer(), handles),
                    Set.copyOf(directTickets.isEmpty() ? tickets : directTickets)));
        }
        return new AssistantConversationContext(List.copyOf(turns), List.copyOf(inheritedByRoot.values()));
    }

    private String ticketNumber(MessageSource source) {
        try {
            String number = objectMapper.readTree(source.getSnapshot()).path("ticketNo").asText("");
            if (!number.isBlank()) {
                return number;
            }
        } catch (JsonProcessingException ignored) {
            // 已登记快照若不可解析则失败关闭，不能让损坏历史进入模型。
        }
        throw sourceRevoked();
    }

    private String rewriteCitations(String answer, Map<Integer, UUID> handles) {
        Matcher matcher = CITATION.matcher(answer);
        StringBuilder rewritten = new StringBuilder();
        while (matcher.find()) {
            UUID handle = null;
            try {
                handle = handles.get(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // 超长数字引用同样作为未登记来源处理。
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(handle == null
                    ? "（历史来源未登记）" : "[[source:" + handle + "]]"));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private AssistantAgentBudgetException sourceRevoked() {
        return new AssistantAgentBudgetException("AGENT_SOURCE_REVOKED", "助手历史或来源权限已失效，请重新提问");
    }

    private record ValidatedTurn(CompletedChatTurn turn, List<MessageSource> sources) {
    }
}
