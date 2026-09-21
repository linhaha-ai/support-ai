package com.haha.supportai.knowledge.service.document;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.DocumentCleanupJob;
import com.haha.supportai.data.knowledge.repository.DocumentCleanupJobRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVectorRepository;
import com.haha.supportai.knowledge.config.DocumentCleanupProperties;
import com.haha.supportai.knowledge.entity.dto.DocumentCleanupLease;
import com.haha.supportai.knowledge.exception.DocumentCleanupLeaseLostException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentCleanupPersistenceService {

    private static final String ERROR_CODE = "FILE_DELETE_FAILED";
    private static final String ERROR_DETAIL = "受控原文件删除失败，等待自动重试";

    private final DocumentCleanupJobRepository documentCleanupJobRepository;
    private final DocumentVectorRepository documentVectorRepository;
    private final DocumentCleanupProperties properties;
    private final String leaseOwner = "support-ai-document-cleanup-" + UUID.randomUUID();

    @Transactional
    public Optional<DocumentCleanupLease> claim() {
        OffsetDateTime leaseUntil = now().plus(properties.getLeaseDuration());
        UUID leaseToken = UUID.randomUUID();
        return documentCleanupJobRepository.claimNext(leaseOwner, leaseToken, leaseUntil)
                .map(job -> toLease(job, leaseToken, leaseUntil));
    }

    @Transactional
    public void complete(DocumentCleanupLease lease) {
        requireActiveLease(lease);
        documentVectorRepository.deleteByDeletedDocumentId(lease.getDocumentId());
        if (!documentCleanupJobRepository.markSucceeded(lease.getJobId(), lease.getLeaseOwner(),
                lease.getLeaseToken())) {
            throw new DocumentCleanupLeaseLostException();
        }
    }

    @Transactional
    public void retry(DocumentCleanupLease lease) {
        if (!documentCleanupJobRepository.markRetryWait(lease.getJobId(), lease.getLeaseOwner(),
                lease.getLeaseToken(), now().plus(properties.getRetryDelay()), ERROR_CODE, ERROR_DETAIL)) {
            throw new DocumentCleanupLeaseLostException();
        }
    }

    private void requireActiveLease(DocumentCleanupLease lease) {
        documentCleanupJobRepository.findActiveLeaseForUpdate(lease.getJobId(), lease.getDocumentId(),
                        lease.getLeaseOwner(), lease.getLeaseToken())
                .orElseThrow(DocumentCleanupLeaseLostException::new);
    }

    private DocumentCleanupLease toLease(DocumentCleanupJob job, UUID leaseToken, OffsetDateTime leaseUntil) {
        return new DocumentCleanupLease(job.getId(), job.getDocumentId(), job.getAttempt(), leaseOwner,
                leaseToken, leaseUntil);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
