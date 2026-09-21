package com.haha.supportai.conversation.service.run;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.SubmitMessageCommand;
import com.haha.supportai.conversation.exception.ChatIdempotencyConflictException;
import com.haha.supportai.conversation.exception.ChatRunInProgressException;
import com.haha.supportai.conversation.exception.ChatSessionScopeRevokedException;
import com.haha.supportai.data.conversation.entity.po.ChatMessage;
import com.haha.supportai.data.conversation.entity.po.ChatRun;
import com.haha.supportai.data.conversation.entity.po.ChatSession;
import com.haha.supportai.data.conversation.enums.ChatMessageRoleEnum;
import com.haha.supportai.data.conversation.enums.ChatMessageStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatRunRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionRepository;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.security.ActiveAccountGuard;
import com.haha.supportai.knowledge.contract.KnowledgeScopeAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class ChatRunServiceTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID SESSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final UUID KB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
    private static final UUID CLIENT_REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");

    @Mock
    ChatSessionRepository chatSessionRepository;

    @Mock
    ChatSessionKnowledgeBaseRepository chatSessionKnowledgeBaseRepository;

    @Mock
    ChatRunRepository chatRunRepository;

    @Mock
    ChatMessageRepository chatMessageRepository;

    @Mock
    ActiveAccountGuard activeAccountGuard;

    @Mock
    KnowledgeScopeAccess knowledgeScopeAccess;

    @InjectMocks
    ChatRunService chatRunService;

    @Test
    void preparesOneQueuedRunWithCompletedUserAndStreamingAssistantMessages() {
        arrangeAccessibleSession();
        given(chatRunRepository.findBySessionIdAndClientRequestId(SESSION_ID, CLIENT_REQUEST_ID))
                .willReturn(Optional.empty());
        given(chatRunRepository.findActiveBySessionId(SESSION_ID)).willReturn(Optional.empty());
        given(chatSessionRepository.advanceNextMessageSeq(SESSION_ID, 1)).willReturn(true);

        var response = chatRunService.prepare(SESSION_ID, new SubmitMessageCommand(CLIENT_REQUEST_ID, "  如何处理退款失败？  "));

        ArgumentCaptor<ChatRun> runCaptor = ArgumentCaptor.forClass(ChatRun.class);
        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        then(chatRunRepository).should().insert(runCaptor.capture());
        then(chatMessageRepository).should(times(2)).insert(messageCaptor.capture());
        ChatRun run = runCaptor.getValue();
        List<ChatMessage> messages = messageCaptor.getAllValues();
        assertThat(response.isReused()).isFalse();
        assertThat(response.getRunId()).isEqualTo(run.getId());
        assertThat(response.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(response.getQuestion()).isEqualTo("如何处理退款失败？");
        assertThat(run.getStatus()).isEqualTo(ChatRunStatusEnum.QUEUED);
        assertThat(run.getRequestHash()).hasSize(64);
        assertThat(messages).extracting(ChatMessage::getSequence).containsExactly(1L, 2L);
        assertThat(messages).extracting(ChatMessage::getRole)
                .containsExactly(ChatMessageRoleEnum.USER, ChatMessageRoleEnum.ASSISTANT);
        assertThat(messages).extracting(ChatMessage::getStatus)
                .containsExactly(ChatMessageStatusEnum.COMPLETED, ChatMessageStatusEnum.STREAMING);
        assertThat(messages.getFirst().getContent()).isEqualTo("如何处理退款失败？");
        then(chatSessionRepository).should().advanceNextMessageSeq(SESSION_ID, 1);
    }

    @Test
    void reusesRunWhenClientRequestAndNormalizedPayloadMatch() {
        arrangeAccessibleSession();
        ChatRun existingRun = run(requestHash("同一问题"));
        ChatMessage userMessage = message(existingRun.getId(), ChatMessageRoleEnum.USER, 1);
        ChatMessage assistantMessage = message(existingRun.getId(), ChatMessageRoleEnum.ASSISTANT, 2);
        given(chatRunRepository.findBySessionIdAndClientRequestId(SESSION_ID, CLIENT_REQUEST_ID))
                .willReturn(Optional.of(existingRun));
        given(chatMessageRepository.listByRunId(existingRun.getId())).willReturn(List.of(userMessage, assistantMessage));

        var response = chatRunService.prepare(SESSION_ID, new SubmitMessageCommand(CLIENT_REQUEST_ID, "同一问题"));

        assertThat(response.isReused()).isTrue();
        assertThat(response.getRunId()).isEqualTo(existingRun.getId());
        assertThat(response.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(response.getQuestion()).isEqualTo("同一问题");
        then(chatSessionRepository).should(never()).advanceNextMessageSeq(SESSION_ID, 1);
        then(chatRunRepository).should(never()).insert(any());
    }

    @Test
    void rejectsSameClientRequestIdWhenPayloadHashDiffers() {
        arrangeAccessibleSession();
        given(chatRunRepository.findBySessionIdAndClientRequestId(SESSION_ID, CLIENT_REQUEST_ID))
                .willReturn(Optional.of(run("different")));

        assertThatThrownBy(() -> chatRunService.prepare(SESSION_ID,
                new SubmitMessageCommand(CLIENT_REQUEST_ID, "不同问题")))
                .isInstanceOf(ChatIdempotencyConflictException.class);
    }

    @Test
    void rejectsNewMessageWhileAnotherRunIsActive() {
        arrangeAccessibleSession();
        given(chatRunRepository.findBySessionIdAndClientRequestId(SESSION_ID, CLIENT_REQUEST_ID))
                .willReturn(Optional.empty());
        given(chatRunRepository.findActiveBySessionId(SESSION_ID)).willReturn(Optional.of(run("active")));

        assertThatThrownBy(() -> chatRunService.prepare(SESSION_ID,
                new SubmitMessageCommand(CLIENT_REQUEST_ID, "新的问题")))
                .isInstanceOf(ChatRunInProgressException.class);
    }

    @Test
    void rejectsRunWhenFixedKnowledgeScopeHasBeenRevoked() {
        given(activeAccountGuard.currentUser()).willReturn(currentUser());
        given(chatSessionRepository.findOwnedByIdForUpdate(SESSION_ID, USER_ID)).willReturn(Optional.of(session()));
        given(chatSessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(SESSION_ID)).willReturn(List.of(KB_ID));
        given(knowledgeScopeAccess.lockAllReadable(USER_ID, List.of(KB_ID))).willReturn(false);

        assertThatThrownBy(() -> chatRunService.prepare(SESSION_ID,
                new SubmitMessageCommand(CLIENT_REQUEST_ID, "撤权后的问题")))
                .isInstanceOf(ChatSessionScopeRevokedException.class);

        then(chatRunRepository).shouldHaveNoInteractions();
    }

    @Test
    void prepareAlwaysCommitsBeforeRagExecutionStarts() throws NoSuchMethodException {
        Transactional transactional = ChatRunService.class
                .getDeclaredMethod("prepare", UUID.class, SubmitMessageCommand.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private void arrangeAccessibleSession() {
        given(activeAccountGuard.currentUser()).willReturn(currentUser());
        given(chatSessionRepository.findOwnedByIdForUpdate(SESSION_ID, USER_ID)).willReturn(Optional.of(session()));
        given(chatSessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(SESSION_ID)).willReturn(List.of(KB_ID));
        given(knowledgeScopeAccess.lockAllReadable(USER_ID, List.of(KB_ID))).willReturn(true);
    }

    private ChatSession session() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T08:00:00Z");
        return new ChatSession(SESSION_ID, USER_ID, "新会话", ChatSessionModeEnum.RAG, 1, null, now, now);
    }

    private ChatRun run(String requestHash) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T08:00:00Z");
        return new ChatRun(UUID.randomUUID(), SESSION_ID, CLIENT_REQUEST_ID, requestHash, ChatRunStatusEnum.QUEUED,
                "{\"mode\":\"RAG\"}", null, "{}", null, null, null, null, null, now, now);
    }

    private ChatMessage message(UUID runId, ChatMessageRoleEnum role, long sequence) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T08:00:00Z");
        return new ChatMessage(UUID.randomUUID(), runId, SESSION_ID, sequence, role,
                role == ChatMessageRoleEnum.USER ? "同一问题" : "", ChatMessageStatusEnum.COMPLETED, now, now);
    }

    private AuthenticatedUser currentUser() {
        return new AuthenticatedUser(USER_ID, "support-user", "支持用户", UserRoleEnum.USER);
    }

    private String requestHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(("f10-v1\n" + content)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
