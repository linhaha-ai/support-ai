package com.haha.supportai.knowledge.ingestion.worker;

import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.knowledge.document.parsing.service.DocumentChunkPersistenceService;
import com.haha.supportai.knowledge.document.parsing.service.DocumentSourceParsingService;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionStageResult;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentParseStageProcessor implements IngestionStageProcessor {

    private final DocumentSourceParsingService documentSourceParsingService;
    private final DocumentChunkPersistenceService documentChunkPersistenceService;

    @Override
    public IngestionStageEnum stage() {
        return IngestionStageEnum.PARSE;
    }

    @Override
    public IngestionStageResult process(IngestionTaskLease lease) {
        documentSourceParsingService.parse(lease.getVersionId());
        documentChunkPersistenceService.markParsed(lease);
        return IngestionStageResult.advance(IngestionStageEnum.CHUNK, null, null);
    }
}
