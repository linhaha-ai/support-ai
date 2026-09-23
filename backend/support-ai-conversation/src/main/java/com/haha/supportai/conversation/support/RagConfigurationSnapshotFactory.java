package com.haha.supportai.conversation.support;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.conversation.config.RagProperties;
import com.haha.supportai.knowledge.config.RetrievalProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class RagConfigurationSnapshotFactory {

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("ollama", "dashscope");

    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    private final RetrievalProperties retrievalProperties;

    public String create() {
        String chatProvider = provider("chat");
        String embeddingProvider = provider("embedding");
        Map<String, Object> retrieval = new LinkedHashMap<>();
        retrieval.put("strategy", retrievalProperties.getStrategy().name());
        retrieval.put("topK", ragProperties.getTopK());
        retrieval.put("candidateMultiplier", retrievalProperties.getCandidateMultiplier());
        retrieval.put("rrfK", retrievalProperties.getRrfK());
        retrieval.put("maxKeywords", retrievalProperties.getMaxKeywords());

        Map<String, Object> chat = modelSnapshot(chatProvider, "chat");
        chat.put("temperature", optionalDouble("spring.ai." + chatProvider + ".chat.options.temperature"));

        Map<String, Object> embedding = modelSnapshot(embeddingProvider, "embedding");
        embedding.put("dimensions", requiredPositiveInteger("support.ai.dimensions"));

        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("version", RagPromptFactory.PROMPT_VERSION);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", "f22-v1");
        snapshot.put("mode", "RAG");
        snapshot.put("retrieval", retrieval);
        snapshot.put("chat", chat);
        snapshot.put("embedding", embedding);
        snapshot.put("prompt", prompt);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成问答配置快照", exception);
        }
    }

    private Map<String, Object> modelSnapshot(String provider, String role) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("provider", provider);
        snapshot.put("model", requiredProperty("spring.ai." + provider + "." + role + ".options.model"));
        return snapshot;
    }

    private String provider(String role) {
        String provider = requiredProperty("spring.ai.model." + role).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new IllegalStateException("不支持的模型提供方: " + role);
        }
        return provider;
    }

    private String requiredProperty(String key) {
        String value = environment.getProperty(key);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("缺少问答配置: " + key);
        }
        return value.trim();
    }

    private Integer requiredPositiveInteger(String key) {
        Integer value = environment.getProperty(key, Integer.class);
        if (value == null || value <= 0) {
            throw new IllegalStateException("问答配置必须为正整数: " + key);
        }
        return value;
    }

    private Double optionalDouble(String key) {
        return environment.getProperty(key, Double.class);
    }
}
