package com.haha.supportai.knowledge.document.parsing.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.knowledge.document.config.DocumentUploadProperties;
import com.haha.supportai.knowledge.document.parsing.config.DocumentParsingProperties;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentEmptyException;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentProcessingConfigurationException;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentReadFailedException;
import com.haha.supportai.storage.FileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentSourceParsingServiceTest {

    private static final UUID VERSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440101");
    private static final String STORAGE_KEY = "2026/09/source.md";

    @Mock
    DocumentVersionRepository documentVersionRepository;

    @Mock
    FileStore fileStore;

    private DocumentSourceParsingService documentSourceParsingService;

    @BeforeEach
    void setUp() {
        DocumentUploadProperties uploadProperties = new DocumentUploadProperties();
        uploadProperties.setMaxFileSize(DataSize.ofBytes(64));
        DocumentParsingProperties parsingProperties = new DocumentParsingProperties();
        parsingProperties.setMaxTokens(32);
        parsingProperties.setMaxChunks(10);
        DocumentSourceReader documentSourceReader = new DocumentSourceReader(fileStore, uploadProperties);
        documentSourceParsingService = new DocumentSourceParsingService(documentVersionRepository,
                documentSourceReader, new DocumentTextParser(), parsingProperties, new ObjectMapper());
    }

    @Test
    void readsOnlyVerifiedUtf8SourceFromControlledStorage() {
        byte[] content = "# 支付\r\n处理说明".getBytes(StandardCharsets.UTF_8);
        stubVersion(content, validSnapshot());
        when(fileStore.open(STORAGE_KEY)).thenReturn(new ByteArrayInputStream(content));

        var parsed = documentSourceParsingService.parse(VERSION_ID);

        assertThat(parsed.getNormalizedText()).isEqualTo("# 支付\n处理说明");
        verify(fileStore).open(STORAGE_KEY);
    }

    @Test
    void rejectsSourceWhenActualSizeOrHashDoesNotMatchVersionRecord() {
        byte[] content = "原文件内容".getBytes(StandardCharsets.UTF_8);
        DocumentVersion version = version(content, validSnapshot());
        version.setFileHash("a".repeat(64));
        when(documentVersionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));
        when(fileStore.open(STORAGE_KEY)).thenReturn(new ByteArrayInputStream(content));

        assertThatThrownBy(() -> documentSourceParsingService.parse(VERSION_ID))
                .isInstanceOf(DocumentReadFailedException.class)
                .hasMessage("原文件暂时无法读取");
    }

    @Test
    void rejectsSourceWhenActualSizeDoesNotMatchVersionRecord() {
        byte[] content = "原文件内容".getBytes(StandardCharsets.UTF_8);
        DocumentVersion version = version(content, validSnapshot());
        version.setFileSize(content.length + 1L);
        when(documentVersionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));
        when(fileStore.open(STORAGE_KEY)).thenReturn(new ByteArrayInputStream(content));

        assertThatThrownBy(() -> documentSourceParsingService.parse(VERSION_ID))
                .isInstanceOf(DocumentReadFailedException.class)
                .hasMessage("原文件暂时无法读取");
    }

    @Test
    void rejectsSourceThatExceedsConfiguredReadLimit() {
        byte[] content = "超过读取限制的原文件内容".getBytes(StandardCharsets.UTF_8);
        DocumentVersion version = version(content, validSnapshot());
        version.setFileSize(65);
        when(documentVersionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> documentSourceParsingService.parse(VERSION_ID))
                .isInstanceOf(DocumentReadFailedException.class);
    }

    @Test
    void rejectsMalformedUtf8BeforeTextParsing() {
        byte[] content = { (byte) 0xC3, (byte) 0x28 };
        stubVersion(content, validSnapshot());
        when(fileStore.open(STORAGE_KEY)).thenReturn(new ByteArrayInputStream(content));

        assertThatThrownBy(() -> documentSourceParsingService.parse(VERSION_ID))
                .isInstanceOf(DocumentReadFailedException.class);
    }

    @Test
    void rejectsIncompatibleConfigurationSnapshotAndEmptyDocument() {
        byte[] source = "有效内容".getBytes(StandardCharsets.UTF_8);
        stubVersion(source, "{\"parser\":{\"version\":\"other\"},\"chunker\":{\"version\":\"token-budget-v1\"}}");

        assertThatThrownBy(() -> documentSourceParsingService.parse(VERSION_ID))
                .isInstanceOf(DocumentProcessingConfigurationException.class);

        byte[] blank = " \r\n\t".getBytes(StandardCharsets.UTF_8);
        stubVersion(blank, validSnapshot());
        when(fileStore.open(STORAGE_KEY)).thenReturn(new ByteArrayInputStream(blank));
        assertThatThrownBy(() -> documentSourceParsingService.parse(VERSION_ID))
                .isInstanceOf(DocumentEmptyException.class)
                .hasMessage("文档没有可处理内容");
    }

    @Test
    void rejectsSnapshotWithoutReproducibleChunkerSettings() {
        byte[] content = "有效内容".getBytes(StandardCharsets.UTF_8);
        stubVersion(content, "{\"parser\":{\"version\":\"plain-text-v1\"},"
                + "\"chunker\":{\"version\":\"token-budget-v1\"}}");

        assertThatThrownBy(() -> documentSourceParsingService.parse(VERSION_ID))
                .isInstanceOf(DocumentProcessingConfigurationException.class);
    }

    private void stubVersion(byte[] content, String snapshot) {
        when(documentVersionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version(content, snapshot)));
    }

    private DocumentVersion version(byte[] content, String snapshot) {
        DocumentVersion version = new DocumentVersion();
        version.setId(VERSION_ID);
        version.setStorageKey(STORAGE_KEY);
        version.setFileSize(content.length);
        version.setFileHash(sha256(content));
        version.setMediaType("text/markdown");
        version.setParserVersion("plain-text-v1");
        version.setChunkerVersion("token-budget-v1");
        version.setConfigSnapshot(snapshot);
        return version;
    }

    private String validSnapshot() {
        return "{\"parser\":{\"version\":\"plain-text-v1\"},\"chunker\":{\"version\":\"token-budget-v1\","
                + "\"maxTokens\":32,\"maxChunks\":10,\"tokenizer\":\"CL100K_BASE\"}}";
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new AssertionError("测试环境不支持 SHA-256", exception);
        }
    }
}
