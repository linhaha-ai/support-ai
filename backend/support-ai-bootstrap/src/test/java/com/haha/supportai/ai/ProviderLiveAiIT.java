package com.haha.supportai.ai;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

import com.haha.supportai.ai.config.AiConfiguration;
import com.haha.supportai.storage.PostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** 仅通过 -Plive-ai 显式执行；缺少服务、模型或凭据必须失败。 */
class ProviderLiveAiIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(Long.getLong("live.ai.timeout.seconds", 180));
    private static ConfigurableApplicationContext context;
    private static PostgreSQLContainer<?> database;

    @BeforeAll
    static void startRealDependencies() {
        context = checked("model configuration", () -> new SpringApplicationBuilder(LiveConfiguration.class)
                .web(WebApplicationType.NONE).run());
        database = PostgresFixture.container();
        checked("isolated database startup", () -> {
            database.start();
            PostgresFixture.migrations(database, true).migrate();
            return true;
        });
    }

    @AfterAll
    static void closeDependencies() {
        if (context != null) {
            context.close();
        }
        if (database != null) {
            database.stop();
        }
    }

    @Test
    void returnsNonEmptyChat() {
        String answer = checked("chat", () -> context.getBean(ChatClient.class).prompt()
                .user("请用一句中文回复：服务已就绪。").call().content());
        assertThat(answer).isNotBlank();
    }

    @Test
    void streamsContentAndCompletesNormally() {
        List<String> chunks = checked("streaming chat", () -> context.getBean(ChatClient.class).prompt()
                .user("请用两句中文介绍客服助手的作用。").stream().content().collectList().block(TIMEOUT));
        assertThat(chunks).isNotEmpty();
        assertThat(String.join("", chunks)).isNotBlank();
    }

    @Test
    void returns1024FiniteEmbeddingValues() {
        float[] vector = checked("embedding", () -> context.getBean(EmbeddingModel.class).embed(new Document("这是向量维度验收文本。")));
        assertThat(vector).hasSize(1024);
        for (float value : vector) {
            assertThat(Float.isFinite(value)).isTrue();
        }
    }

    @Test
    void embedsStoresAndRetrievesRealDocuments() {
        checked("vector add and similarity search", () -> {
            String provider = context.getEnvironment().getRequiredProperty("spring.ai.model.embedding");
            var store = PgVectorStore.builder(PostgresFixture.jdbc(database), context.getBean(EmbeddingModel.class))
                    .vectorTableName("acceptance_" + provider + "_vectors")
                    .dimensions(1024).initializeSchema(false).build();
            store.afterPropertiesSet();
            Document returns = new Document("退货规则：收到商品后七天内可以申请退货，商品应保持完好。");
            Document delivery = new Document("配送规则：订单付款后通常两天内发货，可通过订单页面查询快递单号。");
            // add() 内部负责向量化；禁止为这批文档提前再执行一次 embed()。
            store.add(List.of(returns, delivery));
            var matches = store.similaritySearch(SearchRequest.builder().query("商品收到后几天内可以退货？")
                    .topK(1).similarityThreshold(0).build());
            assertThat(matches).hasSize(1);
            assertThat(matches.getFirst().getId()).isEqualTo(returns.getId());
            return true;
        });
    }

    private static <T> T checked(String operation, Callable<T> action) {
        return assertTimeoutPreemptively(TIMEOUT, () -> {
            try {
                return action.call();
            }
            catch (Exception exception) {
                // 供应商响应可能包含请求或鉴权细节，仅保留可定位的异常类别。
                throw new AssertionError("Live AI acceptance failed at " + operation + " ("
                        + exception.getClass().getSimpleName()
                        + "). Check service availability, installed model, credentials and configured timeouts.");
            }
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = { DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class })
    @Import(AiConfiguration.class)
    static class LiveConfiguration {
    }
}
