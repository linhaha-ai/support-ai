package com.haha.supportai.knowledge.document.embedding.service;

import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.knowledge.document.entity.dto.ProcessingConfigurationSnapshot;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentProcessingConfigurationException;
import com.haha.supportai.knowledge.document.support.DocumentProcessingSnapshotFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentEmbeddingConfigurationValidator {

    private final DocumentProcessingSnapshotFactory snapshotFactory;
    private final ObjectMapper objectMapper;

    public ProcessingConfigurationSnapshot requireCurrent(DocumentVersion version) {
        ProcessingConfigurationSnapshot current = snapshotFactory.create();
        if (!Objects.equals(version.getEmbeddingModel(), current.getEmbeddingModel())
                || version.getDimensions() != current.getDimensions()
                || !Objects.equals(version.getConfigHash(), current.getConfigHash())
                || !sameConfiguration(version.getConfigSnapshot(), current.getConfigSnapshot())) {
            throw new DocumentProcessingConfigurationException();
        }
        return current;
    }

    private boolean sameConfiguration(String stored, String current) {
        try {
            return objectMapper.readTree(stored).equals(objectMapper.readTree(current));
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new DocumentProcessingConfigurationException(exception);
        }
    }
}
