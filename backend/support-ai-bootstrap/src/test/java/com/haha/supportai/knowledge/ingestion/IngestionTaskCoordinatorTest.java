package com.haha.supportai.knowledge.ingestion;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.knowledge.ingestion.config.IngestionWorkerProperties;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionStageResult;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import com.haha.supportai.knowledge.ingestion.exception.IngestionStateConflictException;
import com.haha.supportai.knowledge.ingestion.service.IngestionTaskCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class IngestionTaskCoordinatorTest {

    private static final UUID JOB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440010");
    private static final UUID VERSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440011");
    private static final UUID LEASE_TOKEN = UUID.fromString("550e8400-e29b-41d4-a716-446655440012");

    @Mock
    IngestionJobRepository ingestionJobRepository;

    @Mock
    DocumentVersionRepository documentVersionRepository;

    IngestionTaskCoordinator ingestionTaskCoordinator;

    @BeforeEach
    void setUp() {
        IngestionWorkerProperties properties = new IngestionWorkerProperties();
        properties.setLeaseDuration(Duration.ofSeconds(60));
        properties.setRetryDelay(Duration.ofSeconds(10));
        ingestionTaskCoordinator = new IngestionTaskCoordinator(ingestionJobRepository, documentVersionRepository,
                properties);
    }

    @Test
    void retryableAttemptMovesTaskToRetryWait() {
        IngestionTaskLease lease = lease(IngestionStageEnum.PARSE, 1, 3);
        when(ingestionJobRepository.markRetryWait(eq(JOB_ID), any(String.class), eq(LEASE_TOKEN), any(),
                eq("INGESTION_PROCESSING_FAILED"), eq("文档处理暂时失败"))).thenReturn(true);

        ingestionTaskCoordinator.recordFailure(lease);

        ArgumentCaptor<OffsetDateTime> nextRunAtCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(ingestionJobRepository).markRetryWait(eq(JOB_ID), any(String.class), eq(LEASE_TOKEN),
                nextRunAtCaptor.capture(), eq("INGESTION_PROCESSING_FAILED"), eq("文档处理暂时失败"));
        verify(ingestionJobRepository, never()).markFailed(any(), any(), any(), any(), any());
        assertThat(nextRunAtCaptor.getValue()).isAfter(OffsetDateTime.now().minusSeconds(1));
    }

    @Test
    void exhaustedAttemptMovesTaskToFailed() {
        IngestionTaskLease lease = lease(IngestionStageEnum.EMBED, 3, 3);
        when(ingestionJobRepository.markFailed(JOB_ID, lease.getLeaseOwner(), LEASE_TOKEN,
                "INGESTION_PROCESSING_FAILED", "文档处理暂时失败")).thenReturn(true);
        when(documentVersionRepository.markFailedIfQueuedOrBuilding(VERSION_ID)).thenReturn(true);

        ingestionTaskCoordinator.recordFailure(lease);

        verify(ingestionJobRepository).markFailed(JOB_ID, lease.getLeaseOwner(), LEASE_TOKEN,
                "INGESTION_PROCESSING_FAILED", "文档处理暂时失败");
        verify(documentVersionRepository).markFailedIfQueuedOrBuilding(VERSION_ID);
        verify(ingestionJobRepository, never()).markRetryWait(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsSkippedStageBeforeWritingProgress() {
        IngestionTaskLease lease = lease(IngestionStageEnum.PARSE, 1, 3);

        assertThatThrownBy(() -> ingestionTaskCoordinator.advance(lease,
                IngestionStageResult.advance(IngestionStageEnum.EMBED, 1, 2)))
                .isInstanceOf(IngestionStateConflictException.class);

        verify(ingestionJobRepository, never()).updateProgress(any(), any(), any(), any(), any());
        verify(ingestionJobRepository, never()).advanceToNextStage(any(), any(), any(), any());
    }

    @Test
    void rejectsPublishCompletionOutsideVersionPublishTransaction() {
        IngestionTaskLease lease = lease(IngestionStageEnum.PUBLISH, 1, 3);

        assertThatThrownBy(() -> ingestionTaskCoordinator.advance(lease,
                IngestionStageResult.advance(IngestionStageEnum.PUBLISH, 2, 2)))
                .isInstanceOf(IngestionStateConflictException.class)
                .hasMessageContaining("版本发布事务");

        verify(ingestionJobRepository, never()).updateProgress(any(), any(), any(), any(), any());
        verify(ingestionJobRepository, never()).advanceToNextStage(any(), any(), any(), any());
    }

    private IngestionTaskLease lease(IngestionStageEnum stage, int attempt, int maxAttempts) {
        return new IngestionTaskLease(JOB_ID, VERSION_ID, stage, attempt, maxAttempts, "worker-test", LEASE_TOKEN,
                OffsetDateTime.parse("2026-09-22T00:00:00Z"));
    }
}
