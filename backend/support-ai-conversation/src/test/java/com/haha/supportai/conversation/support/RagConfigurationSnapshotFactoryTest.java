package com.haha.supportai.conversation.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.conversation.config.RagProperties;
import com.haha.supportai.knowledge.config.RetrievalProperties;
import com.haha.supportai.knowledge.entity.enums.RetrievalStrategyEnum;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagConfigurationSnapshotFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recordsModelRetrievalAndPromptConfigurationWithoutSecrets() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.model.chat", "ollama")
                .withProperty("spring.ai.model.embedding", "dashscope")
                .withProperty("spring.ai.ollama.chat.options.model", "qwen2.5:3b")
                .withProperty("spring.ai.ollama.chat.options.temperature", "0.0")
                .withProperty("spring.ai.dashscope.embedding.options.model", "text-embedding-v4")
                .withProperty("spring.ai.dashscope.api-key", "不应进入快照")
                .withProperty("support.ai.dimensions", "1024");
        RagProperties properties = new RagProperties();
        properties.setTopK(6);
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        retrievalProperties.setStrategy(RetrievalStrategyEnum.FUSION);
        retrievalProperties.setCandidateMultiplier(4);
        retrievalProperties.setRrfK(50);
        retrievalProperties.setMaxKeywords(7);
        RagConfigurationSnapshotFactory factory = new RagConfigurationSnapshotFactory(environment,
                objectMapper, properties, retrievalProperties);

        JsonNode snapshot = objectMapper.readTree(factory.create());

        assertThat(snapshot.path("schemaVersion").asText()).isEqualTo("f22-v1");
        assertThat(snapshot.path("retrieval").path("strategy").asText()).isEqualTo("FUSION");
        assertThat(snapshot.path("retrieval").path("topK").asInt()).isEqualTo(6);
        assertThat(snapshot.path("retrieval").path("candidateMultiplier").asInt()).isEqualTo(4);
        assertThat(snapshot.path("retrieval").path("rrfK").asInt()).isEqualTo(50);
        assertThat(snapshot.path("retrieval").path("maxKeywords").asInt()).isEqualTo(7);
        assertThat(snapshot.path("chat").path("provider").asText()).isEqualTo("ollama");
        assertThat(snapshot.path("chat").path("model").asText()).isEqualTo("qwen2.5:3b");
        assertThat(snapshot.path("chat").path("temperature").asDouble()).isZero();
        assertThat(snapshot.path("embedding").path("provider").asText()).isEqualTo("dashscope");
        assertThat(snapshot.path("embedding").path("dimensions").asInt()).isEqualTo(1024);
        assertThat(snapshot.path("prompt").path("version").asText()).isEqualTo(RagPromptFactory.PROMPT_VERSION);
        assertThat(factory.create()).doesNotContain("api-key", "不应进入快照");
    }

    @Test
    void rejectsUnsupportedProviderBeforeCreatingSnapshot() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.model.chat", "unknown")
                .withProperty("spring.ai.model.embedding", "ollama")
                .withProperty("support.ai.dimensions", "1024");

        RagConfigurationSnapshotFactory factory = new RagConfigurationSnapshotFactory(environment,
                objectMapper, new RagProperties(), new RetrievalProperties());

        assertThatThrownBy(factory::create)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("不支持的模型提供方: chat");
    }
}
