package com.haha.supportai.conversation.service.run;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.conversation.exception.ChatRunNotFoundException;
import com.haha.supportai.data.conversation.entity.po.ChatMessage;
import com.haha.supportai.data.conversation.entity.po.ChatRun;
import com.haha.supportai.data.conversation.enums.ChatMessageRoleEnum;
import com.haha.supportai.data.conversation.enums.ChatMessageStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatRunRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ChatRunControlPersistenceServiceTest {

    private static final UUID RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID USER_MESSAGE_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID ASSISTANT_MESSAGE_ID = UUID.fromString("10000000-0000-4000-8000-000000000004");

    @Mock
    ChatRunRepository chatRunRepository;

    @Mock
    ChatMessageRepository chatMessageRepository;

    @InjectMocks
    ChatRunControlPersistenceService persistenceService;

    @Test
    void ownerCancellationPersistsRunBeforeInterruptingAssistantMessage() {
        given(chatRunRepository.findOwnedByIdForUpdate(RUN_ID, USER_ID)).willReturn(Optional.of(run(ChatRunStatusEnum.RUNNING)));
        given(chatMessageRepository.listByRunId(RUN_ID)).willReturn(messages());
        given(chatRunRepository.cancel(eq(RUN_ID), any())).willReturn(true);
        given(chatMessageRepository.cancelAssistant(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID), any())).willReturn(true);

        var result = persistenceService.cancelOwned(RUN_ID, USER_ID);

        assertThat(result.isNewlyCancelled()).isTrue();
        assertThat(result.getStatus()).isEqualTo(ChatRunStatusEnum.CANCELLED);
        ArgumentCaptor<OffsetDateTime> cancelledAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        then(chatRunRepository).should().cancel(eq(RUN_ID), cancelledAt.capture());
        then(chatMessageRepository).should().cancelAssistant(eq(RUN_ID), eq(ASSISTANT_MESSAGE_ID),
                eq(cancelledAt.getValue()));
    }

    @Test
    void completedRunCannotBeRewrittenAsCancelled() {
        given(chatRunRepository.findOwnedByIdForUpdate(RUN_ID, USER_ID))
                .willReturn(Optional.of(run(ChatRunStatusEnum.COMPLETED)));

        var result = persistenceService.cancelOwned(RUN_ID, USER_ID);

        assertThat(result.isNewlyCancelled()).isFalse();
        assertThat(result.getStatus()).isEqualTo(ChatRunStatusEnum.COMPLETED);
        then(chatMessageRepository).shouldHaveNoInteractions();
        then(chatRunRepository).should(never()).cancel(any(), any());
    }

    @Test
    void cancellationRejectsRunOutsideOwnerScope() {
        given(chatRunRepository.findOwnedByIdForUpdate(RUN_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> persistenceService.cancelOwned(RUN_ID, USER_ID))
                .isInstanceOf(ChatRunNotFoundException.class);

        then(chatMessageRepository).shouldHaveNoInteractions();
    }

    @Test
    void recoveryMarksOnlyPersistedActiveRunsBeforeStartupBoundary() {
        OffsetDateTime boundary = OffsetDateTime.parse("2026-09-21T08:00:00Z");
        given(chatRunRepository.interruptActiveBefore(eq(boundary), any())).willReturn(2);
        given(chatMessageRepository.interruptAssistants(any())).willReturn(2);

        assertThat(persistenceService.recoverInterrupted(boundary)).isEqualTo(2);

        then(chatRunRepository).should().interruptActiveBefore(eq(boundary), any());
        then(chatMessageRepository).should().interruptAssistants(any());
    }

    @Test
    void recoveryFailsTransactionWhenRunAndAssistantCountsDiffer() {
        OffsetDateTime boundary = OffsetDateTime.parse("2026-09-21T08:00:00Z");
        given(chatRunRepository.interruptActiveBefore(eq(boundary), any())).willReturn(2);
        given(chatMessageRepository.interruptAssistants(any())).willReturn(1);

        assertThatThrownBy(() -> persistenceService.recoverInterrupted(boundary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("中断运行与助手消息数量不一致");
    }

    @Test
    void controlPersistenceUsesIndependentTransactions() throws NoSuchMethodException {
        assertRequiresNew("cancelOwned", UUID.class, UUID.class);
        assertRequiresNew("recoverInterrupted", OffsetDateTime.class);
    }

    private void assertRequiresNew(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Transactional transactional = ChatRunControlPersistenceService.class.getDeclaredMethod(methodName, parameterTypes)
                .getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private ChatRun run(ChatRunStatusEnum status) {
        ChatRun run = new ChatRun();
        run.setId(RUN_ID);
        run.setStatus(status);
        return run;
    }

    private List<ChatMessage> messages() {
        ChatMessage user = new ChatMessage();
        user.setId(USER_MESSAGE_ID);
        user.setRole(ChatMessageRoleEnum.USER);
        user.setStatus(ChatMessageStatusEnum.COMPLETED);
        ChatMessage assistant = new ChatMessage();
        assistant.setId(ASSISTANT_MESSAGE_ID);
        assistant.setRole(ChatMessageRoleEnum.ASSISTANT);
        assistant.setStatus(ChatMessageStatusEnum.STREAMING);
        return List.of(user, assistant);
    }
}
