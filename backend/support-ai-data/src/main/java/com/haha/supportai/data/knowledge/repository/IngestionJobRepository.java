package com.haha.supportai.data.knowledge.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.IngestionJob;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.mapper.IngestionJobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class IngestionJobRepository {

    private final IngestionJobMapper ingestionJobMapper;

    public IngestionJob insert(IngestionJob ingestionJob) {
        ingestionJobMapper.insert(ingestionJob);
        return ingestionJob;
    }

    public Optional<IngestionJob> claimNextByStage(IngestionStageEnum stage, String leaseOwner, UUID leaseToken,
                                                   OffsetDateTime leaseUntil) {
        return ingestionJobMapper.claimNextByStage(stage, leaseOwner, leaseToken, leaseUntil);
    }

    public Optional<IngestionJob> findAccessibleByIdAndUserId(UUID jobId, UUID userId) {
        return ingestionJobMapper.selectAccessibleByIdAndUserId(jobId, userId);
    }

    public Optional<IngestionJob> findActiveLeaseForUpdate(UUID jobId, UUID versionId, IngestionStageEnum stage,
                                                            String leaseOwner, UUID leaseToken) {
        return ingestionJobMapper.selectActiveLeaseForUpdate(jobId, versionId, stage, leaseOwner, leaseToken);
    }

    public boolean renewLease(UUID jobId, String leaseOwner, UUID leaseToken, OffsetDateTime leaseUntil) {
        return ingestionJobMapper.renewLease(jobId, leaseOwner, leaseToken, leaseUntil) == 1;
    }

    public boolean updateProgress(UUID jobId, String leaseOwner, UUID leaseToken, int completedChunks,
                                  int totalChunks) {
        return ingestionJobMapper.updateProgress(jobId, leaseOwner, leaseToken, completedChunks, totalChunks) == 1;
    }

    public boolean advanceToNextStage(UUID jobId, String leaseOwner, UUID leaseToken, IngestionStageEnum stage) {
        return ingestionJobMapper.advanceToNextStage(jobId, leaseOwner, leaseToken, stage) == 1;
    }

    public boolean markRetryWait(UUID jobId, String leaseOwner, UUID leaseToken, OffsetDateTime nextRunAt,
                                 String errorCode, String errorDetail) {
        return ingestionJobMapper.markRetryWait(jobId, leaseOwner, leaseToken, nextRunAt, errorCode, errorDetail) == 1;
    }

    public boolean markFailed(UUID jobId, String leaseOwner, UUID leaseToken, String errorCode, String errorDetail) {
        return ingestionJobMapper.markFailed(jobId, leaseOwner, leaseToken, errorCode, errorDetail) == 1;
    }

    public int markExpiredExhaustedRunningAsFailed() {
        return ingestionJobMapper.markExpiredExhaustedRunningAsFailed();
    }
}
