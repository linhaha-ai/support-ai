package com.haha.supportai.data.conversation.repository;

import java.time.OffsetDateTime;
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

    public boolean markRunning(UUID runId, UUID sessionId, UUID userMessageId, UUID assistantMessageId,
            String question, OffsetDateTime startedAt) {
        return chatRunMapper.markRunning(runId, sessionId, userMessageId, assistantMessageId, question, startedAt) == 1;
    }

    public boolean complete(UUID runId, String usage, String metrics, OffsetDateTime finishedAt) {
        return chatRunMapper.complete(runId, usage, metrics, finishedAt) == 1;
    }

    public boolean fail(UUID runId, String errorCode, String errorDetail, OffsetDateTime finishedAt) {
        return fail(runId, errorCode, errorDetail, null, null, finishedAt);
    }

    public boolean fail(UUID runId, String errorCode, String errorDetail, String usage, String metrics,
            OffsetDateTime finishedAt) {
        return chatRunMapper.fail(runId, errorCode, errorDetail, usage, metrics, finishedAt) == 1;
    }
}
