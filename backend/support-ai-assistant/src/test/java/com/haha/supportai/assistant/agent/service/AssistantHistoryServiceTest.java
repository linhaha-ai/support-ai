package com.haha.supportai.assistant.agent.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.assistant.agent.entity.dto.AssistantConversationContext;
import com.haha.supportai.assistant.agent.exception.AssistantAgentBudgetException;
import com.haha.supportai.conversation.config.AssistantAgentProperties;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.data.assistant.repository.AssistantHistoryRepository;
import com.haha.supportai.data.assistant.repository.ToolCallRepository;
import com.haha.supportai.data.conversation.entity.po.CompletedChatTurn;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import com.haha.supportai.knowledge.contract.KnowledgeScopeAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantHistoryServiceTest {

    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final MessageSourceRepository sources = mock(MessageSourceRepository.class);
    private final AssistantHistoryRepository history = mock(AssistantHistoryRepository.class);
    private final ToolCallRepository calls = mock(ToolCallRepository.class);
    private final ChatSessionKnowledgeBaseRepository scope = mock(ChatSessionKnowledgeBaseRepository.class);
    private final KnowledgeScopeAccess access = mock(KnowledgeScopeAccess.class);
    private final AssistantAgentProperties properties = new AssistantAgentProperties();
    private final UUID kbId = UUID.randomUUID();
    private final PreparedChatRunResponse prepared = new PreparedChatRunResponse(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "现在呢？", ChatSessionModeEnum.ASSISTANT,
            ChatRunStatusEnum.QUEUED, false);
    private final AssistantHistoryService service = new AssistantHistoryService(messages, sources, history,
            calls, scope, access, properties, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(scope.listKnowledgeBaseIdsBySessionId(prepared.getSessionId())).thenReturn(List.of(kbId));
        when(access.allReadable(prepared.getUserId(), List.of(kbId))).thenReturn(true);
    }

    @Test
    void inheritsAllEvidenceIncludingUncitedSourcesAndFlattensRoots() {
        CompletedChatTurn first = turn(1, "第一轮问题", "先前结果[1]");
        CompletedChatTurn second = turn(3, "解释原因", "历史说明[1]");
        MessageSource root = ticket(first.getAssistantMessageId(), null, 1);
        MessageSource inherited = ticket(second.getAssistantMessageId(), root.getId(), 1);
        MessageSource uncited = ticket(second.getAssistantMessageId(), null, 2);
        candidates(List.of(second, first));
        when(sources.listByMessageId(first.getAssistantMessageId())).thenReturn(List.of(root));
        when(sources.listByMessageId(second.getAssistantMessageId())).thenReturn(List.of(inherited, uncited));
        when(history.isUsable(any(), eq(prepared.getSessionId()), eq(prepared.getUserId()))).thenReturn(true);

        AssistantConversationContext context = service.load(prepared);

        assertThat(context.getTurns()).extracting(item -> item.getQuestion())
                .containsExactly("第一轮问题", "解释原因");
        assertThat(context.getSources()).hasSize(2).extracting(MessageSource::getOriginSourceId)
                .containsExactly(root.getId(), uncited.getId());
        assertThat(context.getTurns().getFirst().getAnswer()).contains(
                "[[source:" + context.getSources().getFirst().getId() + "]]");
        assertThat(context.getTurns().getLast().getAnswer()).contains(
                "[[source:" + context.getSources().getFirst().getId() + "]]");
    }

    @Test
    void dropsWholeTurnWhenEvenUncitedEvidenceIsRevoked() {
        CompletedChatTurn turn = turn(1, "问题", "仅引用第一条[1]");
        MessageSource allowed = ticket(turn.getAssistantMessageId(), null, 1);
        MessageSource revoked = ticket(turn.getAssistantMessageId(), null, 2);
        candidates(List.of(turn));
        when(sources.listByMessageId(turn.getAssistantMessageId())).thenReturn(List.of(allowed, revoked));
        when(history.isUsable(allowed.getId(), prepared.getSessionId(), prepared.getUserId())).thenReturn(true);

        assertThat(service.load(prepared).getTurns()).isEmpty();
        assertThat(service.load(prepared).getSources()).isEmpty();
    }

    @Test
    void skipsOversizedWholeTurnAndKeepsOlderUsableTurn() {
        properties.setHistoryMaxCharacters(400);
        CompletedChatTurn oversized = turn(3, "太长".repeat(500), "回答");
        CompletedChatTurn older = turn(1, "早先问题", "回答[1]");
        candidates(List.of(oversized, older));
        when(sources.listByMessageId(oversized.getAssistantMessageId()))
                .thenReturn(List.of(ticket(oversized.getAssistantMessageId(), null, 1)));
        when(sources.listByMessageId(older.getAssistantMessageId()))
                .thenReturn(List.of(ticket(older.getAssistantMessageId(), null, 1)));
        when(history.isUsable(any(), any(), any())).thenReturn(true);

        assertThat(service.load(prepared).getTurns()).singleElement()
                .satisfies(item -> assertThat(item.getQuestion()).isEqualTo("早先问题"));
    }

    @Test
    void rejectsScopeRevocationAndRevalidatesAllRegisteredSources() {
        when(access.allReadable(prepared.getUserId(), List.of(kbId))).thenReturn(false);
        assertThatThrownBy(() -> service.load(prepared)).isInstanceOf(AssistantAgentBudgetException.class);
        when(access.allReadable(prepared.getUserId(), List.of(kbId))).thenReturn(true);
        when(sources.listByMessageId(prepared.getAssistantMessageId()))
                .thenReturn(List.of(ticket(prepared.getAssistantMessageId(), null, 1)));
        assertThatThrownBy(() -> service.ensureReadable(prepared)).isInstanceOf(AssistantAgentBudgetException.class);
    }

    @Test
    void rejectsSourceLostBetweenSelectionAndRegistration() {
        MessageSource source = ticket(prepared.getAssistantMessageId(), UUID.randomUUID(), 1);
        when(calls.lockStartContext(any(), any(), any(), any())).thenReturn(true);
        when(calls.nextCitationNo(prepared.getAssistantMessageId())).thenReturn(1);
        assertThatThrownBy(() -> service.register(prepared,
                new AssistantConversationContext(List.of(), List.of(source))))
                .isInstanceOf(AssistantAgentBudgetException.class);
    }

    private void candidates(List<CompletedChatTurn> values) {
        when(messages.listCompletedTurnsBefore(eq(prepared.getSessionId()), eq(prepared.getUserId()),
                eq(prepared.getUserMessageId()), isNull(), anyInt())).thenReturn(values);
    }

    private CompletedChatTurn turn(long sequence, String question, String answer) {
        return new CompletedChatTurn(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sequence,
                question, answer);
    }

    private MessageSource ticket(UUID messageId, UUID rootId, int number) {
        return new MessageSource(UUID.randomUUID(), messageId, rootId, number, false, MessageSourceTypeEnum.TICKET,
                kbId, null, UUID.randomUUID(), UUID.randomUUID(), "{}", "{\"ticketNo\":\"T10086\"}",
                OffsetDateTime.now());
    }
}
