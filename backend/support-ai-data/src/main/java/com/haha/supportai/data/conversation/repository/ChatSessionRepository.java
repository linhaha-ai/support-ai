package com.haha.supportai.data.conversation.repository;

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

    public boolean advanceNextMessageSeq(UUID sessionId, long expectedNextMessageSeq) {
        return chatSessionMapper.advanceNextMessageSequence(sessionId, expectedNextMessageSeq) == 1;
    }
}
