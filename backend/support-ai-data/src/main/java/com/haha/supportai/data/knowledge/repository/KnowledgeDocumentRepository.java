package com.haha.supportai.data.knowledge.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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

    public boolean reservePendingVersion(UUID documentId, long expectedRevision, UUID versionId, int versionNo) {
        return knowledgeDocumentMapper.reservePendingVersion(documentId, expectedRevision, versionId, versionNo) == 1;
    }

    public boolean publishPendingVersion(UUID documentId, UUID versionId) {
        return knowledgeDocumentMapper.publishPendingVersion(documentId, versionId) == 1;
    }

    public long countActiveByKnowledgeBaseId(UUID knowledgeBaseId) {
        return knowledgeDocumentMapper.selectCount(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getKbId, knowledgeBaseId)
                .isNull(KnowledgeDocument::getDeletedAt));
    }

    public List<KnowledgeDocument> listAccessibleByKnowledgeBaseId(UUID knowledgeBaseId, UUID userId) {
        return knowledgeDocumentMapper.selectAccessibleByKnowledgeBaseId(knowledgeBaseId, userId);
    }

    public Optional<KnowledgeDocument> findAccessibleByIdAndUserId(UUID documentId, UUID userId) {
        return knowledgeDocumentMapper.selectAccessibleByIdAndUserId(documentId, userId);
    }

    public Optional<KnowledgeDocument> findAccessibleByIdAndUserIdForUpdate(UUID documentId, UUID userId) {
        return knowledgeDocumentMapper.selectAccessibleByIdAndUserIdForUpdate(documentId, userId);
    }

    public Optional<KnowledgeDocument> findWritableByIdAndUserIdForUpdateIncludingDeleted(UUID documentId,
            UUID userId) {
        return knowledgeDocumentMapper.selectWritableByIdAndUserIdForUpdateIncludingDeleted(documentId, userId);
    }

    public boolean softDeleteAndClearVersions(UUID documentId, OffsetDateTime deletedAt) {
        return knowledgeDocumentMapper.softDeleteAndClearVersions(documentId, deletedAt) == 1;
    }
}
