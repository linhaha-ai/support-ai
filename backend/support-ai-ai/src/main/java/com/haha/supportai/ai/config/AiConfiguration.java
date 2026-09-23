package com.haha.supportai.ai.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;

import com.haha.supportai.ai.model.ValidatedEmbeddingModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class AiConfiguration {

    @Bean
    static BeanFactoryPostProcessor validateModelConfiguration(Environment environment) {
        // 在模型 Bean 创建前拒绝非法配置，避免自动配置缺失时延迟报错或误用其他提供方。
        return beanFactory -> {
            boolean cloud = false;
            for (String role : new String[] { "chat", "embedding" }) {
                String provider = environment.getProperty("spring.ai.model." + role, "ollama");
                if (!Set.of("ollama", "dashscope").contains(provider)) {
                    throw new IllegalStateException("spring.ai.model." + role + " must be ollama or dashscope");
                }
                cloud |= provider.equals("dashscope");
                String model = environment.getProperty("spring.ai." + provider + "." + role + ".options.model");
                if (!StringUtils.hasText(model)) {
                    throw new IllegalStateException("Configure a model for " + provider + " " + role);
                }
            }
            if (cloud && !environment.getProperty("spring.ai.dashscope.enabled", Boolean.class, false)) {
                throw new IllegalStateException("DashScope requires the dashscope profile (spring.ai.dashscope.enabled=true)");
            }
            if (cloud && !StringUtils.hasText(environment.getProperty("spring.ai.dashscope.api-key"))) {
                throw new IllegalStateException(
                        "SPRING_AI_DASHSCOPE_API_KEY is required when a DashScope model is selected");
            }
            validateRerankConfiguration(environment);
            positiveDuration(environment, "support.ai.connect-timeout", Duration.ofSeconds(10));
            positiveDuration(environment, "support.ai.read-timeout", Duration.ofSeconds(180));
        };
    }

    @Bean
    static BeanPostProcessor validatedEmbeddingModel(Environment environment) {
        // 装饰提供方的原有 Bean，让所有调用方共享校验边界，同时保留唯一的 EmbeddingModel。
        boolean dashscope = "dashscope".equals(environment.getProperty("spring.ai.model.embedding", "ollama"));
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof EmbeddingModel model && !(bean instanceof ValidatedEmbeddingModel)) {
                    return new ValidatedEmbeddingModel(model, dashscope);
                }
                return bean;
            }
        };
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean
    RestClientCustomizer aiRestClientTimeouts(Environment environment) {
        return builder -> {
            var factory = new JdkClientHttpRequestFactory(httpClient(environment));
            factory.setReadTimeout(positiveDuration(environment, "support.ai.read-timeout", Duration.ofSeconds(180)));
            builder.requestFactory(factory);
        };
    }

    @Bean
    WebClientCustomizer aiWebClientTimeouts(Environment environment) {
        return builder -> {
            var connector = new JdkClientHttpConnector(httpClient(environment));
            connector.setReadTimeout(positiveDuration(environment, "support.ai.read-timeout", Duration.ofSeconds(180)));
            builder.clientConnector(connector);
        };
    }

    private static HttpClient httpClient(Environment environment) {
        return HttpClient.newBuilder()
                .connectTimeout(positiveDuration(environment, "support.ai.connect-timeout", Duration.ofSeconds(10)))
                .build();
    }

    private static void validateRerankConfiguration(Environment environment) {
        if (!environment.getProperty("support.ai.rerank.enabled", Boolean.class, false)) {
            return;
        }
        String model = environment.getProperty("support.ai.rerank.model");
        if (!RerankProperties.SUPPORTED_MODEL.equals(model)) {
            throw new IllegalStateException("support.ai.rerank.model must be qwen3-rerank");
        }
        String endpoint = environment.getProperty("support.ai.rerank.endpoint");
        if (!isSecureEndpoint(endpoint)) {
            throw new IllegalStateException("SUPPORT_AI_RERANK_ENDPOINT must be a valid qwen3-rerank HTTPS URL");
        }
        if (!StringUtils.hasText(environment.getProperty("spring.ai.dashscope.api-key"))) {
            throw new IllegalStateException("SPRING_AI_DASHSCOPE_API_KEY is required when rerank is enabled");
        }
        positiveDuration(environment, "support.ai.rerank.timeout", Duration.ofSeconds(5));
    }

    private static boolean isSecureEndpoint(String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return false;
        }
        try {
            URI uri = URI.create(endpoint.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && StringUtils.hasText(uri.getHost())
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && "/compatible-api/v1/reranks".equals(uri.getPath());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static Duration positiveDuration(Environment environment, String key, Duration defaultValue) {
        Duration duration = DurationStyle.detectAndParse(environment.getProperty(key, defaultValue.toString()));
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException(key + " must be positive");
        }
        return duration;
    }
}
