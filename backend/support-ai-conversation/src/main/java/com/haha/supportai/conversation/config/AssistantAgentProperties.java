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
@ConfigurationProperties(prefix = "support.ai.assistant.agent")
public class AssistantAgentProperties {

    @Min(1)
    @Max(8)
    private int maxToolCalls = 2; // 单次助手运行允许的工具调用总数

    @Min(1)
    @Max(12)
    private int maxModelCalls = 3; // 单次助手运行允许的模型调用总数

    @Min(512)
    @Max(50000)
    private int maxContextTokens = 8000; // 单次模型调用允许的最大上下文 Token 数

    @Min(0)
    @Max(10)
    private int historyMaxRounds = 3; // 最多注入的完整成功历史轮次，零表示关闭历史

    @Min(0)
    @Max(50000)
    private int historyMaxCharacters = 6000; // 历史问答及来源说明的字符预算

    @Min(4)
    @Max(100)
    private int recursionLimit = 16; // ReactAgent 图执行的最大递归步数

    @NotNull
    private Duration toolTimeout = Duration.ofSeconds(20); // 单次工具执行超时时间

    @NotNull
    private Duration executionTimeout = Duration.ofSeconds(60); // 单次助手运行总执行时间

    @AssertTrue(message = "助手工具超时时间必须大于零")
    public boolean isToolTimeoutValid() {
        return !toolTimeout.isZero() && !toolTimeout.isNegative();
    }

    @AssertTrue(message = "助手总执行时间必须大于零")
    public boolean isExecutionTimeoutValid() {
        return !executionTimeout.isZero() && !executionTimeout.isNegative();
    }
}
