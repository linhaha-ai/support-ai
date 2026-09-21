package com.haha.supportai.data.knowledge.repository;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.DocumentVector;
import com.haha.supportai.data.knowledge.mapper.DocumentVectorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentVectorRepository {

    private final DocumentVectorMapper documentVectorMapper;

    public void upsertBatch(List<DocumentVector> documentVectors) {
        if (!documentVectors.isEmpty()) {
            documentVectorMapper.upsertBatch(documentVectors);
        }
    }

    public List<DocumentVector> findByVersionId(UUID versionId) {
        return documentVectorMapper.selectByVersionId(versionId);
    }

    public long countByVersionId(UUID versionId) {
        return documentVectorMapper.countByVersionId(versionId);
    }

    public long countByVersionIdAndDimensions(UUID versionId, int dimensions) {
        return documentVectorMapper.countByVersionIdAndDimensions(versionId, dimensions);
    }

    public DocumentVector findById(UUID id) {
        return documentVectorMapper.selectVectorById(id);
    }
}
