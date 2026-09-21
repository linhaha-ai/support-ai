package com.haha.supportai.knowledge.document.parsing.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.knowledge.document.config.DocumentUploadProperties;
import com.haha.supportai.knowledge.document.parsing.config.DocumentParsingProperties;
import com.haha.supportai.knowledge.document.parsing.entity.dto.ParsedDocument;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentProcessingConfigurationException;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentReadFailedException;
import com.haha.supportai.storage.FileStore;
import com.haha.supportai.storage.exception.StorageKeyInvalidException;
import com.haha.supportai.storage.exception.StorageReadException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DocumentSourceParsingService {

    private static final String SUPPORTED_PARSER_VERSION = "plain-text-v1";
    private static final String SUPPORTED_CHUNKER_VERSION = "token-budget-v1";
    private static final String TOKENIZER = "CL100K_BASE";
    private static final int READ_BUFFER_SIZE = 8192;

    private final DocumentVersionRepository documentVersionRepository;
    private final FileStore fileStore;
    private final DocumentTextParser documentTextParser;
    private final DocumentUploadProperties uploadProperties;
    private final DocumentParsingProperties parsingProperties;
    private final ObjectMapper objectMapper;

    public ParsedDocument parse(UUID versionId) {
        DocumentVersion version = documentVersionRepository.findById(versionId)
                .orElseThrow(DocumentProcessingConfigurationException::new);
        validateConfiguration(version);
        byte[] source = readAndVerify(version);
        return documentTextParser.parse(decodeUtf8(source), version.getMediaType());
    }

    private void validateConfiguration(DocumentVersion version) {
        if (!SUPPORTED_PARSER_VERSION.equals(version.getParserVersion())
                || !SUPPORTED_CHUNKER_VERSION.equals(version.getChunkerVersion())) {
            throw new DocumentProcessingConfigurationException();
        }
        try {
            JsonNode snapshot = objectMapper.readTree(version.getConfigSnapshot());
            JsonNode parser = snapshot.path("parser");
            JsonNode chunker = snapshot.path("chunker");
            if (!version.getParserVersion().equals(parser.path("version").asText())
                    || !version.getChunkerVersion().equals(chunker.path("version").asText())) {
                throw new DocumentProcessingConfigurationException();
            }
            validateChunkerSetting(chunker, "maxTokens", parsingProperties.getMaxTokens());
            validateChunkerSetting(chunker, "maxChunks", parsingProperties.getMaxChunks());
            if (!TOKENIZER.equals(chunker.path("tokenizer").asText())) {
                throw new DocumentProcessingConfigurationException();
            }
        } catch (JsonProcessingException exception) {
            throw new DocumentProcessingConfigurationException(exception);
        }
    }

    private void validateChunkerSetting(JsonNode chunker, String field, int expectedValue) {
        if (!chunker.path(field).canConvertToInt() || chunker.path(field).asInt() != expectedValue) {
            throw new DocumentProcessingConfigurationException();
        }
    }

    private byte[] readAndVerify(DocumentVersion version) {
        long maxBytes = uploadProperties.getMaxFileSize().toBytes();
        if (version.getFileSize() < 0 || version.getFileSize() > maxBytes) {
            throw new DocumentReadFailedException(new IllegalStateException("文件大小与处理限制不一致"));
        }
        try (InputStream inputStream = fileStore.open(version.getStorageKey())) {
            byte[] content = readBounded(inputStream, maxBytes);
            if (content.length != version.getFileSize() || !sha256(content).equals(version.getFileHash())) {
                throw new DocumentReadFailedException(new IllegalStateException("原文件完整性校验失败"));
            }
            return content;
        } catch (StorageReadException | StorageKeyInvalidException | IOException exception) {
            throw new DocumentReadFailedException(exception);
        }
    }

    private byte[] readBounded(InputStream inputStream, long maxBytes) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        long totalBytes = 0;
        int readBytes;
        while ((readBytes = inputStream.read(buffer)) != -1) {
            totalBytes += readBytes;
            if (totalBytes > maxBytes) {
                throw new IOException("原文件超过处理大小限制");
            }
            outputStream.write(buffer, 0, readBytes);
        }
        return outputStream.toByteArray();
    }

    private String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new DocumentReadFailedException(exception);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}
