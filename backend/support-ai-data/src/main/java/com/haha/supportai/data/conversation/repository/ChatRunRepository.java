package com.haha.supportai.data.conversation.repository;

import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.conversation.entity.po.ChatRun;
import com.haha.supportai.data.conversation.mapper.ChatRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChatRunRepository {

    private final ChatRunMapper chatRunMapper;

    public ChatRun insert(ChatRun chatRun) {
        chatRunMapper.insertChatRun(chatRun);
        return chatRun;
    }

    public Optional<ChatRun> findBySessionIdAndClientRequestId(UUID sessionId, UUID clientRequestId) {
        return chatRunMapper.selectBySessionIdAndClientRequestId(sessionId, clientRequestId);
    }

    public Optional<ChatRun> findActiveBySessionId(UUID sessionId) {
        return chatRunMapper.selectActiveBySessionId(sessionId);
    }
}
