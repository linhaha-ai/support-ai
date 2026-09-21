package com.haha.supportai.knowledge.ingestion.service;

import java.util.Set;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.IngestionJob;
import com.haha.supportai.data.knowledge.enums.IngestionJobStatusEnum;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.ingestion.entity.vo.IngestionJobStatusResponse;
import com.haha.supportai.knowledge.ingestion.exception.IngestionJobNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IngestionJobQueryService {

    private static final Set<String> PUBLIC_ERROR_CODES = Set.of(
            "DOCUMENT_EMPTY",
            "DOCUMENT_READ_FAILED",
            "EMBEDDING_FAILED",
            "INGESTION_LEASE_EXPIRED",
            "INGESTION_PROCESSING_FAILED");

    private static final Set<IngestionJobStatusEnum> CANCELLABLE_STATUSES = Set.of(
            IngestionJobStatusEnum.QUEUED,
            IngestionJobStatusEnum.RUNNING,
            IngestionJobStatusEnum.RETRY_WAIT);

    private final IngestionJobRepository ingestionJobRepository;
    private final AccessPolicy accessPolicy;

    public IngestionJobStatusResponse get(UUID jobId) {
        AuthenticatedUser currentUser = accessPolicy.currentUser();
        IngestionJob job = ingestionJobRepository.findAccessibleByIdAndUserId(jobId, currentUser.getId())
                .orElseThrow(IngestionJobNotFoundException::new);
        return new IngestionJobStatusResponse(job.getId(), job.getVersionId(), job.getStatus(), job.getStage(),
                job.getAttempt(), job.getMaxAttempts(), job.getCompletedChunks(), job.getTotalChunks(),
                progressPercent(job), isRetryable(job), CANCELLABLE_STATUSES.contains(job.getStatus()),
                safeErrorCode(job), safeErrorMessage(job), job.getNextRunAt(), job.getStartedAt(),
                job.getFinishedAt(), job.getUpdatedAt());
    }

    private Integer progressPercent(IngestionJob job) {
        if (job.getCompletedChunks() == null || job.getTotalChunks() == null || job.getTotalChunks() <= 0) {
            return null;
        }
        long percent = (long) job.getCompletedChunks() * 100 / job.getTotalChunks();
        return (int) Math.max(0, Math.min(100, percent));
    }

    private boolean isRetryable(IngestionJob job) {
        return job.getStatus() == IngestionJobStatusEnum.FAILED && job.getAttempt() < job.getMaxAttempts();
    }

    private String safeErrorCode(IngestionJob job) {
        String errorCode = job.getErrorCode();
        if (errorCode == null) {
            return job.getStatus() == IngestionJobStatusEnum.FAILED ? "INGESTION_PROCESSING_FAILED" : null;
        }
        if (PUBLIC_ERROR_CODES.contains(errorCode)) {
            return errorCode;
        }
        return "INGESTION_PROCESSING_FAILED";
    }

    private String safeErrorMessage(IngestionJob job) {
        String errorCode = safeErrorCode(job);
        if (errorCode == null) {
            return null;
        }
        return switch (errorCode) {
            case "DOCUMENT_EMPTY" -> "文档没有可处理内容";
            case "DOCUMENT_READ_FAILED" -> "原文件暂时无法读取";
            case "EMBEDDING_FAILED" -> "文档向量化暂时失败";
            case "INGESTION_LEASE_EXPIRED" -> "文档处理执行超时";
            default -> "文档处理暂时失败";
        };
    }
}
