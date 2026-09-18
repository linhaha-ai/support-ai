package com.haha.supportai.ai.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.haha.supportai.ai.model.ValidatedEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(IsolatedAiTestConfiguration.class)
            .withPropertyValues(
                    "spring.profiles.active=ollama",
                    "spring.ai.model.chat=ollama",
                    "spring.ai.model.embedding=ollama",
                    "spring.ai.dashscope.enabled=false",
                    "spring.ai.dashscope.api-key=",
                    "spring.ai.ollama.base-url=http://127.0.0.1:1",
                    "spring.ai.dashscope.base-url=http://127.0.0.1:1");

    @ParameterizedTest
    @CsvSource({ "ollama,ollama", "ollama,dashscope", "dashscope,ollama", "dashscope,dashscope" })
    void selectsExactlyOneModelForEachRoleWithoutCallingIt(String chat, String embedding) {
        boolean cloud = chat.equals("dashscope") || embedding.equals("dashscope");
        runner.withPropertyValues(
                "spring.ai.model.chat=" + chat,
                "spring.ai.model.embedding=" + embedding,
                "spring.ai.dashscope.enabled=" + cloud,
                "spring.ai.dashscope.api-key=" + (cloud ? "test-key-not-a-credential" : ""))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ChatModel.class)
                            .hasSingleBean(EmbeddingModel.class).hasSingleBean(ChatClient.class);
                    assertThat(context.getBean(ChatModel.class)).isInstanceOf(
                            chat.equals("ollama") ? OllamaChatModel.class : DashScopeChatModel.class);
                    assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(ValidatedEmbeddingModel.class);
                    assertThat(context.getBean(EmbeddingModel.class).dimensions()).isEqualTo(1024);
                });
    }

    @Test
    void defaultLocalConfigurationDoesNotNeedCloudCredentials() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class));
    }

    @Test
    void rejectsUnknownProvider() {
        runner.withPropertyValues("spring.ai.model.chat=unknown")
                .run(context -> assertThat(context.getStartupFailure()).hasMessageContaining("must be ollama or dashscope"));
    }

    @Test
    void rejectsMissingCloudKey() {
        runner.withPropertyValues("spring.ai.model.chat=dashscope", "spring.ai.dashscope.enabled=true")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasMessageContaining("SPRING_AI_DASHSCOPE_API_KEY"));
    }

    @Test
    void rejectsCloudSelectionWithDisabledConfiguration() {
        runner.withPropertyValues("spring.ai.model.embedding=dashscope")
                .run(context -> assertThat(context.getStartupFailure()).hasMessageContaining("dashscope profile"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    @Import(AiConfiguration.class)
    static class IsolatedAiTestConfiguration {
    }
}
