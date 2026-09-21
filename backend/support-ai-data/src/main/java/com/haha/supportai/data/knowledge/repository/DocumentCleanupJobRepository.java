package com.haha.supportai.data.knowledge.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.DocumentCleanupJob;
import com.haha.supportai.data.knowledge.mapper.DocumentCleanupJobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentCleanupJobRepository {

    private final DocumentCleanupJobMapper documentCleanupJobMapper;

    public boolean insertPendingIfAbsent(DocumentCleanupJob job) {
        return documentCleanupJobMapper.insertPendingIfAbsent(job) == 1;
    }

    public Optional<DocumentCleanupJob> claimNext(String leaseOwner, UUID leaseToken, OffsetDateTime leaseUntil) {
        return documentCleanupJobMapper.claimNext(leaseOwner, leaseToken, leaseUntil);
    }

    public Optional<DocumentCleanupJob> findActiveLeaseForUpdate(UUID jobId, UUID documentId, String leaseOwner,
            UUID leaseToken) {
        return documentCleanupJobMapper.selectActiveLeaseForUpdate(jobId, documentId, leaseOwner, leaseToken);
    }

    public boolean markSucceeded(UUID jobId, String leaseOwner, UUID leaseToken) {
        return documentCleanupJobMapper.markSucceeded(jobId, leaseOwner, leaseToken) == 1;
    }

    public boolean markRetryWait(UUID jobId, String leaseOwner, UUID leaseToken, OffsetDateTime nextRunAt,
            String errorCode, String errorDetail) {
        return documentCleanupJobMapper.markRetryWait(jobId, leaseOwner, leaseToken, nextRunAt, errorCode,
                errorDetail) == 1;
    }
}
