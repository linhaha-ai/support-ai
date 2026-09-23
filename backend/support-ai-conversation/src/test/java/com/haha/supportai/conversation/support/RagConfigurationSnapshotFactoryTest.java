package com.haha.supportai.conversation.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.ai.config.RerankProperties;
import com.haha.supportai.conversation.config.RagProperties;
import com.haha.supportai.knowledge.config.RetrievalProperties;
import com.haha.supportai.knowledge.document.config.DocumentUploadProperties;
import com.haha.supportai.knowledge.document.parsing.config.DocumentParsingProperties;
import com.haha.supportai.knowledge.document.support.DocumentProcessingSnapshotFactory;
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
        properties.setMaxEvidenceTokens(3072);
        properties.setMinimumVectorScore(0.25D);
        properties.setMinimumFusionScore(0.01D);
        properties.setMinimumRerankScore(0.4D);
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        retrievalProperties.setStrategy(RetrievalStrategyEnum.FUSION);
        retrievalProperties.setCandidateMultiplier(4);
        retrievalProperties.setRrfK(50);
        retrievalProperties.setMaxKeywords(7);
        RerankProperties rerankProperties = new RerankProperties();
        rerankProperties.setEnabled(true);
        rerankProperties.setEndpoint("https://workspace.example/compatible-api/v1/reranks");
        rerankProperties.setCandidateMultiplier(5);
        rerankProperties.setMaxCandidates(40);
        RagConfigurationSnapshotFactory factory = new RagConfigurationSnapshotFactory(environment,
                objectMapper, properties, retrievalProperties, rerankProperties,
                processingSnapshotFactory(environment));

        JsonNode snapshot = objectMapper.readTree(factory.create());

        assertThat(snapshot.path("schemaVersion").asText()).isEqualTo("f24-v1");
        assertThat(snapshot.path("retrieval").path("strategy").asText()).isEqualTo("FUSION");
        assertThat(snapshot.path("retrieval").path("topK").asInt()).isEqualTo(6);
        assertThat(snapshot.path("retrieval").path("candidateMultiplier").asInt()).isEqualTo(4);
        assertThat(snapshot.path("retrieval").path("rrfK").asInt()).isEqualTo(50);
        assertThat(snapshot.path("retrieval").path("maxKeywords").asInt()).isEqualTo(7);
        assertThat(snapshot.path("rerank").path("enabled").asBoolean()).isTrue();
        assertThat(snapshot.path("rerank").path("role").asText()).isEqualTo("RERANK");
        assertThat(snapshot.path("rerank").path("model").asText()).isEqualTo("qwen3-rerank");
        assertThat(snapshot.path("rerank").path("candidateMultiplier").asInt()).isEqualTo(5);
        assertThat(snapshot.path("rerank").path("maxCandidates").asInt()).isEqualTo(40);
        assertThat(snapshot.path("rerank").path("timeoutMs").asLong()).isEqualTo(5000L);
        assertThat(snapshot.path("chat").path("provider").asText()).isEqualTo("ollama");
        assertThat(snapshot.path("chat").path("role").asText()).isEqualTo("CHAT");
        assertThat(snapshot.path("chat").path("model").asText()).isEqualTo("qwen2.5:3b");
        assertThat(snapshot.path("chat").path("temperature").asDouble()).isZero();
        assertThat(snapshot.path("embedding").path("provider").asText()).isEqualTo("dashscope");
        assertThat(snapshot.path("embedding").path("role").asText()).isEqualTo("EMBEDDING");
        assertThat(snapshot.path("embedding").path("dimensions").asInt()).isEqualTo(1024);
        assertThat(snapshot.path("answer").path("maxEvidenceTokens").asInt()).isEqualTo(3072);
        assertThat(snapshot.path("answer").path("minimumScores").path("vector").asDouble()).isEqualTo(0.25D);
        assertThat(snapshot.path("answer").path("minimumScores").path("fusion").asDouble()).isEqualTo(0.01D);
        assertThat(snapshot.path("answer").path("minimumScores").path("rerank").asDouble()).isEqualTo(0.4D);
        assertThat(snapshot.path("index").path("configHash").asText()).matches("[0-9a-f]{64}");
        assertThat(snapshot.path("index").path("chunker").path("maxTokens").asInt()).isEqualTo(512);
        assertThat(snapshot.path("index").path("embedding").path("model").asText())
                .isEqualTo("text-embedding-v4");
        assertThat(snapshot.path("prompt").path("version").asText()).isEqualTo(RagPromptFactory.PROMPT_VERSION);
        assertThat(factory.create()).doesNotContain("api-key", "不应进入快照", "workspace.example");
    }

    @Test
    void rejectsUnsupportedProviderBeforeCreatingSnapshot() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.model.chat", "unknown")
                .withProperty("spring.ai.model.embedding", "ollama")
                .withProperty("support.ai.dimensions", "1024");

        RagConfigurationSnapshotFactory factory = new RagConfigurationSnapshotFactory(environment,
                objectMapper, new RagProperties(), new RetrievalProperties(), new RerankProperties(),
                processingSnapshotFactory(environment));

        assertThatThrownBy(factory::create)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("不支持的模型提供方: chat");
    }

    private DocumentProcessingSnapshotFactory processingSnapshotFactory(MockEnvironment environment) {
        return new DocumentProcessingSnapshotFactory(environment, objectMapper,
                new DocumentUploadProperties(), new DocumentParsingProperties());
    }
}
