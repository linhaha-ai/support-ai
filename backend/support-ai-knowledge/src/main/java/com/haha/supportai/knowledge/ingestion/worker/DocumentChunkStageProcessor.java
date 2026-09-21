package com.haha.supportai.knowledge.ingestion.worker;

import java.util.List;

import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.knowledge.document.parsing.entity.dto.DocumentChunkDraft;
import com.haha.supportai.knowledge.document.parsing.entity.dto.ParsedDocument;
import com.haha.supportai.knowledge.document.parsing.service.DocumentChunkPersistenceService;
import com.haha.supportai.knowledge.document.parsing.service.DocumentSourceParsingService;
import com.haha.supportai.knowledge.document.parsing.service.TokenBudgetChunker;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionStageResult;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentChunkStageProcessor implements IngestionStageProcessor {

    private final DocumentSourceParsingService documentSourceParsingService;
    private final TokenBudgetChunker tokenBudgetChunker;
    private final DocumentChunkPersistenceService documentChunkPersistenceService;

    @Override
    public IngestionStageEnum stage() {
        return IngestionStageEnum.CHUNK;
    }

    @Override
    public IngestionStageResult process(IngestionTaskLease lease) {
        ParsedDocument parsedDocument = documentSourceParsingService.parse(lease.getVersionId());
        List<DocumentChunkDraft> chunks = tokenBudgetChunker.chunk(parsedDocument);
        documentChunkPersistenceService.replaceChunks(lease, chunks);
        return IngestionStageResult.advance(IngestionStageEnum.EMBED, 0, chunks.size());
    }
}
