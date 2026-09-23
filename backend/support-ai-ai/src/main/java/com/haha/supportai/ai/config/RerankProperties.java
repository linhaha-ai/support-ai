package com.haha.supportai.ai.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "support.ai.rerank")
public class RerankProperties {

    public static final String SUPPORTED_MODEL = "qwen3-rerank";

    private boolean enabled; // 是否在授权召回后启用重排序

    @NotBlank
    private String model = SUPPORTED_MODEL; // 重排序模型名称

    private String endpoint = ""; // qwen3-rerank 完整请求地址，不包含密钥

    @Min(1)
    @Max(10)
    private int candidateMultiplier = 3; // 重排序候选数相对最终 TopK 的倍数

    @Min(1)
    @Max(500)
    private int maxCandidates = 50; // 单次模型调用允许的最大候选数量

    @Min(1)
    @Max(4000)
    private int maxEstimatedItemTokens = 3500; // 单条输入的保守估算 Token 上限

    @Min(1)
    @Max(120000)
    private int maxEstimatedRequestTokens = 100000; // 单次请求的保守估算 Token 上限

    @NotNull
    private Duration timeout = Duration.ofSeconds(5); // 单次重排序调用超时时间

    @NotBlank
    private String instruction = "Given a web search query, retrieve relevant passages that answer the query."; // 排序任务说明

    @AssertTrue(message = "启用重排序时必须配置有效的 qwen3-rerank HTTPS 地址")
    public boolean isEnabledEndpointValid() {
        if (!enabled) {
            return true;
        }
        if (!SUPPORTED_MODEL.equals(model) || !StringUtils.hasText(endpoint)) {
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

    @AssertTrue(message = "重排序超时时间必须为正数")
    public boolean isTimeoutValid() {
        return timeout != null && !timeout.isZero() && !timeout.isNegative();
    }
}
