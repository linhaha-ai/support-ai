package com.haha.supportai.knowledge.ingestion.service;

import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.IngestionJob;
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

    private final IngestionJobRepository ingestionJobRepository;
    private final AccessPolicy accessPolicy;

    public IngestionJobStatusResponse get(UUID jobId) {
        AuthenticatedUser currentUser = accessPolicy.currentUser();
        IngestionJob job = ingestionJobRepository.findAccessibleByIdAndUserId(jobId, currentUser.getId())
                .orElseThrow(IngestionJobNotFoundException::new);
        return IngestionJobStatusResponse.from(job);
    }
}
