package com.haha.supportai.conversation.support;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.ai.config.RerankProperties;
import com.haha.supportai.conversation.config.RagProperties;
import com.haha.supportai.knowledge.config.RetrievalProperties;
import com.haha.supportai.knowledge.document.entity.dto.ProcessingConfigurationSnapshot;
import com.haha.supportai.knowledge.document.support.DocumentProcessingSnapshotFactory;
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
    private final RerankProperties rerankProperties;
    private final DocumentProcessingSnapshotFactory documentProcessingSnapshotFactory;

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

        Map<String, Object> rerank = new LinkedHashMap<>();
        rerank.put("role", "RERANK");
        rerank.put("enabled", rerankProperties.isEnabled());
        rerank.put("provider", "dashscope");
        rerank.put("model", rerankProperties.getModel());
        rerank.put("candidateMultiplier", rerankProperties.getCandidateMultiplier());
        rerank.put("maxCandidates", rerankProperties.getMaxCandidates());
        rerank.put("maxEstimatedItemTokens", rerankProperties.getMaxEstimatedItemTokens());
        rerank.put("maxEstimatedRequestTokens", rerankProperties.getMaxEstimatedRequestTokens());
        rerank.put("timeoutMs", rerankProperties.getTimeout().toMillis());
        rerank.put("instruction", rerankProperties.getInstruction());

        Map<String, Object> minimumScores = new LinkedHashMap<>();
        minimumScores.put("vector", ragProperties.getMinimumVectorScore());
        minimumScores.put("fusion", ragProperties.getMinimumFusionScore());
        minimumScores.put("rerank", ragProperties.getMinimumRerankScore());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("maxEvidenceTokens", ragProperties.getMaxEvidenceTokens());
        answer.put("minimumScores", minimumScores);

        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("version", RagPromptFactory.PROMPT_VERSION);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", "f24-v1");
        snapshot.put("mode", "RAG");
        snapshot.put("retrieval", retrieval);
        snapshot.put("rerank", rerank);
        snapshot.put("answer", answer);
        snapshot.put("chat", chat);
        snapshot.put("embedding", embedding);
        snapshot.put("index", indexSnapshot());
        snapshot.put("prompt", prompt);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成问答配置快照", exception);
        }
    }

    private Map<String, Object> modelSnapshot(String provider, String role) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("role", role.toUpperCase(Locale.ROOT));
        snapshot.put("provider", provider);
        snapshot.put("model", requiredProperty("spring.ai." + provider + "." + role + ".options.model"));
        return snapshot;
    }

    private Map<String, Object> indexSnapshot() {
        ProcessingConfigurationSnapshot processing = documentProcessingSnapshotFactory.create();
        try {
            JsonNode configuration = objectMapper.readTree(processing.getConfigSnapshot());
            if (!configuration.isObject() || !configuration.path("parser").isObject()
                    || !configuration.path("chunker").isObject()
                    || !configuration.path("embedding").isObject()) {
                throw new IllegalStateException("文档处理配置快照结构无效");
            }
            Map<String, Object> index = new LinkedHashMap<>();
            index.put("configHash", processing.getConfigHash());
            index.put("parser", configuration.path("parser"));
            index.put("chunker", configuration.path("chunker"));
            index.put("embedding", configuration.path("embedding"));
            return index;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取文档处理配置快照", exception);
        }
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
