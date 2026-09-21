package com.haha.supportai.knowledge.document.embedding.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.haha.supportai.ai.model.ValidatedEmbeddingModel;
import com.haha.supportai.data.knowledge.entity.po.DocumentChunk;
import com.haha.supportai.data.knowledge.entity.po.DocumentVector;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVectorRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import com.haha.supportai.knowledge.ingestion.exception.IngestionLeaseLostException;
import com.haha.supportai.knowledge.ingestion.exception.IngestionStateConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DocumentVectorPersistenceService {

    private final IngestionJobRepository ingestionJobRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentVectorRepository documentVectorRepository;

    @Transactional
    public void validateLease(IngestionTaskLease lease) {
        if (lease.getStage() != IngestionStageEnum.EMBED) {
            throw new IngestionStateConflictException("向量生成阶段无效");
        }
        requireActiveLease(lease);
    }

    @Transactional
    public void persistBatch(IngestionTaskLease lease, List<DocumentChunk> chunks, List<DocumentVector> vectors,
            int completedChunks, int totalChunks) {
        validateBatch(lease, chunks, vectors, completedChunks, totalChunks);
        requireActiveLease(lease);
        documentChunkRepository.upsertBatch(chunks);
        documentVectorRepository.upsertBatch(vectors);
        if (documentChunkRepository.countByVersionId(lease.getVersionId()) != totalChunks
                || documentVectorRepository.countByVersionId(lease.getVersionId()) != completedChunks) {
            throw new IngestionStateConflictException("向量批次写入数量不一致");
        }
        if (!ingestionJobRepository.updateProgress(lease.getJobId(), lease.getLeaseOwner(), lease.getLeaseToken(),
                completedChunks, totalChunks)) {
            throw new IngestionLeaseLostException();
        }
    }

    private void validateBatch(IngestionTaskLease lease, List<DocumentChunk> chunks, List<DocumentVector> vectors,
            int completedChunks, int totalChunks) {
        if (lease.getStage() != IngestionStageEnum.EMBED || chunks == null || vectors == null || vectors.isEmpty()
                || chunks.size() != vectors.size()
                || completedChunks <= 0 || completedChunks > totalChunks || vectors.size() > completedChunks) {
            throw new IngestionStateConflictException("向量批次结果无效");
        }
        Set<UUID> ids = new HashSet<>();
        int firstChunkIndex = completedChunks - vectors.size();
        for (int index = 0; index < vectors.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            DocumentVector vector = vectors.get(index);
            if (chunk == null || vector == null || chunk.getId() == null || vector.getId() == null
                    || !chunk.getId().equals(vector.getId()) || !lease.getVersionId().equals(chunk.getVersionId())
                    || chunk.getChunkIndex() != firstChunkIndex + index || !ids.add(vector.getId())
                    || !StringUtils.hasText(vector.getContent()) || !StringUtils.hasText(vector.getMetadata())) {
                throw new IngestionStateConflictException("向量批次结果无效");
            }
            validateEmbedding(vector.getEmbedding());
        }
    }

    private void validateEmbedding(float[] embedding) {
        if (embedding == null || embedding.length != ValidatedEmbeddingModel.DIMENSIONS) {
            throw new IngestionStateConflictException("向量维度无效");
        }
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new IngestionStateConflictException("向量包含无效数值");
            }
        }
    }

    private void requireActiveLease(IngestionTaskLease lease) {
        ingestionJobRepository.findActiveLeaseForUpdate(lease.getJobId(), lease.getVersionId(), lease.getStage(),
                        lease.getLeaseOwner(), lease.getLeaseToken())
                .orElseThrow(IngestionLeaseLostException::new);
    }
}
