package com.haha.supportai.data.conversation.repository;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.conversation.entity.po.HistoricalMessageSource;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.mapper.MessageSourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MessageSourceRepository {

    private final MessageSourceMapper messageSourceMapper;

    public int insertAll(UUID runId, List<MessageSource> sources) {
        if (sources.isEmpty()) {
            return 0;
        }
        return messageSourceMapper.insertAll(runId, sources);
    }

    public List<MessageSource> listByMessageId(UUID messageId) {
        return messageSourceMapper.selectByMessageId(messageId);
    }

    public List<HistoricalMessageSource> listHistoricalSources(UUID assistantMessageId, UUID sessionId, UUID userId) {
        return messageSourceMapper.selectHistoricalSources(assistantMessageId, sessionId, userId);
    }

    public int markCited(UUID messageId, List<Integer> citationNumbers) {
        if (citationNumbers.isEmpty()) {
            return 0;
        }
        return messageSourceMapper.markCited(messageId, citationNumbers);
    }
}
