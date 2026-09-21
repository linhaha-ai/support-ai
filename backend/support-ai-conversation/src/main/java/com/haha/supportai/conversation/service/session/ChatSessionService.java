package com.haha.supportai.conversation.service.session;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.CreateChatSessionRequest;
import com.haha.supportai.conversation.entity.vo.ChatSessionResponse;
import com.haha.supportai.conversation.exception.ChatSessionInvalidRequestException;
import com.haha.supportai.conversation.exception.ChatSessionKnowledgeBaseUnavailableException;
import com.haha.supportai.data.conversation.entity.po.ChatSession;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.security.ActiveAccountGuard;
import com.haha.supportai.knowledge.contract.KnowledgeScopeAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private static final String DEFAULT_TITLE = "新会话";

    private final ChatSessionRepository chatSessionRepository;
    private final ChatSessionKnowledgeBaseRepository chatSessionKnowledgeBaseRepository;
    private final ActiveAccountGuard activeAccountGuard;
    private final KnowledgeScopeAccess knowledgeScopeAccess;

    @Transactional
    public ChatSessionResponse create(CreateChatSessionRequest request) {
        List<UUID> knowledgeBaseIds = normalizeKnowledgeBaseIds(request);
        AuthenticatedUser currentUser = activeAccountGuard.currentUser();
        if (!knowledgeScopeAccess.lockAllReadable(currentUser.getId(), knowledgeBaseIds)) {
            throw new ChatSessionKnowledgeBaseUnavailableException();
        }

        OffsetDateTime now = OffsetDateTime.now();
        ChatSession session = new ChatSession(UUID.randomUUID(), currentUser.getId(), DEFAULT_TITLE,
                ChatSessionModeEnum.RAG, 1, null, now, now);
        chatSessionRepository.insert(session);
        chatSessionKnowledgeBaseRepository.insert(session.getId(), knowledgeBaseIds, now);
        return ChatSessionResponse.from(session, knowledgeBaseIds);
    }

    private List<UUID> normalizeKnowledgeBaseIds(CreateChatSessionRequest request) {
        if (request == null || request.getMode() != ChatSessionModeEnum.RAG || request.getKbIds() == null
                || request.getKbIds().isEmpty() || request.getKbIds().stream().anyMatch(id -> id == null)) {
            throw new ChatSessionInvalidRequestException();
        }
        List<UUID> knowledgeBaseIds = new ArrayList<>(request.getKbIds());
        knowledgeBaseIds.sort(Comparator.naturalOrder());
        for (int index = 1; index < knowledgeBaseIds.size(); index++) {
            if (knowledgeBaseIds.get(index - 1).equals(knowledgeBaseIds.get(index))) {
                throw new ChatSessionInvalidRequestException();
            }
        }
        return List.copyOf(knowledgeBaseIds);
    }
}
