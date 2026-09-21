package com.haha.supportai.conversation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "support.ai.rag")
public class RagProperties {

    @Min(1)
    @Max(50)
    private int topK = 6; // 单次问答最多送入模型的授权证据数量
}
