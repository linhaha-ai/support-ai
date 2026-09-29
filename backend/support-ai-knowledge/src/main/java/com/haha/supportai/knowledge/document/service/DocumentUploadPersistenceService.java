package com.haha.supportai.knowledge.document.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.common.support.TraceContext;
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
import com.haha.supportai.knowledge.document.entity.dto.DocumentVersionMatchCommand;
import com.haha.supportai.knowledge.document.entity.dto.DocumentVersionUploadCommand;
import com.haha.supportai.knowledge.document.entity.dto.NewDocumentVersionCommand;
import com.haha.supportai.knowledge.document.entity.vo.DocumentVersionUploadResponse;
import com.haha.supportai.knowledge.document.exception.DocumentNotFoundException;
import com.haha.supportai.knowledge.document.exception.DocumentVersionConflictException;
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
                command.getCreatedAt(), command.getCreatedAt(), TraceContext.currentOrNull());

        knowledgeDocumentRepository.insert(document);
        documentVersionRepository.insert(version);
        ingestionJobRepository.insert(job);
        if (!knowledgeDocumentRepository.updatePendingVersion(document.getId(), version.getId())) {
            throw new IllegalStateException("未能关联待处理文档版本");
        }
    }

    @Transactional(readOnly = true)
    public void requireVersionUploadAccess(UUID documentId) {
        UUID userId = accessPolicy.currentUser().getId();
        KnowledgeDocument document = knowledgeDocumentRepository.findAccessibleByIdAndUserId(documentId, userId)
                .orElseThrow(DocumentNotFoundException::new);
        accessPolicy.requireWrite(document.getKbId());
    }

    @Transactional
    public Optional<DocumentVersionUploadResponse> findReusableCurrentVersion(DocumentVersionMatchCommand command) {
        KnowledgeDocument document = lockWritableDocument(command.getDocumentId());
        validateUpdateState(document, command.getExpectedRevision());
        if (document.getActiveVersionId() == null) {
            return Optional.empty();
        }

        DocumentVersion version = documentVersionRepository.findById(document.getActiveVersionId())
                .filter(candidate -> Objects.equals(candidate.getDocumentId(), document.getId()))
                .orElseThrow(DocumentVersionConflictException::new);
        if (!matchesCurrentVersion(version, command)) {
            return Optional.empty();
        }

        IngestionJob job = ingestionJobRepository.findByVersionIds(List.of(version.getId())).stream()
                .filter(candidate -> candidate.getStatus() == IngestionJobStatusEnum.SUCCEEDED)
                .findFirst()
                .orElseThrow(DocumentVersionConflictException::new);
        return Optional.of(new DocumentVersionUploadResponse(document.getId(), version.getId(), job.getId(),
                job.getStatus(), document.getRevision(), true));
    }

    @Transactional
    public DocumentVersionUploadResponse createNewVersion(DocumentVersionUploadCommand command) {
        KnowledgeDocument document = lockWritableDocument(command.getDocumentId());
        validateUpdateState(document, command.getExpectedRevision());
        int versionNo = document.getNextVersionNo();
        if (versionNo < 1 || versionNo == Integer.MAX_VALUE) {
            throw new DocumentVersionConflictException();
        }

        DocumentVersion version = new DocumentVersion(command.getVersionId(), document.getId(), versionNo,
                command.getStorageKey(), command.getFileHash(), command.getFileSize(), command.getMediaType(),
                command.getOriginalFilename(), DocumentVersionStatusEnum.QUEUED, command.getParserVersion(),
                command.getChunkerVersion(), command.getEmbeddingModel(), command.getDimensions(),
                command.getConfigHash(), command.getConfigSnapshot(), 0, command.getCreatedAt(),
                command.getCreatedAt());
        IngestionJob job = new IngestionJob(command.getJobId(), version.getId(), IngestionJobStatusEnum.QUEUED,
                IngestionStageEnum.PARSE, 0, command.getMaxAttempts(), null, null, null, null,
                command.getCreatedAt(), 0, null, null, null, null, null, null,
                command.getCreatedAt(), command.getCreatedAt(), TraceContext.currentOrNull());

        documentVersionRepository.insert(version);
        ingestionJobRepository.insert(job);
        if (!knowledgeDocumentRepository.reservePendingVersion(document.getId(), command.getExpectedRevision(),
                version.getId(), versionNo)) {
            throw new DocumentVersionConflictException();
        }
        return new DocumentVersionUploadResponse(document.getId(), version.getId(), job.getId(), job.getStatus(),
                command.getExpectedRevision() + 1, false);
    }

    private KnowledgeDocument lockWritableDocument(UUID documentId) {
        UUID userId = accessPolicy.currentUser().getId();
        KnowledgeDocument document = knowledgeDocumentRepository
                .findAccessibleByIdAndUserIdForUpdate(documentId, userId)
                .orElseThrow(DocumentNotFoundException::new);
        accessPolicy.requireWriteForUpdate(document.getKbId());
        return document;
    }

    private void validateUpdateState(KnowledgeDocument document, long expectedRevision) {
        if (expectedRevision < 0 || document.getRevision() != expectedRevision
                || document.getPendingVersionId() != null) {
            throw new DocumentVersionConflictException();
        }
    }

    private boolean matchesCurrentVersion(DocumentVersion version, DocumentVersionMatchCommand command) {
        return version.getStatus() == DocumentVersionStatusEnum.READY
                && Objects.equals(version.getFileHash(), command.getFileHash())
                && Objects.equals(version.getParserVersion(), command.getParserVersion())
                && Objects.equals(version.getChunkerVersion(), command.getChunkerVersion())
                && Objects.equals(version.getEmbeddingModel(), command.getEmbeddingModel())
                && version.getDimensions() == command.getDimensions()
                && Objects.equals(version.getConfigHash(), command.getConfigHash());
    }
}
