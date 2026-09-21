package com.haha.supportai.knowledge.document.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.knowledge.document.config.DocumentUploadProperties;
import com.haha.supportai.knowledge.document.entity.dto.ProcessingConfigurationSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class DocumentProcessingSnapshotFactory {

    private static final int REQUIRED_DIMENSIONS = 1024;

    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final DocumentUploadProperties documentUploadProperties;

    public ProcessingConfigurationSnapshot create() {
        String provider = requiredProperty("spring.ai.model.embedding").toLowerCase(Locale.ROOT);
        String model = switch (provider) {
            case "ollama" -> requiredProperty("spring.ai.ollama.embedding.options.model");
            case "dashscope" -> requiredProperty("spring.ai.dashscope.embedding.options.model");
            default -> throw new IllegalStateException("不支持的向量模型提供方");
        };
        Integer dimensions = environment.getProperty("support.ai.dimensions", Integer.class);
        if (dimensions == null || dimensions != REQUIRED_DIMENSIONS) {
            throw new IllegalStateException("文档处理要求 1024 维向量配置");
        }

        Map<String, Object> parser = new LinkedHashMap<>();
        parser.put("version", documentUploadProperties.getParserVersion());
        Map<String, Object> chunker = new LinkedHashMap<>();
        chunker.put("version", documentUploadProperties.getChunkerVersion());
        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("provider", provider);
        embedding.put("model", model);
        embedding.put("dimensions", dimensions);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("parser", parser);
        snapshot.put("chunker", chunker);
        snapshot.put("embedding", embedding);
        String snapshotJson = writeSnapshot(snapshot);
        return new ProcessingConfigurationSnapshot(model, dimensions, sha256(snapshotJson), snapshotJson);
    }

    private String requiredProperty(String key) {
        String value = environment.getProperty(key);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("缺少文档处理配置: " + key);
        }
        return value.trim();
    }

    private String writeSnapshot(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成文档处理配置快照", exception);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }
}
