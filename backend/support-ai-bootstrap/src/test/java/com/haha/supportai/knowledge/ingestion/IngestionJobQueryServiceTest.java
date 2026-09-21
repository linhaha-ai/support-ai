package com.haha.supportai.knowledge.ingestion;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.data.knowledge.entity.po.IngestionJob;
import com.haha.supportai.data.knowledge.enums.IngestionJobStatusEnum;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.ingestion.entity.vo.IngestionJobStatusResponse;
import com.haha.supportai.knowledge.ingestion.service.IngestionJobQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionJobQueryServiceTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID JOB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final UUID VERSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

    @Mock
    IngestionJobRepository ingestionJobRepository;

    @Mock
    AccessPolicy accessPolicy;

    @Test
    void queriesOnlyWithCurrentUserAndDoesNotExposeInternalFailureDetail() {
        IngestionJob job = job(IngestionJobStatusEnum.FAILED, "INTERNAL_STORAGE_EXCEPTION", "S3 secret endpoint");
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(ingestionJobRepository.findAccessibleByIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(job));

        IngestionJobStatusResponse response = service().get(JOB_ID);

        ArgumentCaptor<UUID> userIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(ingestionJobRepository).findAccessibleByIdAndUserId(eq(JOB_ID), userIdCaptor.capture());
        assertThat(userIdCaptor.getValue()).isEqualTo(USER_ID);
        assertThat(response.getErrorCode()).isEqualTo("INGESTION_PROCESSING_FAILED");
        assertThat(response.getErrorMessage()).isEqualTo("文档处理暂时失败");
        assertThat(response.getErrorMessage()).doesNotContain("S3").doesNotContain("secret");
    }

    @Test
    void allowsEmptyProgressWhenRunningTaskHasNoFailure() {
        IngestionJob job = job(IngestionJobStatusEnum.RUNNING, null, null);
        job.setCompletedChunks(null);
        job.setTotalChunks(null);
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(ingestionJobRepository.findAccessibleByIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(job));

        IngestionJobStatusResponse response = service().get(JOB_ID);

        assertThat(response.getCompletedChunks()).isNull();
        assertThat(response.getTotalChunks()).isNull();
        assertThat(response.getProgressPercent()).isNull();
        assertThat(response.getErrorCode()).isNull();
        assertThat(response.getErrorMessage()).isNull();
    }

    @Test
    void capsProgressWithinUserVisibleRange() {
        IngestionJob job = job(IngestionJobStatusEnum.RUNNING, null, null);
        job.setCompletedChunks(12);
        job.setTotalChunks(10);
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(ingestionJobRepository.findAccessibleByIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(job));

        IngestionJobStatusResponse response = service().get(JOB_ID);

        assertThat(response.getProgressPercent()).isEqualTo(100);
    }

    private IngestionJobQueryService service() {
        return new IngestionJobQueryService(ingestionJobRepository, accessPolicy);
    }

    private AuthenticatedUser currentUser() {
        return new AuthenticatedUser(USER_ID, "support1", "支持用户", UserRoleEnum.USER);
    }

    private IngestionJob job(IngestionJobStatusEnum status, String errorCode, String errorDetail) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T00:00:00Z");
        return new IngestionJob(JOB_ID, VERSION_ID, status, IngestionStageEnum.PARSE, 1, 3, null, null, null,
                null, now, 0, 0, errorCode, errorDetail, null, now, null, now, now);
    }
}
