package com.haha.supportai.conversation.support;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.conversation.config.AssistantAgentProperties;
import com.haha.supportai.conversation.config.RagProperties;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ChatRunConfigurationSnapshotFactory {

    private static final int REQUIRED_DIMENSIONS = 1024;
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("ollama", "dashscope");

    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    private final AssistantAgentProperties assistantAgentProperties;
    private final RagConfigurationSnapshotFactory ragConfigurationSnapshotFactory;

    @Autowired
    public ChatRunConfigurationSnapshotFactory(Environment environment, ObjectMapper objectMapper,
            RagProperties ragProperties, AssistantAgentProperties assistantAgentProperties,
            RagConfigurationSnapshotFactory ragConfigurationSnapshotFactory) {
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
        this.assistantAgentProperties = assistantAgentProperties;
        this.ragConfigurationSnapshotFactory = ragConfigurationSnapshotFactory;
    }

    ChatRunConfigurationSnapshotFactory(Environment environment, ObjectMapper objectMapper,
            RagProperties ragProperties) {
        this(environment, objectMapper, ragProperties, new AssistantAgentProperties(), null);
    }

    /**
     * 固化本轮回答使用的非敏感配置，后续切换模型或检索参数时仍可追溯。
     */
    public String create() {
        return create(ChatSessionModeEnum.RAG);
    }

    /**
     * 按会话模式固化本轮回答配置，快照不包含任何密钥或用户输入。
     */
    public String create(ChatSessionModeEnum mode) {
        if (mode == null) {
            throw new IllegalStateException("问答运行缺少会话模式");
        }
        if (mode == ChatSessionModeEnum.RAG && ragConfigurationSnapshotFactory != null) {
            return ragConfigurationSnapshotFactory.create();
        }
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
        retrieval.put("queryRewrite", queryRewriteSnapshot());
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("store", "pgvector");
        index.put("metric", "cosine");
        index.put("embedding", embedding);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mode", mode.name());
        snapshot.put("chat", chat);
        if (mode == ChatSessionModeEnum.RAG) {
            snapshot.put("prompt", prompt);
            snapshot.put("retrieval", retrieval);
        } else {
            snapshot.put("agent", assistantAgentSnapshot());
        }
        snapshot.put("index", index);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成问答运行配置快照", exception);
        }
    }

    private Map<String, Object> assistantAgentSnapshot() {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("framework", "spring-ai-alibaba-react-agent");
        agent.put("promptVersion", "assistant-agent-v2");
        agent.put("historyInjection", "authorized-history-once");
        agent.put("historyMaxRounds", assistantAgentProperties.getHistoryMaxRounds());
        agent.put("historyMaxCharacters", assistantAgentProperties.getHistoryMaxCharacters());
        agent.put("ticketFreshness", "current-run-tool-result");
        agent.put("maxToolCalls", assistantAgentProperties.getMaxToolCalls());
        agent.put("maxModelCalls", assistantAgentProperties.getMaxModelCalls());
        agent.put("maxContextTokens", assistantAgentProperties.getMaxContextTokens());
        agent.put("recursionLimit", assistantAgentProperties.getRecursionLimit());
        agent.put("toolTimeoutMs", assistantAgentProperties.getToolTimeout().toMillis());
        agent.put("executionTimeoutMs", assistantAgentProperties.getExecutionTimeout().toMillis());
        agent.put("tools", new String[] { "searchKnowledge", "getTicketStatus" });
        return agent;
    }

    private Map<String, Object> queryRewriteSnapshot() {
        Map<String, Object> queryRewrite = new LinkedHashMap<>();
        queryRewrite.put("enabled", ragProperties.isQueryRewriteEnabled());
        queryRewrite.put("version", RetrievalQueryPromptFactory.PROMPT_VERSION);
        queryRewrite.put("maxCharacters", ragProperties.getQueryRewriteMaxCharacters());
        return queryRewrite;
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
