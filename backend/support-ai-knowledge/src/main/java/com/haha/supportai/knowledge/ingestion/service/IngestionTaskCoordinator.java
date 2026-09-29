package com.haha.supportai.knowledge.ingestion.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.IngestionJob;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.knowledge.ingestion.config.IngestionWorkerProperties;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionStageResult;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import com.haha.supportai.knowledge.ingestion.exception.IngestionLeaseLostException;
import com.haha.supportai.knowledge.ingestion.exception.IngestionStateConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IngestionTaskCoordinator {

    private static final String PROCESSING_ERROR_CODE = "INGESTION_PROCESSING_FAILED";
    private static final String PROCESSING_ERROR_DETAIL = "文档处理暂时失败";

    private final IngestionJobRepository ingestionJobRepository;
    private final IngestionWorkerProperties properties;
    private final String leaseOwner = "support-ai-" + UUID.randomUUID();

    @Transactional
    public Optional<IngestionTaskLease> claim(IngestionStageEnum stage) {
        UUID leaseToken = UUID.randomUUID();
        OffsetDateTime leaseUntil = now().plus(properties.getLeaseDuration());
        return ingestionJobRepository.claimNextByStage(stage, leaseOwner, leaseToken, leaseUntil)
                .map(this::toLease);
    }

    @Transactional
    public void renewLease(IngestionTaskLease lease) {
        OffsetDateTime leaseUntil = now().plus(properties.getLeaseDuration());
        requireLease(ingestionJobRepository.renewLease(lease.getJobId(), lease.getLeaseOwner(),
                lease.getLeaseToken(), leaseUntil));
    }

    @Transactional
    public void updateProgress(IngestionTaskLease lease, int completedChunks, int totalChunks) {
        validateProgress(completedChunks, totalChunks);
        requireLease(ingestionJobRepository.updateProgress(lease.getJobId(), lease.getLeaseOwner(),
                lease.getLeaseToken(), completedChunks, totalChunks));
    }

    @Transactional
    public void advance(IngestionTaskLease lease, IngestionStageResult result) {
        IngestionStageEnum expectedNextStage = nextStage(lease.getStage());
        if (result.getNextStage() != expectedNextStage) {
            throw new IngestionStateConflictException("入库任务阶段推进顺序无效");
        }
        updateProgressIfPresent(lease, result);
        requireLease(ingestionJobRepository.advanceToNextStage(lease.getJobId(), lease.getLeaseOwner(),
                lease.getLeaseToken(), lease.getStage()));
    }

    @Transactional
    public void recordFailure(IngestionTaskLease lease) {
        recordFailure(lease, PROCESSING_ERROR_CODE, PROCESSING_ERROR_DETAIL, true);
    }

    @Transactional
    public void recordFailure(IngestionTaskLease lease, String errorCode, String errorDetail) {
        recordFailure(lease, errorCode, errorDetail, true);
    }

    @Transactional
    public void recordFailure(IngestionTaskLease lease, String errorCode, String errorDetail,
            boolean automaticRetryAllowed) {
        boolean updated;
        if (automaticRetryAllowed && lease.getAttempt() < lease.getMaxAttempts()) {
            OffsetDateTime nextRunAt = now().plus(properties.getRetryDelay());
            updated = ingestionJobRepository.markRetryWait(lease.getJobId(), lease.getLeaseOwner(),
                    lease.getLeaseToken(), nextRunAt, errorCode, errorDetail);
        } else {
            updated = ingestionJobRepository.markFailed(lease.getJobId(), lease.getLeaseOwner(),
                    lease.getLeaseToken(), errorCode, errorDetail);
        }
        requireLease(updated);
    }

    @Transactional
    public int recoverExpiredExhaustedTasks() {
        return ingestionJobRepository.markExpiredExhaustedRunningAsFailed();
    }

    private void updateProgressIfPresent(IngestionTaskLease lease, IngestionStageResult result) {
        Integer completedChunks = result.getCompletedChunks();
        Integer totalChunks = result.getTotalChunks();
        if (completedChunks == null && totalChunks == null) {
            return;
        }
        if (completedChunks == null || totalChunks == null) {
            throw new IngestionStateConflictException("入库任务进度必须同时包含完成量和总量");
        }
        validateProgress(completedChunks, totalChunks);
        requireLease(ingestionJobRepository.updateProgress(lease.getJobId(), lease.getLeaseOwner(),
                lease.getLeaseToken(), completedChunks, totalChunks));
    }

    private void validateProgress(int completedChunks, int totalChunks) {
        if (completedChunks < 0 || totalChunks < 0 || completedChunks > totalChunks) {
            throw new IngestionStateConflictException("入库任务进度数值无效");
        }
    }

    private IngestionTaskLease toLease(IngestionJob job) {
        return new IngestionTaskLease(job.getId(), job.getVersionId(), job.getStage(), job.getAttempt(),
                job.getMaxAttempts(), job.getLeaseOwner(), job.getLeaseToken(), job.getLeaseUntil(),
                job.getTraceId());
    }

    private IngestionStageEnum nextStage(IngestionStageEnum currentStage) {
        return switch (currentStage) {
            case PARSE -> IngestionStageEnum.CHUNK;
            case CHUNK -> IngestionStageEnum.EMBED;
            case EMBED -> IngestionStageEnum.PUBLISH;
            case PUBLISH -> throw new IngestionStateConflictException("发布阶段必须与版本发布事务一并完成");
        };
    }

    private void requireLease(boolean updated) {
        if (!updated) {
            throw new IngestionLeaseLostException();
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
