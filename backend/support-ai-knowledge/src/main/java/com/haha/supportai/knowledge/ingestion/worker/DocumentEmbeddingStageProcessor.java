package com.haha.supportai.knowledge.ingestion.worker;

import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.knowledge.document.embedding.service.DocumentEmbeddingService;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionStageResult;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentEmbeddingStageProcessor implements IngestionStageProcessor {

    private final DocumentEmbeddingService documentEmbeddingService;

    @Override
    public IngestionStageEnum stage() {
        return IngestionStageEnum.EMBED;
    }

    @Override
    public IngestionStageResult process(IngestionTaskLease lease) {
        int chunkCount = documentEmbeddingService.embed(lease);
        return IngestionStageResult.advance(IngestionStageEnum.PUBLISH, chunkCount, chunkCount);
    }
}
