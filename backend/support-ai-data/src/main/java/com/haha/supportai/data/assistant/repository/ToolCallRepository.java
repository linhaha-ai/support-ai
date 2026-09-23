package com.haha.supportai.data.assistant.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.assistant.entity.po.ToolCall;
import com.haha.supportai.data.assistant.mapper.ToolCallMapper;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ToolCallRepository {

    private final ToolCallMapper toolCallMapper;

    public boolean lockStartContext(UUID runId, UUID sessionId, UUID userId, UUID assistantMessageId) {
        return toolCallMapper.lockStartContext(runId, sessionId, userId, assistantMessageId) == 1;
    }

    public boolean insertStarted(ToolCall toolCall, UUID sessionId, UUID userId, UUID assistantMessageId) {
        return toolCallMapper.insertStarted(toolCall, sessionId, userId, assistantMessageId) == 1;
    }

    public boolean lockExecutionContext(UUID runId, UUID sessionId, UUID userId, UUID assistantMessageId,
            UUID toolCallId) {
        return toolCallMapper.lockExecutionContext(runId, sessionId, userId, assistantMessageId, toolCallId) == 1;
    }

    public int nextCitationNo(UUID assistantMessageId) {
        return toolCallMapper.selectNextCitationNo(assistantMessageId);
    }

    public boolean completeSuccess(UUID runId, UUID toolCallId, String resultSnapshot,
            OffsetDateTime finishedAt) {
        return toolCallMapper.completeSuccess(runId, toolCallId, resultSnapshot, finishedAt) == 1;
    }

    public boolean completeFailure(UUID runId, UUID toolCallId, String errorCode, OffsetDateTime finishedAt) {
        return toolCallMapper.completeFailure(runId, toolCallId, errorCode, finishedAt) == 1;
    }

    public int insertSources(UUID runId, UUID sessionId, UUID userId, UUID assistantMessageId,
            List<MessageSource> sources) {
        if (sources.isEmpty()) {
            return 0;
        }
        return toolCallMapper.insertToolSources(runId, sessionId, userId, assistantMessageId, sources);
    }

    public Optional<ToolCall> findById(UUID toolCallId) {
        return toolCallMapper.selectById(toolCallId);
    }
}
