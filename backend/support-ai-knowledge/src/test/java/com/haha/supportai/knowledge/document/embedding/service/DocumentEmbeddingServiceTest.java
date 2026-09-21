package com.haha.supportai.knowledge.document.embedding.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.data.knowledge.entity.po.DocumentChunk;
import com.haha.supportai.data.knowledge.entity.po.DocumentVector;
import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVectorRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.knowledge.document.embedding.config.DocumentEmbeddingProperties;
import com.haha.supportai.knowledge.document.embedding.exception.DocumentEmbeddingFailedException;
import com.haha.supportai.knowledge.document.entity.dto.ProcessingConfigurationSnapshot;
import com.haha.supportai.knowledge.document.parsing.entity.dto.DocumentChunkDraft;
import com.haha.supportai.knowledge.document.parsing.entity.dto.DocumentSection;
import com.haha.supportai.knowledge.document.parsing.entity.dto.ParsedDocument;
import com.haha.supportai.knowledge.document.parsing.service.DocumentSourceParsingService;
import com.haha.supportai.knowledge.document.parsing.service.TokenBudgetChunker;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import com.haha.supportai.knowledge.ingestion.exception.IngestionLeaseLostException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentEmbeddingServiceTest {

    private static final UUID JOB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440811");
    private static final UUID VERSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440812");
    private static final UUID DOCUMENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440813");
    private static final UUID LEASE_TOKEN = UUID.fromString("550e8400-e29b-41d4-a716-446655440814");

    @Mock
    EmbeddingModel embeddingModel;

    @Mock
    DocumentVersionRepository documentVersionRepository;

    @Mock
    DocumentChunkRepository documentChunkRepository;

    @Mock
    DocumentVectorRepository documentVectorRepository;

    @Mock
    DocumentSourceParsingService documentSourceParsingService;

    @Mock
    TokenBudgetChunker tokenBudgetChunker;

    @Mock
    DocumentEmbeddingConfigurationValidator configurationValidator;

    @Mock
    DocumentVectorPersistenceService persistenceService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DocumentEmbeddingService documentEmbeddingService;

    @BeforeEach
    void setUp() {
        DocumentEmbeddingProperties properties = new DocumentEmbeddingProperties();
        properties.setBatchSize(2);
        documentEmbeddingService = new DocumentEmbeddingService(embeddingModel, documentVersionRepository,
                documentChunkRepository, documentVectorRepository, documentSourceParsingService, tokenBudgetChunker,
                configurationValidator, persistenceService, properties, objectMapper);
    }

    @Test
    void embedsOnlyMissingContinuousSuffixWithValidated1024DimensionVectors() throws Exception {
        IngestionTaskLease lease = lease();
        ProcessingConfigurationSnapshot snapshot = snapshot();
        DocumentVersion version = version(snapshot);
        List<DocumentChunkDraft> drafts = drafts();
        List<DocumentChunk> chunks = chunks();

        when(documentVersionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));
        when(configurationValidator.requireCurrent(version)).thenReturn(snapshot);
        when(documentSourceParsingService.parse(VERSION_ID)).thenReturn(new ParsedDocument("第一段第二段第三段",
                List.of(new DocumentSection("支付", 0, 9))));
        when(tokenBudgetChunker.chunk(any())).thenReturn(drafts);
        when(documentChunkRepository.findByVersionId(VERSION_ID)).thenReturn(chunks);
        when(documentVectorRepository.findByVersionId(VERSION_ID)).thenReturn(List.of(new DocumentVector(
                chunks.getFirst().getId(), drafts.getFirst().getContent(), metadata(version, chunks.getFirst(), snapshot),
                null)));
        when(embeddingModel.embed(List.of("第二段", "第三段"))).thenReturn(List.of(vector(1), vector(2)));

        int embedded = documentEmbeddingService.embed(lease);

        assertThat(embedded).isEqualTo(3);
        ArgumentCaptor<List<DocumentVector>> vectorsCaptor = ArgumentCaptor.forClass(List.class);
        verify(persistenceService).persistBatch(eq(lease), eq(chunks.subList(1, 3)), vectorsCaptor.capture(), eq(3), eq(3));
        assertThat(vectorsCaptor.getValue()).extracting(DocumentVector::getEmbedding)
                .allSatisfy(vector -> {
                    assertThat(vector).hasSize(1024);
                    for (float value : vector) {
                        assertThat(Float.isFinite(value)).isTrue();
                    }
                });
        verify(persistenceService).validateLease(lease);
        verify(embeddingModel, times(1)).embed(List.of("第二段", "第三段"));
    }

    @Test
    void invalidEmbeddingResponseCountDoesNotPersistBatch() {
        IngestionTaskLease lease = lease();
        ProcessingConfigurationSnapshot snapshot = snapshot();
        DocumentVersion version = version(snapshot);
        when(documentVersionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));
        when(configurationValidator.requireCurrent(version)).thenReturn(snapshot);
        when(documentSourceParsingService.parse(VERSION_ID)).thenReturn(new ParsedDocument("第一段第二段第三段",
                List.of(new DocumentSection("支付", 0, 9))));
        when(tokenBudgetChunker.chunk(any())).thenReturn(drafts());
        when(documentChunkRepository.findByVersionId(VERSION_ID)).thenReturn(chunks());
        when(documentVectorRepository.findByVersionId(VERSION_ID)).thenReturn(List.of());
        when(embeddingModel.embed(List.of("第一段", "第二段"))).thenReturn(List.of(vector(1)));

        assertThatThrownBy(() -> documentEmbeddingService.embed(lease))
                .isInstanceOf(DocumentEmbeddingFailedException.class);

        verify(persistenceService).validateLease(lease);
        verify(persistenceService, never()).persistBatch(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void completeContinuousPrefixDoesNotCallEmbeddingModelAgain() {
        IngestionTaskLease lease = lease();
        ProcessingConfigurationSnapshot snapshot = snapshot();
        DocumentVersion version = version(snapshot);
        List<DocumentChunkDraft> drafts = drafts();
        List<DocumentChunk> chunks = chunks();
        List<DocumentVector> persistedVectors = new ArrayList<>();
        when(documentVersionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));
        when(configurationValidator.requireCurrent(version)).thenReturn(snapshot);
        when(documentSourceParsingService.parse(VERSION_ID)).thenReturn(new ParsedDocument("第一段第二段第三段",
                List.of(new DocumentSection("支付", 0, 9))));
        when(tokenBudgetChunker.chunk(any())).thenReturn(drafts);
        when(documentChunkRepository.findByVersionId(VERSION_ID)).thenReturn(chunks);
        when(documentVectorRepository.findByVersionId(VERSION_ID)).thenReturn(List.of());
        when(embeddingModel.embed(List.of("第一段", "第二段"))).thenReturn(List.of(vector(1), vector(2)));
        when(embeddingModel.embed(List.of("第三段"))).thenReturn(List.of(vector(3)));
        doAnswer(invocation -> {
            persistedVectors.addAll(invocation.getArgument(2));
            return null;
        }).when(persistenceService).persistBatch(any(), any(), any(), anyInt(), anyInt());

        assertThat(documentEmbeddingService.embed(lease)).isEqualTo(3);

        reset(embeddingModel, persistenceService);
        when(documentVectorRepository.findByVersionId(VERSION_ID)).thenReturn(List.copyOf(persistedVectors));
        assertThat(documentEmbeddingService.embed(lease)).isEqualTo(3);

        verifyNoInteractions(embeddingModel);
        verify(persistenceService, never()).persistBatch(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void lostLeaseBeforeModelCallDoesNotCallEmbeddingModel() {
        IngestionTaskLease lease = lease();
        ProcessingConfigurationSnapshot snapshot = snapshot();
        DocumentVersion version = version(snapshot);
        when(documentVersionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));
        when(configurationValidator.requireCurrent(version)).thenReturn(snapshot);
        when(documentSourceParsingService.parse(VERSION_ID)).thenReturn(new ParsedDocument("第一段第二段第三段",
                List.of(new DocumentSection("支付", 0, 9))));
        when(tokenBudgetChunker.chunk(any())).thenReturn(drafts());
        when(documentChunkRepository.findByVersionId(VERSION_ID)).thenReturn(chunks());
        when(documentVectorRepository.findByVersionId(VERSION_ID)).thenReturn(List.of());
        doThrow(new IngestionLeaseLostException()).when(persistenceService).validateLease(lease);

        assertThatThrownBy(() -> documentEmbeddingService.embed(lease)).isInstanceOf(IngestionLeaseLostException.class);

        verifyNoInteractions(embeddingModel);
        verify(persistenceService, never()).persistBatch(any(), any(), any(), anyInt(), anyInt());
    }

    private IngestionTaskLease lease() {
        return new IngestionTaskLease(JOB_ID, VERSION_ID, IngestionStageEnum.EMBED, 1, 3, "f08-test-worker",
                LEASE_TOKEN, OffsetDateTime.parse("2026-09-23T00:00:00Z"));
    }

    private ProcessingConfigurationSnapshot snapshot() {
        return new ProcessingConfigurationSnapshot("bge-m3", 1024, "c".repeat(64),
                "{\"parser\":{\"version\":\"plain-text-v1\"},\"chunker\":{\"version\":\"token-budget-v1\"},"
                        + "\"embedding\":{\"provider\":\"ollama\",\"model\":\"bge-m3\",\"dimensions\":1024}}");
    }

    private DocumentVersion version(ProcessingConfigurationSnapshot snapshot) {
        DocumentVersion version = new DocumentVersion();
        version.setId(VERSION_ID);
        version.setDocumentId(DOCUMENT_ID);
        version.setEmbeddingModel(snapshot.getEmbeddingModel());
        version.setDimensions(snapshot.getDimensions());
        version.setConfigHash(snapshot.getConfigHash());
        version.setConfigSnapshot(snapshot.getConfigSnapshot());
        version.setChunkCount(3);
        return version;
    }

    private List<DocumentChunkDraft> drafts() {
        return List.of(draft(0, "第一段"), draft(1, "第二段"), draft(2, "第三段"));
    }

    private List<DocumentChunk> chunks() {
        List<DocumentChunkDraft> drafts = drafts();
        return List.of(chunk(0, drafts.get(0)), chunk(1, drafts.get(1)), chunk(2, drafts.get(2)));
    }

    private DocumentChunk chunk(int index, DocumentChunkDraft draft) {
        return new DocumentChunk(UUID.nameUUIDFromBytes((VERSION_ID + ":" + index).getBytes(StandardCharsets.UTF_8)),
                VERSION_ID, index, "支付", null, draft.getStartOffset(), draft.getEndOffset(), draft.getContentHash(),
                draft.getCharCount(), draft.getTokenCount());
    }

    private DocumentChunkDraft draft(int index, String content) {
        return new DocumentChunkDraft(index, "支付", index * 3, index * 3 + content.length(), sha256(content),
                content.length(), 2, content);
    }

    private String metadata(DocumentVersion version, DocumentChunk chunk, ProcessingConfigurationSnapshot snapshot)
            throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("documentId", version.getDocumentId());
        values.put("versionId", version.getId());
        values.put("chunkIndex", chunk.getChunkIndex());
        values.put("section", chunk.getSection());
        values.put("pageNo", chunk.getPageNo());
        values.put("startOffset", chunk.getStartOffset());
        values.put("endOffset", chunk.getEndOffset());
        values.put("contentHash", chunk.getContentHash());
        values.put("charCount", chunk.getCharCount());
        values.put("tokenCount", chunk.getTokenCount());
        values.put("embeddingProvider", "ollama");
        values.put("embeddingModel", version.getEmbeddingModel());
        values.put("dimensions", version.getDimensions());
        values.put("configHash", snapshot.getConfigHash());
        return objectMapper.writeValueAsString(values);
    }

    private float[] vector(int value) {
        float[] vector = new float[1024];
        vector[0] = value;
        return vector;
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError("测试环境不支持 SHA-256", exception);
        }
    }
}
