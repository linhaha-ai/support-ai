package com.haha.supportai.knowledge.service.document;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.DocumentCleanupJob;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeDocument;
import com.haha.supportai.data.knowledge.enums.DocumentCleanupStatusEnum;
import com.haha.supportai.data.knowledge.repository.DocumentCleanupJobRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.data.knowledge.repository.KnowledgeDocumentRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.document.exception.DocumentNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentDeletionService {

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final IngestionJobRepository ingestionJobRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentCleanupJobRepository documentCleanupJobRepository;
    private final AccessPolicy accessPolicy;

    @Transactional
    public void delete(UUID documentId) {
        if (documentId == null) {
            throw new DocumentNotFoundException();
        }
        AuthenticatedUser currentUser = accessPolicy.currentUser();
        KnowledgeDocument document = knowledgeDocumentRepository
                .findWritableByIdAndUserIdForUpdateIncludingDeleted(documentId, currentUser.getId())
                .orElseThrow(DocumentNotFoundException::new);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (document.getDeletedAt() == null) {
            if (!knowledgeDocumentRepository.softDeleteAndClearVersions(documentId, now)) {
                throw new DocumentNotFoundException();
            }
            ingestionJobRepository.cancelNonTerminalByDocumentId(documentId, now);
            documentVersionRepository.cancelBuildingByDocumentId(documentId, now);
        }
        documentCleanupJobRepository.insertPendingIfAbsent(pendingCleanup(documentId, now));
    }

    private DocumentCleanupJob pendingCleanup(UUID documentId, OffsetDateTime now) {
        return new DocumentCleanupJob(UUID.randomUUID(), documentId, DocumentCleanupStatusEnum.PENDING, 0,
                null, null, null, now, null, null, null, null, now, now);
    }
}
