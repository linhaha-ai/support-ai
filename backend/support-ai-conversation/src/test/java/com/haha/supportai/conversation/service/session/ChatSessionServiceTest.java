package com.haha.supportai.conversation.service.session;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.CreateChatSessionRequest;
import com.haha.supportai.conversation.entity.dto.UpdateChatSessionRequest;
import com.haha.supportai.conversation.exception.ChatSessionActiveRunException;
import com.haha.supportai.conversation.exception.ChatSessionInvalidRequestException;
import com.haha.supportai.conversation.exception.ChatSessionKnowledgeBaseUnavailableException;
import com.haha.supportai.conversation.exception.ChatSessionNotFoundException;
import com.haha.supportai.data.conversation.entity.po.ChatMessage;
import com.haha.supportai.data.conversation.entity.po.ChatRun;
import com.haha.supportai.data.conversation.entity.po.ChatSession;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.ChatMessageRoleEnum;
import com.haha.supportai.data.conversation.enums.ChatMessageStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatRunRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionRepository;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.security.ActiveAccountGuard;
import com.haha.supportai.knowledge.contract.KnowledgeScopeAccess;
import com.haha.supportai.knowledge.entity.vo.DocumentSourceAvailabilityResponse;
import com.haha.supportai.knowledge.service.source.DocumentSourceQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID FIRST_KB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final UUID SECOND_KB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

    @Mock
    ChatSessionRepository chatSessionRepository;

    @Mock
    ChatSessionKnowledgeBaseRepository chatSessionKnowledgeBaseRepository;

    @Mock
    ChatRunRepository chatRunRepository;

    @Mock
    ChatMessageRepository chatMessageRepository;

    @Mock
    MessageSourceRepository messageSourceRepository;

    @Mock
    ActiveAccountGuard activeAccountGuard;

    @Mock
    KnowledgeScopeAccess knowledgeScopeAccess;

    @Mock
    DocumentSourceQueryService documentSourceQueryService;

    @InjectMocks
    ChatSessionService chatSessionService;

    @Test
    void createsRagSessionWithSortedFixedKnowledgeBaseScope() {
        CreateChatSessionRequest request = request(List.of(SECOND_KB_ID, FIRST_KB_ID), ChatSessionModeEnum.RAG);
        given(activeAccountGuard.currentUser()).willReturn(currentUser());
        given(knowledgeScopeAccess.lockAllReadable(USER_ID, List.of(FIRST_KB_ID, SECOND_KB_ID))).willReturn(true);

        var response = chatSessionService.create(request);

        ArgumentCaptor<ChatSession> sessionCaptor = ArgumentCaptor.forClass(ChatSession.class);
        then(chatSessionRepository).should().insert(sessionCaptor.capture());
        ChatSession createdSession = sessionCaptor.getValue();
        assertThat(createdSession.getUserId()).isEqualTo(USER_ID);
        assertThat(createdSession.getMode()).isEqualTo(ChatSessionModeEnum.RAG);
        assertThat(createdSession.getNextMessageSeq()).isEqualTo(1);
        assertThat(response.getKnowledgeBaseIds()).containsExactly(FIRST_KB_ID, SECOND_KB_ID);
        then(chatSessionKnowledgeBaseRepository).should().insert(createdSession.getId(),
                List.of(FIRST_KB_ID, SECOND_KB_ID), createdSession.getCreatedAt());
    }

    @Test
    void rejectsEmptyDuplicateOrNonRagKnowledgeBaseScope() {
        assertThatThrownBy(() -> chatSessionService.create(request(List.of(), ChatSessionModeEnum.RAG)))
                .isInstanceOf(ChatSessionInvalidRequestException.class);
        assertThatThrownBy(() -> chatSessionService.create(request(List.of(FIRST_KB_ID, FIRST_KB_ID),
                ChatSessionModeEnum.RAG))).isInstanceOf(ChatSessionInvalidRequestException.class);
        assertThatThrownBy(() -> chatSessionService.create(request(List.of(FIRST_KB_ID), ChatSessionModeEnum.ASSISTANT)))
                .isInstanceOf(ChatSessionInvalidRequestException.class);
        then(knowledgeScopeAccess).shouldHaveNoInteractions();
    }

    @Test
    void rejectsKnowledgeBaseOutsideCurrentUsersReadableScope() {
        CreateChatSessionRequest request = request(List.of(FIRST_KB_ID), ChatSessionModeEnum.RAG);
        given(activeAccountGuard.currentUser()).willReturn(currentUser());
        given(knowledgeScopeAccess.lockAllReadable(USER_ID, List.of(FIRST_KB_ID))).willReturn(false);

        assertThatThrownBy(() -> chatSessionService.create(request))
                .isInstanceOf(ChatSessionKnowledgeBaseUnavailableException.class);

        then(chatSessionRepository).shouldHaveNoInteractions();
        then(chatSessionKnowledgeBaseRepository).shouldHaveNoInteractions();
    }

    @Test
    void listsOwnedSessionsWithOpaqueStableCursorAndScopeState() {
        OffsetDateTime firstUpdatedAt = OffsetDateTime.parse("2026-09-21T08:00:02Z");
        OffsetDateTime secondUpdatedAt = OffsetDateTime.parse("2026-09-21T08:00:01Z");
        ChatSession first = session(UUID.fromString("550e8400-e29b-41d4-a716-446655440011"), firstUpdatedAt);
        ChatSession second = session(UUID.fromString("550e8400-e29b-41d4-a716-446655440012"), secondUpdatedAt);
        ChatSession overflow = session(UUID.fromString("550e8400-e29b-41d4-a716-446655440013"),
                OffsetDateTime.parse("2026-09-21T08:00:00Z"));
        given(activeAccountGuard.currentUser()).willReturn(currentUser());
        given(chatSessionRepository.listOwnedPage(USER_ID, null, null, 3))
                .willReturn(List.of(first, second, overflow));
        given(chatSessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(first.getId()))
                .willReturn(List.of(FIRST_KB_ID));
        given(chatSessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(second.getId()))
                .willReturn(List.of(SECOND_KB_ID));
        given(knowledgeScopeAccess.allReadable(USER_ID, List.of(FIRST_KB_ID))).willReturn(true);
        given(knowledgeScopeAccess.allReadable(USER_ID, List.of(SECOND_KB_ID))).willReturn(false);

        var response = chatSessionService.list(2, null);

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).isContentReadable()).isTrue();
        assertThat(response.getItems().get(1).isContentReadable()).isFalse();
        assertThat(response.getNextCursor()).isNotBlank().doesNotContain(second.getId().toString());

        given(chatSessionRepository.listOwnedPage(USER_ID, secondUpdatedAt, second.getId(), 3))
                .willReturn(List.of());
        chatSessionService.list(2, response.getNextCursor());
        then(chatSessionRepository).should().listOwnedPage(USER_ID, secondUpdatedAt, second.getId(), 3);
    }

    @Test
    void trimsTitleWithoutChangingFixedScope() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440011");
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-09-21T08:00:00Z");
        ChatSession session = session(sessionId, updatedAt);
        UpdateChatSessionRequest request = new UpdateChatSessionRequest();
        request.setTitle("  退款问题  ");
        given(activeAccountGuard.currentUser()).willReturn(currentUser());
        given(chatSessionRepository.findOwnedByIdForUpdate(sessionId, USER_ID)).willReturn(Optional.of(session));
        given(chatSessionRepository.updateOwnedTitle(eq(sessionId), eq(USER_ID), eq("退款问题"), any()))
                .willReturn(true);
        given(chatSessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(sessionId))
                .willReturn(List.of(FIRST_KB_ID));
        given(knowledgeScopeAccess.allReadable(USER_ID, List.of(FIRST_KB_ID))).willReturn(true);

        var response = chatSessionService.updateTitle(sessionId, request);

        assertThat(response.getTitle()).isEqualTo("退款问题");
        assertThat(response.getMode()).isEqualTo(ChatSessionModeEnum.RAG);
        assertThat(response.getKnowledgeBaseIds()).containsExactly(FIRST_KB_ID);
    }

    @Test
    void refusesToDeleteSessionWithActiveRun() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440011");
        given(activeAccountGuard.currentUser()).willReturn(currentUser());
        given(chatSessionRepository.findOwnedByIdForUpdate(sessionId, USER_ID))
                .willReturn(Optional.of(session(sessionId, OffsetDateTime.parse("2026-09-21T08:00:00Z"))));
        given(chatRunRepository.findActiveBySessionId(sessionId)).willReturn(Optional.of(new ChatRun()));

        assertThatThrownBy(() -> chatSessionService.delete(sessionId))
                .isInstanceOf(ChatSessionActiveRunException.class);

        then(chatSessionRepository).should(never()).softDeleteOwned(any(), any(), any());
    }

    @Test
    void softDeletesOwnedIdleSessionAfterLockingIt() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440011");
        given(activeAccountGuard.currentUser()).willReturn(currentUser());
        given(chatSessionRepository.findOwnedByIdForUpdate(sessionId, USER_ID))
                .willReturn(Optional.of(session(sessionId, OffsetDateTime.parse("2026-09-21T08:00:00Z"))));
        given(chatRunRepository.findActiveBySessionId(sessionId)).willReturn(Optional.empty());
        given(chatSessionRepository.softDeleteOwned(eq(sessionId), eq(USER_ID), any())).willReturn(true);

        chatSessionService.delete(sessionId);

        then(chatSessionRepository).should().softDeleteOwned(eq(sessionId), eq(USER_ID), any());
    }

    @Test
    void hidesEveryMessageBodyAndSourceWhenAnyFixedKnowledgeBaseIsRevoked() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440011");
        ChatSession session = session(sessionId, OffsetDateTime.parse("2026-09-21T08:00:00Z"));
        ChatMessage userMessage = message(sessionId, 1, ChatMessageRoleEnum.USER, "退款怎么处理？");
        ChatMessage assistantMessage = message(sessionId, 2, ChatMessageRoleEnum.ASSISTANT, "请先核对状态");
        given(activeAccountGuard.currentUser()).willReturn(currentUser());
        given(chatSessionRepository.findOwnedById(sessionId, USER_ID)).willReturn(Optional.of(session));
        given(chatSessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(sessionId))
                .willReturn(List.of(FIRST_KB_ID, SECOND_KB_ID));
        given(knowledgeScopeAccess.allReadable(USER_ID, List.of(FIRST_KB_ID, SECOND_KB_ID))).willReturn(false);
        given(chatMessageRepository.listOwnedSessionPage(sessionId, USER_ID, 0, 21))
                .willReturn(List.of(userMessage, assistantMessage));

        var response = chatSessionService.listMessages(sessionId, null, null);

        assertThat(response.isContentReadable()).isFalse();
        assertThat(response.getItems()).allSatisfy(item -> {
            assertThat(item.getContent()).isNull();
            assertThat(item.getSources()).isEmpty();
        });
        then(messageSourceRepository).shouldHaveNoInteractions();
    }

    @Test
    void returnsCitedSourcesWithCurrentAuthorizationState() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440011");
        UUID chunkId = UUID.fromString("550e8400-e29b-41d4-a716-446655440021");
        ChatMessage assistantMessage = message(sessionId, 2, ChatMessageRoleEnum.ASSISTANT, "处理步骤[1]");
        MessageSource cited = new MessageSource(UUID.randomUUID(), assistantMessage.getId(), null, 1, true,
                MessageSourceTypeEnum.DOCUMENT, FIRST_KB_ID, chunkId, "{}", "{}", OffsetDateTime.now());
        MessageSource uncited = new MessageSource(UUID.randomUUID(), assistantMessage.getId(), null, 2, false,
                MessageSourceTypeEnum.DOCUMENT, FIRST_KB_ID, UUID.randomUUID(), "{}", "{}", OffsetDateTime.now());
        given(activeAccountGuard.currentUser()).willReturn(currentUser());
        given(chatSessionRepository.findOwnedById(sessionId, USER_ID))
                .willReturn(Optional.of(session(sessionId, OffsetDateTime.parse("2026-09-21T08:00:00Z"))));
        given(chatSessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(sessionId))
                .willReturn(List.of(FIRST_KB_ID));
        given(knowledgeScopeAccess.allReadable(USER_ID, List.of(FIRST_KB_ID))).willReturn(true);
        given(chatMessageRepository.listOwnedSessionPage(sessionId, USER_ID, 0, 21))
                .willReturn(List.of(assistantMessage));
        given(messageSourceRepository.listByMessageId(assistantMessage.getId()))
                .willReturn(List.of(cited, uncited));
        given(documentSourceQueryService.findAuthorizedSource(USER_ID, chunkId)).willReturn(Optional.of(
                new DocumentSourceAvailabilityResponse(FIRST_KB_ID, UUID.randomUUID(), "退款手册", UUID.randomUUID(), 1,
                        chunkId, 0, "退款", null, 0, 10, true, true)));

        var response = chatSessionService.listMessages(sessionId, null, null);

        assertThat(response.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getContent()).isEqualTo("处理步骤[1]");
            assertThat(item.getSources()).singleElement().satisfies(source -> {
                assertThat(source.getNumber()).isEqualTo(1);
                assertThat(source.getChunkId()).isEqualTo(chunkId);
                assertThat(source.isAvailable()).isTrue();
                assertThat(source.getPreviewPath()).isNotBlank();
            });
        });
    }

    @Test
    void keepsDeletedCitedSourceMetadataButMarksItUnavailable() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440011");
        UUID chunkId = UUID.fromString("550e8400-e29b-41d4-a716-446655440021");
        ChatMessage assistantMessage = message(sessionId, 2, ChatMessageRoleEnum.ASSISTANT, "历史回答[1]");
        MessageSource cited = new MessageSource(UUID.randomUUID(), assistantMessage.getId(), null, 1, true,
                MessageSourceTypeEnum.DOCUMENT, FIRST_KB_ID, chunkId, "{}", "{}", OffsetDateTime.now());
        given(activeAccountGuard.currentUser()).willReturn(currentUser());
        given(chatSessionRepository.findOwnedById(sessionId, USER_ID))
                .willReturn(Optional.of(session(sessionId, OffsetDateTime.parse("2026-09-21T08:00:00Z"))));
        given(chatSessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(sessionId))
                .willReturn(List.of(FIRST_KB_ID));
        given(knowledgeScopeAccess.allReadable(USER_ID, List.of(FIRST_KB_ID))).willReturn(true);
        given(chatMessageRepository.listOwnedSessionPage(sessionId, USER_ID, 0, 21))
                .willReturn(List.of(assistantMessage));
        given(messageSourceRepository.listByMessageId(assistantMessage.getId())).willReturn(List.of(cited));
        given(documentSourceQueryService.findAuthorizedSource(USER_ID, chunkId)).willReturn(Optional.of(
                new DocumentSourceAvailabilityResponse(FIRST_KB_ID, UUID.randomUUID(), "已删除退款手册",
                        UUID.randomUUID(), 1, chunkId, 0, "退款", null, 0, 10, false, false)));

        var response = chatSessionService.listMessages(sessionId, null, null);

        assertThat(response.getItems()).singleElement().satisfies(item ->
                assertThat(item.getSources()).singleElement().satisfies(source -> {
                    assertThat(source.getTitle()).isEqualTo("已删除退款手册");
                    assertThat(source.isAvailable()).isFalse();
                    assertThat(source.isCurrent()).isFalse();
                    assertThat(source.getPreviewPath()).isNull();
                }));
    }

    @Test
    void messageCursorContinuesAfterLastSequenceOfPreviousPage() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440011");
        ChatMessage first = message(sessionId, 1, ChatMessageRoleEnum.USER, "第一个问题");
        ChatMessage overflow = message(sessionId, 2, ChatMessageRoleEnum.USER, "下一条消息");
        given(activeAccountGuard.currentUser()).willReturn(currentUser());
        given(chatSessionRepository.findOwnedById(sessionId, USER_ID))
                .willReturn(Optional.of(session(sessionId, OffsetDateTime.parse("2026-09-21T08:00:00Z"))));
        given(chatSessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(sessionId))
                .willReturn(List.of(FIRST_KB_ID));
        given(knowledgeScopeAccess.allReadable(USER_ID, List.of(FIRST_KB_ID))).willReturn(true);
        given(chatMessageRepository.listOwnedSessionPage(sessionId, USER_ID, 0, 2))
                .willReturn(List.of(first, overflow));

        var firstPage = chatSessionService.listMessages(sessionId, 1, null);

        assertThat(firstPage.getItems()).singleElement()
                .satisfies(item -> assertThat(item.getSequence()).isEqualTo(1L));
        assertThat(firstPage.getNextCursor()).isNotBlank();
        given(chatMessageRepository.listOwnedSessionPage(sessionId, USER_ID, 1, 2)).willReturn(List.of());
        chatSessionService.listMessages(sessionId, 1, firstPage.getNextCursor());
        then(chatMessageRepository).should().listOwnedSessionPage(sessionId, USER_ID, 1, 2);
    }

    @Test
    void rejectsInvalidLimitsAndTamperedCursorsBeforeQueryingData() {
        String invalidNanoCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "s:1789977600:1000000000:550e8400-e29b-41d4-a716-446655440011"
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> chatSessionService.list(0, null))
                .isInstanceOf(ChatSessionInvalidRequestException.class);
        assertThatThrownBy(() -> chatSessionService.list(101, null))
                .isInstanceOf(ChatSessionInvalidRequestException.class);
        assertThatThrownBy(() -> chatSessionService.list(20, invalidNanoCursor))
                .isInstanceOf(ChatSessionInvalidRequestException.class);
        assertThatThrownBy(() -> chatSessionService.listMessages(UUID.randomUUID(), 20, "not-base64"))
                .isInstanceOf(ChatSessionInvalidRequestException.class);
        then(chatSessionRepository).shouldHaveNoInteractions();
    }

    @Test
    void nullSessionIdentifierUsesUniformNotFoundResult() {
        assertThatThrownBy(() -> chatSessionService.listMessages(null, null, null))
                .isInstanceOf(ChatSessionNotFoundException.class);
        then(activeAccountGuard).shouldHaveNoInteractions();
    }

    private CreateChatSessionRequest request(List<UUID> knowledgeBaseIds, ChatSessionModeEnum mode) {
        CreateChatSessionRequest request = new CreateChatSessionRequest();
        request.setKbIds(knowledgeBaseIds);
        request.setMode(mode);
        return request;
    }

    private AuthenticatedUser currentUser() {
        return new AuthenticatedUser(USER_ID, "support-user", "支持用户", UserRoleEnum.USER);
    }

    private ChatSession session(UUID sessionId, OffsetDateTime updatedAt) {
        return new ChatSession(sessionId, USER_ID, "新会话", ChatSessionModeEnum.RAG, 1, null,
                updatedAt.minusMinutes(1), updatedAt);
    }

    private ChatMessage message(UUID sessionId, long sequence, ChatMessageRoleEnum role, String content) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T08:00:00Z");
        return new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), sessionId, sequence, role, content,
                ChatMessageStatusEnum.COMPLETED, now, now);
    }
}
