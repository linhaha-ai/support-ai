package com.haha.supportai.data.conversation.repository;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.conversation.entity.po.ChatMessage;
import com.haha.supportai.data.conversation.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChatMessageRepository {

    private final ChatMessageMapper chatMessageMapper;

    public ChatMessage insert(ChatMessage chatMessage) {
        chatMessageMapper.insert(chatMessage);
        return chatMessage;
    }

    public List<ChatMessage> listByRunId(UUID runId) {
        return chatMessageMapper.selectByRunId(runId);
    }
}
