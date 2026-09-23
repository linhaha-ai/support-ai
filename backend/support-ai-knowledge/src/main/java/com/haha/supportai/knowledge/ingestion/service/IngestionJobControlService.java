package com.haha.supportai.knowledge.ingestion.service;

import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.IngestionJob;
import com.haha.supportai.data.knowledge.enums.IngestionJobStatusEnum;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.exception.KnowledgeBaseAccessDeniedException;
import com.haha.supportai.knowledge.ingestion.entity.vo.IngestionJobStatusResponse;
import com.haha.supportai.knowledge.ingestion.exception.IngestionJobNotFoundException;
import com.haha.supportai.knowledge.ingestion.exception.IngestionStateConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IngestionJobControlService {

    private final IngestionJobRepository ingestionJobRepository;
    private final AccessPolicy accessPolicy;

    @Transactional
    public IngestionJobStatusResponse retry(UUID jobId) {
        AuthenticatedUser currentUser = accessPolicy.currentUser();
        requireWriteAccess(jobId, currentUser.getId());
        IngestionJob retried = ingestionJobRepository.retry(jobId, currentUser.getId())
                .orElseThrow(() -> new IngestionStateConflictException("入库任务不满足人工重试条件"));
        return IngestionJobStatusResponse.from(retried, true);
    }

    @Transactional
    public IngestionJobStatusResponse cancel(UUID jobId) {
        AuthenticatedUser currentUser = accessPolicy.currentUser();
        IngestionJob current = requireWriteAccess(jobId, currentUser.getId());
        if (current.getStatus() == IngestionJobStatusEnum.CANCELLED) {
            return IngestionJobStatusResponse.from(current, true);
        }
        IngestionJob cancelled = ingestionJobRepository.cancel(jobId, currentUser.getId())
                .orElseThrow(() -> new IngestionStateConflictException("入库任务不满足取消条件"));
        return IngestionJobStatusResponse.from(cancelled, true);
    }

    private IngestionJob requireWriteAccess(UUID jobId, UUID userId) {
        IngestionJob job = ingestionJobRepository.findAccessibleByIdAndUserId(jobId, userId)
                .orElseThrow(IngestionJobNotFoundException::new);
        if (!ingestionJobRepository.hasWriteAccess(jobId, userId)) {
            throw new KnowledgeBaseAccessDeniedException();
        }
        return job;
    }
}
