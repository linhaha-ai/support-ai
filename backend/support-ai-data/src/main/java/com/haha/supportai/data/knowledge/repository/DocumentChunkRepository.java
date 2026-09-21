package com.haha.supportai.data.knowledge.repository;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.DocumentChunk;
import com.haha.supportai.data.knowledge.mapper.DocumentChunkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentChunkRepository {

    private final DocumentChunkMapper documentChunkMapper;

    public void upsertBatch(List<DocumentChunk> documentChunks) {
        if (!documentChunks.isEmpty()) {
            documentChunkMapper.upsertBatch(documentChunks);
        }
    }

    public List<DocumentChunk> findByVersionId(UUID versionId) {
        return documentChunkMapper.selectByVersionId(versionId);
    }

    public long countByVersionId(UUID versionId) {
        return documentChunkMapper.countByVersionId(versionId);
    }

    public int deleteByVersionIdAndChunkIndexGreaterThanOrEqual(UUID versionId, int chunkCount) {
        return documentChunkMapper.deleteByVersionIdAndChunkIndexGreaterThanOrEqual(versionId, chunkCount);
    }
}
