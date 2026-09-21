package com.haha.supportai.conversation.service.session;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.CreateChatSessionRequest;
import com.haha.supportai.conversation.exception.ChatSessionInvalidRequestException;
import com.haha.supportai.conversation.exception.ChatSessionKnowledgeBaseUnavailableException;
import com.haha.supportai.data.conversation.entity.po.ChatSession;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

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
    ActiveAccountGuard activeAccountGuard;

    @Mock
    KnowledgeScopeAccess knowledgeScopeAccess;

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

    private CreateChatSessionRequest request(List<UUID> knowledgeBaseIds, ChatSessionModeEnum mode) {
        CreateChatSessionRequest request = new CreateChatSessionRequest();
        request.setKbIds(knowledgeBaseIds);
        request.setMode(mode);
        return request;
    }

    private AuthenticatedUser currentUser() {
        return new AuthenticatedUser(USER_ID, "support-user", "支持用户", UserRoleEnum.USER);
    }
}
