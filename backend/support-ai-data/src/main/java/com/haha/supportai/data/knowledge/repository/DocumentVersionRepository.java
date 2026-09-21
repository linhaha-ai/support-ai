package com.haha.supportai.data.knowledge.repository;

import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.data.knowledge.mapper.DocumentVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentVersionRepository {

    private final DocumentVersionMapper documentVersionMapper;

    public DocumentVersion insert(DocumentVersion documentVersion) {
        documentVersionMapper.insertDocumentVersion(documentVersion);
        return documentVersion;
    }

    public Optional<DocumentVersion> findById(UUID versionId) {
        return Optional.ofNullable(documentVersionMapper.selectById(versionId));
    }

    public boolean updateBuildingChunkCount(UUID versionId, int chunkCount) {
        return documentVersionMapper.updateBuildingChunkCount(versionId, chunkCount) == 1;
    }

    public boolean markFailedIfQueuedOrBuilding(UUID versionId) {
        return documentVersionMapper.markFailedIfQueuedOrBuilding(versionId) == 1;
    }
}
