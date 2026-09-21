package com.haha.supportai.knowledge.service.document;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.DocumentCleanupJob;
import com.haha.supportai.data.knowledge.repository.DocumentCleanupJobRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVectorRepository;
import com.haha.supportai.knowledge.config.DocumentCleanupProperties;
import com.haha.supportai.knowledge.entity.dto.DocumentCleanupLease;
import com.haha.supportai.knowledge.exception.DocumentCleanupLeaseLostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentCleanupPersistenceServiceTest {

    private static final UUID JOB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final UUID DOCUMENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
    private static final UUID TOKEN = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");

    @Mock
    DocumentCleanupJobRepository documentCleanupJobRepository;

    @Mock
    DocumentVectorRepository documentVectorRepository;

    @Test
    void completesOnlyAfterActiveLeaseWasLocked() {
        DocumentCleanupLease lease = lease();
        when(documentCleanupJobRepository.findActiveLeaseForUpdate(JOB_ID, DOCUMENT_ID, "worker", TOKEN))
                .thenReturn(Optional.of(new DocumentCleanupJob()));
        when(documentCleanupJobRepository.markSucceeded(JOB_ID, "worker", TOKEN)).thenReturn(true);

        service().complete(lease);

        verify(documentVectorRepository).deleteByDeletedDocumentId(DOCUMENT_ID);
        verify(documentCleanupJobRepository).markSucceeded(JOB_ID, "worker", TOKEN);
    }

    @Test
    void expiredLeaseCannotDeleteVectorsOrCommitSuccess() {
        DocumentCleanupLease lease = lease();
        when(documentCleanupJobRepository.findActiveLeaseForUpdate(JOB_ID, DOCUMENT_ID, "worker", TOKEN))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().complete(lease))
                .isInstanceOf(DocumentCleanupLeaseLostException.class);

        verify(documentVectorRepository, never()).deleteByDeletedDocumentId(any());
    }

    @Test
    void failureUsesStableSanitizedReasonAndRetryDelay() {
        when(documentCleanupJobRepository.markRetryWait(eq(JOB_ID), eq("worker"), eq(TOKEN), any(),
                eq("FILE_DELETE_FAILED"), eq("受控原文件删除失败，等待自动重试"))).thenReturn(true);

        service().retry(lease());

        verify(documentCleanupJobRepository).markRetryWait(eq(JOB_ID), eq("worker"), eq(TOKEN), any(),
                eq("FILE_DELETE_FAILED"), eq("受控原文件删除失败，等待自动重试"));
    }

    @Test
    void expiredLeaseCannotWriteRetryState() {
        when(documentCleanupJobRepository.markRetryWait(eq(JOB_ID), eq("worker"), eq(TOKEN), any(),
                eq("FILE_DELETE_FAILED"), eq("受控原文件删除失败，等待自动重试"))).thenReturn(false);

        assertThatThrownBy(() -> service().retry(lease()))
                .isInstanceOf(DocumentCleanupLeaseLostException.class);
    }

    @Test
    void claimReturnsEmptyWhenNoTaskIsDue() {
        when(documentCleanupJobRepository.claimNext(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(service().claim()).isEmpty();
    }

    private DocumentCleanupPersistenceService service() {
        return new DocumentCleanupPersistenceService(documentCleanupJobRepository, documentVectorRepository,
                new DocumentCleanupProperties());
    }

    private DocumentCleanupLease lease() {
        return new DocumentCleanupLease(JOB_ID, DOCUMENT_ID, 1, "worker", TOKEN,
                OffsetDateTime.parse("2026-09-22T00:05:00Z"));
    }
}
