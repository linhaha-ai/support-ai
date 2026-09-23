package com.haha.supportai.ai.rerank;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.haha.supportai.ai.config.RerankProperties;
import com.haha.supportai.ai.exception.RerankInputLimitException;
import com.haha.supportai.ai.exception.RerankInvalidResponseException;
import com.haha.supportai.ai.exception.RerankUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashScopeQwenRerankAdapterTest {

    @Test
    void callsConfiguredQwenEndpointAndParsesStableIndices() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            capturedRequest.set(request);
            return Mono.just(jsonResponse(HttpStatus.OK, """
                    {
                      "object": "list",
                      "model": "qwen3-rerank",
                      "results": [
                        {"index": 1, "relevance_score": 0.92},
                        {"index": 0, "relevance_score": 0.73}
                      ],
                      "usage": {"total_tokens": 24}
                    }
                    """));
        };

        var result = adapter(exchange, properties()).rerank("如何退款？", List.of("第一条", "第二条"), 2);

        assertThat(result.getRankings()).extracting(score -> score.getIndex()).containsExactly(1, 0);
        assertThat(result.getRankings()).extracting(score -> score.getScore()).containsExactly(0.92D, 0.73D);
        assertThat(result.getTotalTokens()).isEqualTo(24);
        assertThat(capturedRequest.get().url().toString())
                .isEqualTo("https://workspace.example/compatible-api/v1/reranks");
        assertThat(capturedRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer test-key-not-a-credential");
    }

    @Test
    void rejectsOverLimitInputBeforeNetworkCall() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            calls.incrementAndGet();
            return Mono.empty();
        };
        RerankProperties properties = properties();
        properties.setMaxCandidates(1);

        assertThatThrownBy(() -> adapter(exchange, properties)
                .rerank("问题", List.of("第一条", "第二条"), 1))
                .isInstanceOf(RerankInputLimitException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsMalformedRankingNumbersAndOrdering() {
        ExchangeFunction duplicateOrOutOfOrder = request -> Mono.just(jsonResponse(HttpStatus.OK, """
                {
                  "model": "qwen3-rerank",
                  "results": [
                    {"index": 0, "relevance_score": 0.72},
                    {"index": 0, "relevance_score": 0.91}
                  ]
                }
                """));

        assertThatThrownBy(() -> adapter(duplicateOrOutOfOrder, properties())
                .rerank("问题", List.of("第一条", "第二条"), 2))
                .isInstanceOf(RerankInvalidResponseException.class);

        ExchangeFunction fractionalIndex = request -> Mono.just(jsonResponse(HttpStatus.OK, """
                {
                  "model": "qwen3-rerank",
                  "results": [{"index": 0.5, "relevance_score": 0.72}]
                }
                """));
        assertThatThrownBy(() -> adapter(fractionalIndex, properties())
                .rerank("问题", List.of("第一条", "第二条"), 1))
                .isInstanceOf(RerankInvalidResponseException.class);

        ExchangeFunction fractionalUsage = request -> Mono.just(jsonResponse(HttpStatus.OK, """
                {
                  "model": "qwen3-rerank",
                  "results": [{"index": 0, "relevance_score": 0.72}],
                  "usage": {"total_tokens": 12.5}
                }
                """));
        assertThatThrownBy(() -> adapter(fractionalUsage, properties())
                .rerank("问题", List.of("第一条", "第二条"), 1))
                .isInstanceOf(RerankInvalidResponseException.class);
    }

    @Test
    void rejectsMalformedJsonAsInvalidResponse() {
        ExchangeFunction exchange = request -> Mono.just(jsonResponse(HttpStatus.OK, "{not-json"));

        assertThatThrownBy(() -> adapter(exchange, properties())
                .rerank("问题", List.of("第一条", "第二条"), 1))
                .isInstanceOf(RerankInvalidResponseException.class);
    }

    @Test
    void convertsProviderFailureAndTimeoutToUnavailableException() {
        ExchangeFunction unavailable = request -> Mono.just(jsonResponse(HttpStatus.SERVICE_UNAVAILABLE,
                "{\"code\":\"ServiceUnavailable\"}"));
        assertThatThrownBy(() -> adapter(unavailable, properties())
                .rerank("问题", List.of("第一条", "第二条"), 1))
                .isInstanceOf(RerankUnavailableException.class);

        RerankProperties timeoutProperties = properties();
        timeoutProperties.setTimeout(Duration.ofMillis(20));
        assertThatThrownBy(() -> adapter(request -> Mono.never(), timeoutProperties)
                .rerank("问题", List.of("第一条", "第二条"), 1))
                .isInstanceOf(RerankUnavailableException.class);
    }

    private DashScopeQwenRerankAdapter adapter(ExchangeFunction exchange, RerankProperties properties) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.dashscope.api-key", "test-key-not-a-credential");
        return new DashScopeQwenRerankAdapter(WebClient.builder().exchangeFunction(exchange), environment,
                properties);
    }

    private RerankProperties properties() {
        RerankProperties properties = new RerankProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://workspace.example/compatible-api/v1/reranks");
        return properties;
    }

    private ClientResponse jsonResponse(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
