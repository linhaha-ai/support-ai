package com.haha.supportai.knowledge.document.parsing.service;

import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.knowledge.document.parsing.config.DocumentParsingProperties;
import com.haha.supportai.knowledge.document.parsing.entity.dto.ParsedDocument;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentProcessingConfigurationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DocumentSourceParsingService {

    private static final String SUPPORTED_PARSER_VERSION = "plain-text-v1";
    private static final String SUPPORTED_CHUNKER_VERSION = "token-budget-v1";
    private static final String TOKENIZER = "CL100K_BASE";
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentSourceReader documentSourceReader;
    private final DocumentTextParser documentTextParser;
    private final DocumentParsingProperties parsingProperties;
    private final ObjectMapper objectMapper;

    public ParsedDocument parse(UUID versionId) {
        DocumentVersion version = documentVersionRepository.findById(versionId)
                .orElseThrow(DocumentProcessingConfigurationException::new);
        validateConfiguration(version);
        String source = documentSourceReader.readVerifiedUtf8(version);
        return documentTextParser.parse(source, version.getMediaType());
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

}
