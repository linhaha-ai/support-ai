package com.haha.supportai.knowledge.document.embedding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.knowledge.document.entity.dto.ProcessingConfigurationSnapshot;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentProcessingConfigurationException;
import com.haha.supportai.knowledge.document.support.DocumentProcessingSnapshotFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentEmbeddingConfigurationValidatorTest {

    @Mock
    DocumentProcessingSnapshotFactory snapshotFactory;

    private DocumentEmbeddingConfigurationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DocumentEmbeddingConfigurationValidator(snapshotFactory, new ObjectMapper());
    }

    @Test
    void semanticallyEqualJsonSnapshotWithDifferentWhitespaceAndKeyOrderPasses() {
        ProcessingConfigurationSnapshot current = snapshot("bge-m3", 1024,
                "{\"embedding\":{\"provider\":\"ollama\",\"model\":\"bge-m3\",\"dimensions\":1024},"
                        + "\"parser\":{\"version\":\"plain-text-v1\"}}");
        DocumentVersion version = version("bge-m3", 1024, current.getConfigHash(), """
                { "parser" : { "version" : "plain-text-v1" },
                  "embedding" : { "dimensions" : 1024, "model" : "bge-m3", "provider" : "ollama" } }
                """);
        when(snapshotFactory.create()).thenReturn(current);

        assertThat(validator.requireCurrent(version)).isSameAs(current);
    }

    @Test
    void changedProviderModelOrDimensionsFailsConfigurationValidation() {
        ProcessingConfigurationSnapshot current = snapshot("bge-m3", 1024,
                "{\"embedding\":{\"provider\":\"ollama\",\"model\":\"bge-m3\",\"dimensions\":1024}}");
        when(snapshotFactory.create()).thenReturn(current);

        assertThatThrownBy(() -> validator.requireCurrent(version("bge-m3", 1024, current.getConfigHash(),
                "{\"embedding\":{\"provider\":\"dashscope\",\"model\":\"bge-m3\",\"dimensions\":1024}}")))
                .isInstanceOf(DocumentProcessingConfigurationException.class);
        assertThatThrownBy(() -> validator.requireCurrent(version("text-embedding-v4", 1024,
                current.getConfigHash(), current.getConfigSnapshot())))
                .isInstanceOf(DocumentProcessingConfigurationException.class);
        assertThatThrownBy(() -> validator.requireCurrent(version("bge-m3", 768, current.getConfigHash(),
                current.getConfigSnapshot()))).isInstanceOf(DocumentProcessingConfigurationException.class);
    }

    private ProcessingConfigurationSnapshot snapshot(String model, int dimensions, String json) {
        return new ProcessingConfigurationSnapshot(model, dimensions, "a".repeat(64), json);
    }

    private DocumentVersion version(String model, int dimensions, String configHash, String configSnapshot) {
        DocumentVersion version = new DocumentVersion();
        version.setEmbeddingModel(model);
        version.setDimensions(dimensions);
        version.setConfigHash(configHash);
        version.setConfigSnapshot(configSnapshot);
        return version;
    }
}
