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
import com.haha.supportai.knowledge.exception.KnowledgeBaseAccessDeniedException;
import com.haha.supportai.knowledge.ingestion.exception.IngestionStateConflictException;
import com.haha.supportai.knowledge.ingestion.service.IngestionJobControlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionJobControlServiceTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID JOB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final UUID VERSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

    @Mock
    IngestionJobRepository ingestionJobRepository;

    @Mock
    AccessPolicy accessPolicy;

    @Test
    void retriesOnlyThroughAtomicRepositoryTransition() {
        IngestionJob failed = job(IngestionJobStatusEnum.FAILED);
        IngestionJob queued = job(IngestionJobStatusEnum.QUEUED);
        authorize(failed);
        when(ingestionJobRepository.retry(JOB_ID, USER_ID)).thenReturn(Optional.of(queued));

        var response = service().retry(JOB_ID);

        assertThat(response.getStatus()).isEqualTo(IngestionJobStatusEnum.QUEUED);
        verify(ingestionJobRepository).retry(JOB_ID, USER_ID);
    }

    @Test
    void rejectsReadMemberBeforeTaskMutation() {
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(ingestionJobRepository.findAccessibleByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(job(IngestionJobStatusEnum.FAILED)));
        when(ingestionJobRepository.hasWriteAccess(JOB_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service().retry(JOB_ID))
                .isInstanceOf(KnowledgeBaseAccessDeniedException.class);
    }

    @Test
    void reportsConflictWhenFailedTaskCannotReacquirePendingVersion() {
        IngestionJob failed = job(IngestionJobStatusEnum.FAILED);
        authorize(failed);
        when(ingestionJobRepository.retry(JOB_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().retry(JOB_ID))
                .isInstanceOf(IngestionStateConflictException.class);
    }

    @Test
    void repeatedCancelReturnsExistingCancelledState() {
        IngestionJob cancelled = job(IngestionJobStatusEnum.CANCELLED);
        authorize(cancelled);

        var response = service().cancel(JOB_ID);

        assertThat(response.getStatus()).isEqualTo(IngestionJobStatusEnum.CANCELLED);
    }

    private void authorize(IngestionJob job) {
        when(accessPolicy.currentUser()).thenReturn(currentUser());
        when(ingestionJobRepository.findAccessibleByIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(job));
        when(ingestionJobRepository.hasWriteAccess(JOB_ID, USER_ID)).thenReturn(true);
    }

    private IngestionJobControlService service() {
        return new IngestionJobControlService(ingestionJobRepository, accessPolicy);
    }

    private AuthenticatedUser currentUser() {
        return new AuthenticatedUser(USER_ID, "support1", "支持用户", UserRoleEnum.USER);
    }

    private IngestionJob job(IngestionJobStatusEnum status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-23T00:00:00Z");
        return new IngestionJob(JOB_ID, VERSION_ID, status, IngestionStageEnum.PARSE, 1, 3, null, null, null,
                null, now, 0, 0, null, null, null, now, status == IngestionJobStatusEnum.QUEUED ? null : now,
                now, now);
    }
}
