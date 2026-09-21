package com.haha.supportai.knowledge.ingestion.worker;

import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.knowledge.document.embedding.service.DocumentIndexPublicationService;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionStageResult;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentPublishStageProcessor implements IngestionStageProcessor {

    private final DocumentIndexPublicationService publicationService;

    @Override
    public IngestionStageEnum stage() {
        return IngestionStageEnum.PUBLISH;
    }

    @Override
    public IngestionStageResult process(IngestionTaskLease lease) {
        int chunkCount = publicationService.publish(lease);
        return IngestionStageResult.completed(chunkCount, chunkCount);
    }
}
