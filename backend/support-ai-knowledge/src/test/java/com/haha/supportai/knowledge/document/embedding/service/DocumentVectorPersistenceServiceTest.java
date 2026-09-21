package com.haha.supportai.knowledge.document.embedding.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.DocumentChunk;
import com.haha.supportai.data.knowledge.entity.po.DocumentVector;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVectorRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import com.haha.supportai.knowledge.ingestion.exception.IngestionLeaseLostException;
import com.haha.supportai.knowledge.ingestion.exception.IngestionStateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentVectorPersistenceServiceTest {

    private static final UUID JOB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440821");
    private static final UUID VERSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440822");
    private static final UUID LEASE_TOKEN = UUID.fromString("550e8400-e29b-41d4-a716-446655440823");
    private static final UUID CHUNK_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440824");

    @Mock
    IngestionJobRepository ingestionJobRepository;

    @Mock
    DocumentVectorRepository documentVectorRepository;

    @Mock
    DocumentChunkRepository documentChunkRepository;

    private DocumentVectorPersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        persistenceService = new DocumentVectorPersistenceService(ingestionJobRepository, documentChunkRepository,
                documentVectorRepository);
    }

    @Test
    void expiredLeaseCannotWriteVectorBatch() {
        IngestionTaskLease lease = lease();
        when(ingestionJobRepository.findActiveLeaseForUpdate(JOB_ID, VERSION_ID, IngestionStageEnum.EMBED,
                "f08-test-worker", LEASE_TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> persistenceService.persistBatch(lease, chunks(), vectors(), 1, 1))
                .isInstanceOf(IngestionLeaseLostException.class);

        verify(documentVectorRepository, never()).upsertBatch(any());
        verify(documentChunkRepository, never()).upsertBatch(any());
        verify(ingestionJobRepository, never()).updateProgress(any(), any(), any(), eq(1), eq(1));
    }

    @Test
    void invalidDimensionOrNonFiniteVectorDoesNotPersistBatch() {
        IngestionTaskLease lease = lease();
        float[] invalidDimension = new float[1023];
        float[] nonFinite = new float[1024];
        nonFinite[7] = Float.NaN;

        assertThatThrownBy(() -> persistenceService.persistBatch(lease, chunks(), List.of(
                new DocumentVector(CHUNK_ID, "付款", "{\"chunkIndex\":0}", invalidDimension)), 1, 1))
                .isInstanceOf(IngestionStateConflictException.class);
        assertThatThrownBy(() -> persistenceService.persistBatch(lease, chunks(), List.of(
                new DocumentVector(CHUNK_ID, "付款", "{\"chunkIndex\":0}", nonFinite)), 1, 1))
                .isInstanceOf(IngestionStateConflictException.class);

        verify(documentVectorRepository, never()).upsertBatch(any());
        verify(documentChunkRepository, never()).upsertBatch(any());
    }

    private IngestionTaskLease lease() {
        return new IngestionTaskLease(JOB_ID, VERSION_ID, IngestionStageEnum.EMBED, 1, 3, "f08-test-worker",
                LEASE_TOKEN, OffsetDateTime.parse("2026-09-23T00:00:00Z"));
    }

    private List<DocumentChunk> chunks() {
        return List.of(new DocumentChunk(CHUNK_ID, VERSION_ID, 0, "支付", null, 0, 3,
                "a".repeat(64), 3, 1));
    }

    private List<DocumentVector> vectors() {
        float[] embedding = new float[1024];
        embedding[0] = 1;
        return List.of(new DocumentVector(CHUNK_ID, "付款", "{\"chunkIndex\":0}", embedding));
    }
}
