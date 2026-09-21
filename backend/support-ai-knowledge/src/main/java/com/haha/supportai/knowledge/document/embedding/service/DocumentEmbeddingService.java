package com.haha.supportai.knowledge.document.embedding.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.ai.model.ValidatedEmbeddingModel;
import com.haha.supportai.data.knowledge.entity.po.DocumentChunk;
import com.haha.supportai.data.knowledge.entity.po.DocumentVector;
import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVectorRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.knowledge.document.embedding.config.DocumentEmbeddingProperties;
import com.haha.supportai.knowledge.document.embedding.exception.DocumentEmbeddingFailedException;
import com.haha.supportai.knowledge.document.embedding.exception.DocumentIndexIntegrityException;
import com.haha.supportai.knowledge.document.entity.dto.ProcessingConfigurationSnapshot;
import com.haha.supportai.knowledge.document.parsing.entity.dto.DocumentChunkDraft;
import com.haha.supportai.knowledge.document.parsing.entity.dto.ParsedDocument;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentProcessingConfigurationException;
import com.haha.supportai.knowledge.document.parsing.service.DocumentSourceParsingService;
import com.haha.supportai.knowledge.document.parsing.service.TokenBudgetChunker;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import com.haha.supportai.knowledge.ingestion.exception.IngestionStateConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DocumentEmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentVectorRepository documentVectorRepository;
    private final DocumentSourceParsingService documentSourceParsingService;
    private final TokenBudgetChunker tokenBudgetChunker;
    private final DocumentEmbeddingConfigurationValidator configurationValidator;
    private final DocumentVectorPersistenceService persistenceService;
    private final DocumentEmbeddingProperties properties;
    private final ObjectMapper objectMapper;

    public int embed(IngestionTaskLease lease) {
        if (lease.getStage() != IngestionStageEnum.EMBED) {
            throw new IngestionStateConflictException("向量生成阶段无效");
        }
        DocumentVersion version = documentVersionRepository.findById(lease.getVersionId())
                .orElseThrow(DocumentProcessingConfigurationException::new);
        ProcessingConfigurationSnapshot snapshot = configurationValidator.requireCurrent(version);
        ParsedDocument parsedDocument = documentSourceParsingService.parse(lease.getVersionId());
        List<DocumentChunkDraft> drafts = tokenBudgetChunker.chunk(parsedDocument);
        List<DocumentChunk> chunks = documentChunkRepository.findByVersionId(lease.getVersionId());
        validateChunks(version, chunks, drafts);

        List<String> metadata = createMetadata(version, chunks, snapshot);
        int completedChunks = validateCompletedPrefix(chunks, drafts, metadata,
                documentVectorRepository.findByVersionId(lease.getVersionId()));
        int totalChunks = chunks.size();
        for (int start = completedChunks; start < totalChunks; start += properties.getBatchSize()) {
            int end = Math.min(start + properties.getBatchSize(), totalChunks);
            persistenceService.validateLease(lease);
            List<float[]> embeddings = createEmbeddings(drafts.subList(start, end));
            List<DocumentVector> vectors = new ArrayList<>(end - start);
            for (int index = start; index < end; index++) {
                vectors.add(new DocumentVector(chunks.get(index).getId(), drafts.get(index).getContent(),
                        metadata.get(index), embeddings.get(index - start)));
            }
            persistenceService.persistBatch(lease, chunks.subList(start, end), vectors, end, totalChunks);
        }
        return totalChunks;
    }

    private void validateChunks(DocumentVersion version, List<DocumentChunk> chunks,
            List<DocumentChunkDraft> drafts) {
        if (chunks == null || drafts == null || chunks.isEmpty() || chunks.size() != drafts.size()
                || version.getChunkCount() != chunks.size()) {
            throw new DocumentIndexIntegrityException();
        }
        for (int index = 0; index < chunks.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            DocumentChunkDraft draft = drafts.get(index);
            if (chunk == null || draft == null || chunk.getChunkIndex() != index || draft.getChunkIndex() != index
                    || !version.getId().equals(chunk.getVersionId())
                    || !Objects.equals(chunk.getSection(), emptyToNull(draft.getSection()))
                    || chunk.getStartOffset() != draft.getStartOffset()
                    || chunk.getEndOffset() != draft.getEndOffset()
                    || !Objects.equals(chunk.getContentHash(), draft.getContentHash())
                    || chunk.getCharCount() != draft.getCharCount()
                    || !Objects.equals(chunk.getTokenCount(), draft.getTokenCount())) {
                throw new DocumentIndexIntegrityException();
            }
        }
    }

    private List<String> createMetadata(DocumentVersion version, List<DocumentChunk> chunks,
            ProcessingConfigurationSnapshot snapshot) {
        String provider = embeddingProvider(snapshot.getConfigSnapshot());
        List<String> metadata = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
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
            values.put("embeddingProvider", provider);
            values.put("embeddingModel", version.getEmbeddingModel());
            values.put("dimensions", version.getDimensions());
            values.put("configHash", version.getConfigHash());
            metadata.add(writeJson(values));
        }
        return List.copyOf(metadata);
    }

    private int validateCompletedPrefix(List<DocumentChunk> chunks, List<DocumentChunkDraft> drafts,
            List<String> metadata, List<DocumentVector> existingVectors) {
        if (existingVectors == null || existingVectors.size() > chunks.size()) {
            throw new DocumentIndexIntegrityException();
        }
        for (int index = 0; index < existingVectors.size(); index++) {
            DocumentVector vector = existingVectors.get(index);
            if (vector == null || !chunks.get(index).getId().equals(vector.getId())
                    || !drafts.get(index).getContent().equals(vector.getContent())
                    || !sameMetadata(metadata.get(index), vector.getMetadata())) {
                throw new DocumentIndexIntegrityException();
            }
        }
        return existingVectors.size();
    }

    private List<float[]> createEmbeddings(List<DocumentChunkDraft> drafts) {
        List<float[]> embeddings;
        try {
            embeddings = embeddingModel.embed(drafts.stream().map(DocumentChunkDraft::getContent).toList());
        } catch (RuntimeException exception) {
            throw new DocumentEmbeddingFailedException(exception);
        }
        if (embeddings == null || embeddings.size() != drafts.size()) {
            throw new DocumentEmbeddingFailedException(
                    new IllegalStateException("Embedding response count does not match input count"));
        }
        for (float[] embedding : embeddings) {
            validateEmbedding(embedding);
        }
        return embeddings;
    }

    private void validateEmbedding(float[] embedding) {
        if (embedding == null || embedding.length != ValidatedEmbeddingModel.DIMENSIONS) {
            throw new DocumentEmbeddingFailedException(
                    new IllegalStateException("Embedding response must contain 1024 dimensions"));
        }
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new DocumentEmbeddingFailedException(
                        new IllegalStateException("Embedding response contains a non-finite value"));
            }
        }
    }

    private String embeddingProvider(String configSnapshot) {
        try {
            JsonNode snapshot = objectMapper.readTree(configSnapshot);
            String provider = snapshot.path("embedding").path("provider").asText();
            if (provider.isBlank()) {
                throw new DocumentProcessingConfigurationException();
            }
            return provider;
        } catch (JsonProcessingException exception) {
            throw new DocumentProcessingConfigurationException(exception);
        }
    }

    private String writeJson(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new DocumentProcessingConfigurationException(exception);
        }
    }

    private boolean sameMetadata(String expected, String actual) {
        try {
            return objectMapper.readTree(expected).equals(objectMapper.readTree(actual));
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new DocumentIndexIntegrityException();
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
