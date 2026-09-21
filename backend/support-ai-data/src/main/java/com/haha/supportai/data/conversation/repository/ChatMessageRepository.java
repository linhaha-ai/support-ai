package com.haha.supportai.data.conversation.repository;

import java.time.OffsetDateTime;
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

    public List<ChatMessage> listOwnedSessionPage(UUID sessionId, UUID userId, long afterSequence, int limit) {
        return chatMessageMapper.selectOwnedSessionPage(sessionId, userId, afterSequence, limit);
    }

    public boolean appendAssistantContent(UUID runId, UUID messageId, String delta, OffsetDateTime updatedAt) {
        return chatMessageMapper.appendAssistantContent(runId, messageId, delta, updatedAt) == 1;
    }

    public boolean completeAssistant(UUID runId, UUID messageId, String content, OffsetDateTime completedAt) {
        return chatMessageMapper.completeAssistant(runId, messageId, content, completedAt) == 1;
    }

    public boolean failAssistant(UUID runId, UUID messageId, OffsetDateTime failedAt) {
        return failAssistant(runId, messageId, null, failedAt);
    }

    public boolean failAssistant(UUID runId, UUID messageId, String partialContent, OffsetDateTime failedAt) {
        return chatMessageMapper.failAssistant(runId, messageId, partialContent, failedAt) == 1;
    }

    public boolean cancelAssistant(UUID runId, UUID messageId, OffsetDateTime cancelledAt) {
        return chatMessageMapper.cancelAssistant(runId, messageId, cancelledAt) == 1;
    }

    public int interruptAssistants(OffsetDateTime recoveredAt) {
        return chatMessageMapper.interruptAssistants(recoveredAt);
    }
}
