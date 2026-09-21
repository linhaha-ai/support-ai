package com.haha.supportai.conversation.service.run;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.FutureTask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.conversation.entity.dto.ChatRunCancellationResult;
import com.haha.supportai.conversation.exception.ChatRunNotFoundException;
import com.haha.supportai.data.conversation.entity.po.ChatMessage;
import com.haha.supportai.data.conversation.entity.po.ChatRun;
import com.haha.supportai.data.conversation.enums.ChatMessageRoleEnum;
import com.haha.supportai.data.conversation.enums.ChatMessageStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatRunRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.security.ActiveAccountGuard;
import com.haha.supportai.knowledge.contract.KnowledgeScopeAccess;
import com.haha.supportai.knowledge.service.source.DocumentSourceQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ChatRunControlServiceTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID SESSION_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID KB_ID = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID USER_MESSAGE_ID = UUID.fromString("10000000-0000-4000-8000-000000000005");
    private static final UUID ASSISTANT_MESSAGE_ID = UUID.fromString("10000000-0000-4000-8000-000000000006");

    @Mock
    ChatRunRepository chatRunRepository;

    @Mock
    ChatMessageRepository chatMessageRepository;

    @Mock
    ChatSessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;

    @Mock
    MessageSourceRepository messageSourceRepository;

    @Mock
    ActiveAccountGuard activeAccountGuard;

    @Mock
    KnowledgeScopeAccess knowledgeScopeAccess;

    @Mock
    DocumentSourceQueryService documentSourceQueryService;

    @Mock
    ChatRunControlPersistenceService persistenceService;

    private ChatRunExecutionRegistry executionRegistry;

    private ChatRunControlService controlService;

    @BeforeEach
    void setUp() {
        executionRegistry = new ChatRunExecutionRegistry();
        controlService = new ChatRunControlService(chatRunRepository, chatMessageRepository,
                sessionKnowledgeBaseRepository, messageSourceRepository, activeAccountGuard, knowledgeScopeAccess,
                documentSourceQueryService, persistenceService, executionRegistry, new ObjectMapper());
        given(activeAccountGuard.currentUser()).willReturn(new AuthenticatedUser(USER_ID, "tester", "测试用户",
                UserRoleEnum.USER));
    }

    @Test
    void revokedKnowledgeScopeHidesAnswerAndSourcesButReturnsPersistedStatus() {
        given(chatRunRepository.findOwnedById(RUN_ID, USER_ID)).willReturn(Optional.of(run(ChatRunStatusEnum.RUNNING)));
        given(chatMessageRepository.listByRunId(RUN_ID)).willReturn(messages());
        given(sessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(SESSION_ID)).willReturn(List.of(KB_ID));
        given(knowledgeScopeAccess.allReadable(USER_ID, List.of(KB_ID))).willReturn(false);

        var response = controlService.getStatus(RUN_ID);

        assertThat(response.getStatus()).isEqualTo(ChatRunStatusEnum.RUNNING);
        assertThat(response.isContentReadable()).isFalse();
        assertThat(response.getAnswer()).isNull();
        assertThat(response.getSources()).isEmpty();
        assertThat(response.isCancellable()).isTrue();
        then(messageSourceRepository).shouldHaveNoInteractions();
    }

    @Test
    void runOutsideCurrentOwnerScopeIsReportedAsNotFound() {
        given(chatRunRepository.findOwnedById(RUN_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> controlService.getStatus(RUN_ID))
                .isInstanceOf(ChatRunNotFoundException.class);

        then(chatMessageRepository).shouldHaveNoInteractions();
    }

    @Test
    void cancellationIsAllowedWithoutKnowledgeScopeCheckAndStopsRegisteredTaskAfterPersistence() {
        given(persistenceService.cancelOwned(RUN_ID, USER_ID))
                .willReturn(new ChatRunCancellationResult(RUN_ID, ChatRunStatusEnum.CANCELLED, true));
        FutureTask<Void> task = new FutureTask<>(() -> null);
        executionRegistry.register(RUN_ID, task, () -> { });

        var result = controlService.cancel(RUN_ID);

        assertThat(result.isNewlyCancelled()).isTrue();
        assertThat(task.isCancelled()).isTrue();
        then(persistenceService).should().cancelOwned(eq(RUN_ID), eq(USER_ID));
        then(knowledgeScopeAccess).shouldHaveNoInteractions();
    }

    private ChatRun run(ChatRunStatusEnum status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T08:00:00Z");
        return new ChatRun(RUN_ID, SESSION_ID, UUID.randomUUID(), "request", status, "{}", null, "{}", null,
                null, null, now, null, now, now);
    }

    private List<ChatMessage> messages() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T08:00:00Z");
        ChatMessage user = new ChatMessage(USER_MESSAGE_ID, RUN_ID, SESSION_ID, 1, ChatMessageRoleEnum.USER,
                "问题", ChatMessageStatusEnum.COMPLETED, now, now);
        ChatMessage assistant = new ChatMessage(ASSISTANT_MESSAGE_ID, RUN_ID, SESSION_ID, 2,
                ChatMessageRoleEnum.ASSISTANT, "部分回答", ChatMessageStatusEnum.STREAMING, now, now);
        return List.of(user, assistant);
    }
}
