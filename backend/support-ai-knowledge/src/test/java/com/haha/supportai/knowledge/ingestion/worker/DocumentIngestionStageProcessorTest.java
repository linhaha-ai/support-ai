package com.haha.supportai.knowledge.ingestion.worker;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.knowledge.document.embedding.service.DocumentEmbeddingService;
import com.haha.supportai.knowledge.document.embedding.service.DocumentIndexPublicationService;
import com.haha.supportai.knowledge.document.parsing.entity.dto.DocumentChunkDraft;
import com.haha.supportai.knowledge.document.parsing.entity.dto.DocumentSection;
import com.haha.supportai.knowledge.document.parsing.entity.dto.ParsedDocument;
import com.haha.supportai.knowledge.document.parsing.service.DocumentChunkPersistenceService;
import com.haha.supportai.knowledge.document.parsing.service.DocumentSourceParsingService;
import com.haha.supportai.knowledge.document.parsing.service.TokenBudgetChunker;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionStageProcessorTest {

    private static final UUID JOB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440301");
    private static final UUID VERSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440302");
    private static final UUID LEASE_TOKEN = UUID.fromString("550e8400-e29b-41d4-a716-446655440303");

    @Mock
    DocumentSourceParsingService documentSourceParsingService;

    @Mock
    TokenBudgetChunker tokenBudgetChunker;

    @Mock
    DocumentChunkPersistenceService documentChunkPersistenceService;

    @Mock
    DocumentEmbeddingService documentEmbeddingService;

    @Mock
    DocumentIndexPublicationService documentIndexPublicationService;

    @Test
    void parseProcessorOnlyAdvancesFromParseToChunk() {
        IngestionTaskLease lease = lease(IngestionStageEnum.PARSE);
        DocumentParseStageProcessor processor = new DocumentParseStageProcessor(documentSourceParsingService,
                documentChunkPersistenceService);

        var result = processor.process(lease);

        assertThat(processor.stage()).isEqualTo(IngestionStageEnum.PARSE);
        assertThat(result.getNextStage()).isEqualTo(IngestionStageEnum.CHUNK);
        assertThat(result.getCompletedChunks()).isNull();
        assertThat(result.getTotalChunks()).isNull();
        verify(documentSourceParsingService).parse(VERSION_ID);
        verify(documentChunkPersistenceService).markParsed(lease);
    }

    @Test
    void chunkProcessorPersistsDraftsAndAdvancesToEmbedWithZeroProgress() {
        IngestionTaskLease lease = lease(IngestionStageEnum.CHUNK);
        ParsedDocument parsedDocument = parsedDocument();
        List<DocumentChunkDraft> chunks = List.of(
                new DocumentChunkDraft(0, "", 0, 2, "a".repeat(64), 2, 1, "内容"));
        when(documentSourceParsingService.parse(VERSION_ID)).thenReturn(parsedDocument);
        when(tokenBudgetChunker.chunk(parsedDocument)).thenReturn(chunks);
        DocumentChunkStageProcessor processor = new DocumentChunkStageProcessor(documentSourceParsingService,
                tokenBudgetChunker, documentChunkPersistenceService);

        var result = processor.process(lease);

        assertThat(processor.stage()).isEqualTo(IngestionStageEnum.CHUNK);
        assertThat(result.getNextStage()).isEqualTo(IngestionStageEnum.EMBED);
        assertThat(result.getCompletedChunks()).isZero();
        assertThat(result.getTotalChunks()).isEqualTo(1);
        verify(documentChunkPersistenceService).replaceChunks(lease, chunks);
    }

    @Test
    void embeddingProcessorAdvancesToPublishAfterReturningCompleteChunkCount() {
        IngestionTaskLease lease = lease(IngestionStageEnum.EMBED);
        when(documentEmbeddingService.embed(lease)).thenReturn(2);
        DocumentEmbeddingStageProcessor processor = new DocumentEmbeddingStageProcessor(documentEmbeddingService);

        var result = processor.process(lease);

        assertThat(processor.stage()).isEqualTo(IngestionStageEnum.EMBED);
        assertThat(result.isTerminal()).isFalse();
        assertThat(result.getNextStage()).isEqualTo(IngestionStageEnum.PUBLISH);
        assertThat(result.getCompletedChunks()).isEqualTo(2);
        assertThat(result.getTotalChunks()).isEqualTo(2);
    }

    @Test
    void publishProcessorReturnsTerminalResultAfterAtomicPublication() {
        IngestionTaskLease lease = lease(IngestionStageEnum.PUBLISH);
        when(documentIndexPublicationService.publish(lease)).thenReturn(2);
        DocumentPublishStageProcessor processor = new DocumentPublishStageProcessor(documentIndexPublicationService);

        var result = processor.process(lease);

        assertThat(processor.stage()).isEqualTo(IngestionStageEnum.PUBLISH);
        assertThat(result.isTerminal()).isTrue();
        assertThat(result.getNextStage()).isNull();
        assertThat(result.getCompletedChunks()).isEqualTo(2);
        assertThat(result.getTotalChunks()).isEqualTo(2);
    }

    private IngestionTaskLease lease(IngestionStageEnum stage) {
        return new IngestionTaskLease(JOB_ID, VERSION_ID, stage, 1, 3, "worker-test", LEASE_TOKEN,
                OffsetDateTime.parse("2026-09-23T00:00:00Z"));
    }

    private ParsedDocument parsedDocument() {
        return new ParsedDocument("内容", List.of(new DocumentSection("", 0, 2)));
    }
}
