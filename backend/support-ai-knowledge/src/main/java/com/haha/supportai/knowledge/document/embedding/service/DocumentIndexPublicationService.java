package com.haha.supportai.knowledge.document.embedding.service;

import com.haha.supportai.ai.model.ValidatedEmbeddingModel;
import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVectorRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.data.knowledge.repository.KnowledgeDocumentRepository;
import com.haha.supportai.knowledge.document.embedding.exception.DocumentIndexIntegrityException;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import com.haha.supportai.knowledge.ingestion.exception.IngestionLeaseLostException;
import com.haha.supportai.knowledge.ingestion.exception.IngestionStateConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentIndexPublicationService {

    private final IngestionJobRepository ingestionJobRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentVectorRepository documentVectorRepository;
    private final DocumentEmbeddingConfigurationValidator configurationValidator;

    @Transactional
    public int publish(IngestionTaskLease lease) {
        if (lease.getStage() != IngestionStageEnum.PUBLISH) {
            throw new IngestionStateConflictException("文档索引发布阶段无效");
        }
        ingestionJobRepository.findActiveLeaseForUpdate(lease.getJobId(), lease.getVersionId(), lease.getStage(),
                        lease.getLeaseOwner(), lease.getLeaseToken())
                .orElseThrow(IngestionLeaseLostException::new);

        DocumentVersion version = documentVersionRepository.findById(lease.getVersionId())
                .orElseThrow(DocumentIndexIntegrityException::new);
        configurationValidator.requireCurrent(version);
        long chunkCount = documentChunkRepository.countByVersionId(lease.getVersionId());
        long vectorCount = documentVectorRepository.countByVersionId(lease.getVersionId());
        long validVectorCount = documentVectorRepository.countByVersionIdAndDimensions(
                lease.getVersionId(), ValidatedEmbeddingModel.DIMENSIONS);
        if (chunkCount <= 0 || chunkCount > Integer.MAX_VALUE || version.getChunkCount() != chunkCount
                || vectorCount != chunkCount || validVectorCount != chunkCount) {
            throw new DocumentIndexIntegrityException();
        }

        int completedChunks = (int) chunkCount;
        if (!documentVersionRepository.markReady(lease.getVersionId(), completedChunks)) {
            throw new IngestionStateConflictException("文档版本发布状态冲突");
        }
        if (!knowledgeDocumentRepository.publishPendingVersion(version.getDocumentId(), lease.getVersionId())) {
            throw new IngestionStateConflictException("文档版本指针发布冲突");
        }
        if (!ingestionJobRepository.markSucceeded(lease.getJobId(), lease.getLeaseOwner(), lease.getLeaseToken(),
                completedChunks)) {
            throw new IngestionLeaseLostException();
        }
        return completedChunks;
    }
}
