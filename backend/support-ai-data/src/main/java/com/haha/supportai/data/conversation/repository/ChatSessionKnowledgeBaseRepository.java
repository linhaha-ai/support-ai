package com.haha.supportai.data.conversation.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.conversation.entity.po.ChatSessionKnowledgeBase;
import com.haha.supportai.data.conversation.mapper.ChatSessionKnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChatSessionKnowledgeBaseRepository {

    private final ChatSessionKnowledgeBaseMapper chatSessionKnowledgeBaseMapper;

    public ChatSessionKnowledgeBase insert(ChatSessionKnowledgeBase sessionKnowledgeBase) {
        chatSessionKnowledgeBaseMapper.insert(sessionKnowledgeBase);
        return sessionKnowledgeBase;
    }

    public void insert(UUID sessionId, List<UUID> knowledgeBaseIds, OffsetDateTime createdAt) {
        for (UUID knowledgeBaseId : knowledgeBaseIds) {
            insert(new ChatSessionKnowledgeBase(sessionId, knowledgeBaseId, createdAt));
        }
    }

    public List<UUID> listKnowledgeBaseIdsBySessionId(UUID sessionId) {
        return chatSessionKnowledgeBaseMapper.selectKnowledgeBaseIdsBySessionId(sessionId);
    }
}
