package com.haha.supportai.knowledge.service.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.knowledge.entity.dto.DocumentCleanupLease;
import com.haha.supportai.storage.FileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentCleanupServiceTest {

    private static final UUID DOCUMENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

    @Mock
    DocumentCleanupPersistenceService persistenceService;

    @Mock
    DocumentVersionRepository documentVersionRepository;

    @Mock
    FileStore fileStore;

    @Test
    void deletesAllFilesBeforeCompletingDatabaseCleanup() {
        DocumentCleanupLease lease = lease();
        when(persistenceService.claim()).thenReturn(Optional.of(lease));
        when(documentVersionRepository.listStorageKeysForDeletedDocument(DOCUMENT_ID))
                .thenReturn(List.of("documents/a", "documents/b"));

        service().executeOnce();

        InOrder ordered = inOrder(fileStore, persistenceService);
        ordered.verify(fileStore).delete("documents/a");
        ordered.verify(fileStore).delete("documents/b");
        ordered.verify(persistenceService).complete(lease);
        verify(persistenceService, never()).retry(lease);
    }

    @Test
    void fileFailureMovesLeaseToAutomaticRetry() {
        DocumentCleanupLease lease = lease();
        when(persistenceService.claim()).thenReturn(Optional.of(lease));
        when(documentVersionRepository.listStorageKeysForDeletedDocument(DOCUMENT_ID))
                .thenReturn(List.of("documents/a"));
        doThrow(new IllegalStateException("sensitive path")).when(fileStore).delete("documents/a");

        service().executeOnce();

        verify(persistenceService).retry(lease);
        verify(persistenceService, never()).complete(lease);
    }

    private DocumentCleanupService service() {
        return new DocumentCleanupService(persistenceService, documentVersionRepository, fileStore);
    }

    private DocumentCleanupLease lease() {
        return new DocumentCleanupLease(UUID.randomUUID(), DOCUMENT_ID, 1, "worker", UUID.randomUUID(),
                OffsetDateTime.parse("2026-09-22T00:05:00Z"));
    }
}
