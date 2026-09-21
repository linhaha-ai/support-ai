package com.haha.supportai.knowledge.ingestion.config;

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
@ConfigurationProperties(prefix = "support.ai.ingestion.worker")
public class IngestionWorkerProperties {

    private boolean enabled = true; // 默认执行已接入的解析与分块阶段，未接入阶段不会被领取

    @Min(1)
    @Max(16)
    private int poolSize = 2; // 入库任务执行线程数

    @Min(0)
    @Max(1000)
    private int queueCapacity = 16; // 执行器等待队列容量

    @NotNull
    private Duration pollInterval = Duration.ofSeconds(5); // 数据库轮询间隔

    @NotNull
    private Duration leaseDuration = Duration.ofSeconds(60); // 单次任务租约时长

    @NotNull
    private Duration heartbeatInterval = Duration.ofSeconds(15); // 任务执行期间的续租心跳间隔

    @NotNull
    private Duration retryDelay = Duration.ofSeconds(10); // 自动重试等待时间

    @AssertTrue(message = "入库 Worker 的时间配置必须大于零")
    public boolean isDurationConfigurationValid() {
        return !pollInterval.isZero() && !pollInterval.isNegative()
                && !leaseDuration.isZero() && !leaseDuration.isNegative()
                && !heartbeatInterval.isZero() && !heartbeatInterval.isNegative()
                && heartbeatInterval.compareTo(leaseDuration) < 0
                && !retryDelay.isZero() && !retryDelay.isNegative();
    }
}
