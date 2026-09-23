package com.haha.supportai.conversation.config;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "support.ai.chat.stream")
public class ChatStreamProperties {

    @Min(1)
    @Max(32)
    private int poolSize = 4; // 同时执行的流式问答数量

    @Min(0)
    @Max(1000)
    private int queueCapacity = 32; // 等待执行的流式问答数量

    @NotNull
    private Duration timeout = Duration.ofSeconds(200); // SSE 连接保留时间

    @NotNull
    private Duration executionTimeout = Duration.ofSeconds(180); // 单次问答任务总执行时间

    @AssertTrue(message = "流式连接超时时间必须大于零")
    public boolean isTimeoutValid() {
        return !timeout.isZero() && !timeout.isNegative();
    }

    @AssertTrue(message = "问答任务总执行时间必须大于零")
    public boolean isExecutionTimeoutValid() {
        return !executionTimeout.isZero() && !executionTimeout.isNegative();
    }
}
