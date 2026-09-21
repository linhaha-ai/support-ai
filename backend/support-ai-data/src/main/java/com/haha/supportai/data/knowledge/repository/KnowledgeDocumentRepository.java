package com.haha.supportai.data.knowledge.repository;

import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeDocument;
import com.haha.supportai.data.knowledge.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class KnowledgeDocumentRepository {

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    public KnowledgeDocument insert(KnowledgeDocument knowledgeDocument) {
        knowledgeDocumentMapper.insert(knowledgeDocument);
        return knowledgeDocument;
    }

    public boolean updatePendingVersion(UUID documentId, UUID versionId) {
        return knowledgeDocumentMapper.updatePendingVersion(documentId, versionId) == 1;
    }

    public long countActiveByKnowledgeBaseId(UUID knowledgeBaseId) {
        return knowledgeDocumentMapper.selectCount(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getKbId, knowledgeBaseId)
                .isNull(KnowledgeDocument::getDeletedAt));
    }
}
