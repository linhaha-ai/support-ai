package com.haha.supportai.knowledge.config;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
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
@ConfigurationProperties(prefix = "support.ai.document.cleanup")
public class DocumentCleanupProperties {

    private boolean enabled = true; // 是否启用文档清理任务轮询

    @NotNull
    private Duration pollInterval = Duration.ofSeconds(10); // 清理任务轮询间隔

    @NotNull
    private Duration leaseDuration = Duration.ofMinutes(5); // 单次清理租约时长

    @NotNull
    private Duration retryDelay = Duration.ofSeconds(30); // 清理失败后的重试等待时间

    @AssertTrue(message = "文档清理任务的时间配置必须大于零")
    public boolean isDurationConfigurationValid() {
        return isPositive(pollInterval) && isPositive(leaseDuration) && isPositive(retryDelay);
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
