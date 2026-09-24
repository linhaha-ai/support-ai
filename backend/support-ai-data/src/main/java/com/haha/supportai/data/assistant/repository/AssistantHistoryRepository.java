package com.haha.supportai.data.assistant.repository;

import java.util.UUID;

import com.haha.supportai.data.assistant.mapper.AssistantHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AssistantHistoryRepository {

    private final AssistantHistoryMapper mapper;

    public boolean isUsable(UUID sourceId, UUID sessionId, UUID userId) {
        return mapper.countUsableSource(sourceId, sessionId, userId) == 1;
    }

    public boolean inherit(UUID rootSourceId, UUID sessionId, UUID userId, UUID runId,
            UUID assistantMessageId, UUID newSourceId, int citationNo) {
        return mapper.insertInheritedSource(rootSourceId, sessionId, userId, runId, assistantMessageId,
                newSourceId, citationNo) == 1;
    }
}
