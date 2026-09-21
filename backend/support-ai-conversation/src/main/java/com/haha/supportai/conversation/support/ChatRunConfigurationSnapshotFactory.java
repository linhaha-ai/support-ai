package com.haha.supportai.conversation.support;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.conversation.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class ChatRunConfigurationSnapshotFactory {

    private static final int REQUIRED_DIMENSIONS = 1024;
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("ollama", "dashscope");

    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    /**
     * 固化本轮回答使用的非敏感配置，后续切换模型或检索参数时仍可追溯。
     */
    public String create() {
        String chatProvider = provider("chat");
        String embeddingProvider = provider("embedding");
        String chatModel = model(chatProvider, "chat");
        String embeddingModel = model(embeddingProvider, "embedding");
        Integer dimensions = environment.getProperty("support.ai.dimensions", Integer.class);
        if (dimensions == null || dimensions != REQUIRED_DIMENSIONS) {
            throw new IllegalStateException("问答运行要求 1024 维向量配置");
        }

        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("provider", chatProvider);
        chat.put("model", chatModel);
        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("provider", embeddingProvider);
        embedding.put("model", embeddingModel);
        embedding.put("dimensions", dimensions);
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("version", RagPromptFactory.PROMPT_VERSION);
        prompt.put("historyInjection", "business-prompt-once");
        prompt.put("historyMaxRounds", ragProperties.getHistoryMaxRounds());
        prompt.put("historyMaxCharacters", ragProperties.getHistoryMaxCharacters());
        Map<String, Object> retrieval = new LinkedHashMap<>();
        retrieval.put("strategy", "vector-cosine-exact");
        retrieval.put("topK", ragProperties.getTopK());
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("store", "pgvector");
        index.put("metric", "cosine");
        index.put("embedding", embedding);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mode", "RAG");
        snapshot.put("chat", chat);
        snapshot.put("prompt", prompt);
        snapshot.put("retrieval", retrieval);
        snapshot.put("index", index);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成问答运行配置快照", exception);
        }
    }

    private String provider(String role) {
        String provider = environment.getProperty("spring.ai.model." + role, "ollama")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new IllegalStateException("不支持的问答模型提供方");
        }
        return provider;
    }

    private String model(String provider, String role) {
        String key = "spring.ai." + provider + "." + role + ".options.model";
        String model = environment.getProperty(key);
        if (!StringUtils.hasText(model)) {
            throw new IllegalStateException("缺少问答运行模型配置: " + key);
        }
        return model.trim();
    }
}
