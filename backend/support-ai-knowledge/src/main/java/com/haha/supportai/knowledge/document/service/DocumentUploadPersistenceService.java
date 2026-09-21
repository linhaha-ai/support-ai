package com.haha.supportai.knowledge.document.service;

import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.data.knowledge.entity.po.IngestionJob;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeDocument;
import com.haha.supportai.data.knowledge.enums.DocumentVersionStatusEnum;
import com.haha.supportai.data.knowledge.enums.IngestionJobStatusEnum;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.data.knowledge.repository.KnowledgeDocumentRepository;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.document.entity.dto.NewDocumentVersionCommand;
import com.haha.supportai.knowledge.entity.dto.KnowledgeBaseAccessContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentUploadPersistenceService {

    private final AccessPolicy accessPolicy;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final IngestionJobRepository ingestionJobRepository;

    @Transactional
    public void createInitialVersion(NewDocumentVersionCommand command) {
        // 文件落盘后再次校验写权限，避免权限变更与数据库登记之间出现竞态。
        KnowledgeBaseAccessContext accessContext = accessPolicy.requireWriteForUpdate(command.getKnowledgeBaseId());
        KnowledgeDocument document = new KnowledgeDocument(command.getDocumentId(), command.getKnowledgeBaseId(),
                command.getDisplayName(), null, null, 2, accessContext.getMember().getUserId(), null, 0,
                command.getCreatedAt(), command.getCreatedAt());
        DocumentVersion version = new DocumentVersion(command.getVersionId(), command.getDocumentId(), 1,
                command.getStorageKey(), command.getFileHash(), command.getFileSize(), command.getMediaType(),
                command.getOriginalFilename(), DocumentVersionStatusEnum.QUEUED, command.getParserVersion(),
                command.getChunkerVersion(), command.getEmbeddingModel(), command.getDimensions(),
                command.getConfigHash(), command.getConfigSnapshot(), 0, command.getCreatedAt(),
                command.getCreatedAt());
        IngestionJob job = new IngestionJob(command.getJobId(), command.getVersionId(),
                IngestionJobStatusEnum.QUEUED, IngestionStageEnum.PARSE, 0, command.getMaxAttempts(), null, null,
                null, null, command.getCreatedAt(), 0, null, null, null, null, null, null,
                command.getCreatedAt(), command.getCreatedAt());

        knowledgeDocumentRepository.insert(document);
        documentVersionRepository.insert(version);
        ingestionJobRepository.insert(job);
        if (!knowledgeDocumentRepository.updatePendingVersion(document.getId(), version.getId())) {
            throw new IllegalStateException("未能关联待处理文档版本");
        }
    }
}
