package com.haha.supportai.data.conversation.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.conversation.entity.po.ChatSession;
import com.haha.supportai.data.conversation.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChatSessionRepository {

    private final ChatSessionMapper chatSessionMapper;

    public ChatSession insert(ChatSession chatSession) {
        chatSessionMapper.insert(chatSession);
        return chatSession;
    }

    public Optional<ChatSession> findOwnedByIdForUpdate(UUID sessionId, UUID userId) {
        return chatSessionMapper.selectOwnedByIdForUpdate(sessionId, userId);
    }

    public Optional<ChatSession> findOwnedById(UUID sessionId, UUID userId) {
        return chatSessionMapper.selectOwnedById(sessionId, userId);
    }

    public List<ChatSession> listOwnedPage(UUID userId, OffsetDateTime beforeUpdatedAt, UUID beforeId, int limit) {
        return chatSessionMapper.selectOwnedPage(userId, beforeUpdatedAt, beforeId, limit);
    }

    public boolean updateOwnedTitle(UUID sessionId, UUID userId, String title, OffsetDateTime updatedAt) {
        return chatSessionMapper.updateOwnedTitle(sessionId, userId, title, updatedAt) == 1;
    }

    public boolean softDeleteOwned(UUID sessionId, UUID userId, OffsetDateTime deletedAt) {
        return chatSessionMapper.softDeleteOwned(sessionId, userId, deletedAt) == 1;
    }

    public boolean advanceNextMessageSeq(UUID sessionId, long expectedNextMessageSeq) {
        return chatSessionMapper.advanceNextMessageSequence(sessionId, expectedNextMessageSeq) == 1;
    }
}
