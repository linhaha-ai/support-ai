package com.haha.supportai.conversation.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.conversation.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRunConfigurationSnapshotFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void snapshotsChatPromptRetrievalAndEmbeddingIndexWithoutCredentials() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.model.chat", "dashscope")
                .withProperty("spring.ai.model.embedding", "ollama")
                .withProperty("spring.ai.dashscope.chat.options.model", "qwen-plus")
                .withProperty("spring.ai.ollama.embedding.options.model", "bge-m3")
                .withProperty("spring.ai.dashscope.api-key", "must-not-be-snapshotted")
                .withProperty("support.ai.dimensions", "1024");
        RagProperties ragProperties = new RagProperties();
        ragProperties.setTopK(8);
        ragProperties.setHistoryMaxRounds(4);
        ragProperties.setHistoryMaxCharacters(7200);
        ragProperties.setQueryRewriteEnabled(true);
        ragProperties.setQueryRewriteMaxCharacters(900);
        ChatRunConfigurationSnapshotFactory factory = new ChatRunConfigurationSnapshotFactory(environment,
                objectMapper, ragProperties);

        JsonNode snapshot = objectMapper.readTree(factory.create());

        assertThat(snapshot.path("mode").asText()).isEqualTo("RAG");
        assertThat(snapshot.path("chat").path("provider").asText()).isEqualTo("dashscope");
        assertThat(snapshot.path("chat").path("model").asText()).isEqualTo("qwen-plus");
        assertThat(snapshot.path("prompt").path("version").asText()).isEqualTo(RagPromptFactory.PROMPT_VERSION);
        assertThat(snapshot.path("prompt").path("historyInjection").asText())
                .isEqualTo("business-prompt-once");
        assertThat(snapshot.path("prompt").path("historyMaxRounds").asInt()).isEqualTo(4);
        assertThat(snapshot.path("prompt").path("historyMaxCharacters").asInt()).isEqualTo(7200);
        assertThat(snapshot.path("retrieval").path("strategy").asText()).isEqualTo("vector-cosine-exact");
        assertThat(snapshot.path("retrieval").path("topK").asInt()).isEqualTo(8);
        assertThat(snapshot.path("retrieval").path("queryRewrite").path("enabled").asBoolean()).isTrue();
        assertThat(snapshot.path("retrieval").path("queryRewrite").path("version").asText())
                .isEqualTo(RetrievalQueryPromptFactory.PROMPT_VERSION);
        assertThat(snapshot.path("retrieval").path("queryRewrite").path("maxCharacters").asInt())
                .isEqualTo(900);
        assertThat(snapshot.path("index").path("store").asText()).isEqualTo("pgvector");
        assertThat(snapshot.path("index").path("embedding").path("provider").asText()).isEqualTo("ollama");
        assertThat(snapshot.path("index").path("embedding").path("model").asText()).isEqualTo("bge-m3");
        assertThat(snapshot.path("index").path("embedding").path("dimensions").asInt()).isEqualTo(1024);
        assertThat(factory.create()).doesNotContain("must-not-be-snapshotted");
    }

    @Test
    void rejectsUnsupportedProviderAndInvalidDimensions() {
        RagProperties ragProperties = new RagProperties();
        MockEnvironment unsupportedProvider = new MockEnvironment()
                .withProperty("spring.ai.model.chat", "unknown")
                .withProperty("spring.ai.model.embedding", "ollama")
                .withProperty("spring.ai.ollama.embedding.options.model", "bge-m3")
                .withProperty("support.ai.dimensions", "1024");
        ChatRunConfigurationSnapshotFactory unsupportedFactory = new ChatRunConfigurationSnapshotFactory(
                unsupportedProvider, objectMapper, ragProperties);

        assertThatThrownBy(unsupportedFactory::create)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("不支持的问答模型提供方");

        MockEnvironment invalidDimensions = new MockEnvironment()
                .withProperty("spring.ai.model.chat", "ollama")
                .withProperty("spring.ai.model.embedding", "ollama")
                .withProperty("spring.ai.ollama.chat.options.model", "qwen2.5:3b")
                .withProperty("spring.ai.ollama.embedding.options.model", "bge-m3")
                .withProperty("support.ai.dimensions", "768");
        ChatRunConfigurationSnapshotFactory dimensionsFactory = new ChatRunConfigurationSnapshotFactory(
                invalidDimensions, objectMapper, ragProperties);

        assertThatThrownBy(dimensionsFactory::create)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("问答运行要求 1024 维向量配置");
    }
}
