package com.haha.supportai.knowledge.service.document;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.data.knowledge.entity.po.DocumentCleanupJob;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeDocument;
import com.haha.supportai.data.knowledge.repository.DocumentCleanupJobRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.data.knowledge.repository.KnowledgeDocumentRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.document.exception.DocumentNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentDeletionServiceTest {

    private static final UUID DOCUMENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

    @Mock
    KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Mock
    IngestionJobRepository ingestionJobRepository;

    @Mock
    DocumentVersionRepository documentVersionRepository;

    @Mock
    DocumentCleanupJobRepository documentCleanupJobRepository;

    @Mock
    AccessPolicy accessPolicy;

    @Test
    void deletesDocumentAndInvalidatesPendingWorkInOneApplicationOperation() {
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(knowledgeDocumentRepository.findWritableByIdAndUserIdForUpdateIncludingDeleted(DOCUMENT_ID, USER_ID))
                .thenReturn(Optional.of(document(null)));
        when(knowledgeDocumentRepository.softDeleteAndClearVersions(any(), any())).thenReturn(true);

        service().delete(DOCUMENT_ID);

        verify(knowledgeDocumentRepository).softDeleteAndClearVersions(any(), any());
        verify(ingestionJobRepository).cancelNonTerminalByDocumentId(any(), any());
        verify(documentVersionRepository).cancelBuildingByDocumentId(any(), any());
        ArgumentCaptor<DocumentCleanupJob> jobCaptor = ArgumentCaptor.forClass(DocumentCleanupJob.class);
        verify(documentCleanupJobRepository).insertPendingIfAbsent(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(jobCaptor.getValue().getNextRunAt()).isNotNull();
    }

    @Test
    void repeatedDeleteKeepsCleanupRegistrationIdempotent() {
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(knowledgeDocumentRepository.findWritableByIdAndUserIdForUpdateIncludingDeleted(DOCUMENT_ID, USER_ID))
                .thenReturn(Optional.of(document(OffsetDateTime.parse("2026-09-22T00:00:00Z"))));

        service().delete(DOCUMENT_ID);

        verify(knowledgeDocumentRepository, never()).softDeleteAndClearVersions(any(), any());
        verify(ingestionJobRepository, never()).cancelNonTerminalByDocumentId(any(), any());
        verify(documentCleanupJobRepository).insertPendingIfAbsent(any());
    }

    @Test
    void hidesMissingOrNonWritableDocument() {
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(knowledgeDocumentRepository.findWritableByIdAndUserIdForUpdateIncludingDeleted(DOCUMENT_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(DOCUMENT_ID))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    private DocumentDeletionService service() {
        return new DocumentDeletionService(knowledgeDocumentRepository, ingestionJobRepository,
                documentVersionRepository, documentCleanupJobRepository, accessPolicy);
    }

    private AuthenticatedUser currentUser() {
        return new AuthenticatedUser(USER_ID, "support1", "支持用户", UserRoleEnum.USER);
    }

    private KnowledgeDocument document(OffsetDateTime deletedAt) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(DOCUMENT_ID);
        document.setDeletedAt(deletedAt);
        return document;
    }
}
