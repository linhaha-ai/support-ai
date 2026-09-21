package com.haha.supportai.knowledge.document.parsing.config;

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
@ConfigurationProperties(prefix = "support.ai.document.parsing")
public class DocumentParsingProperties {

    @Min(1)
    @Max(8192)
    private int maxTokens = 512; // 单个知识分块允许的最大估算 token 数

    @Min(1)
    @Max(100000)
    private int maxChunks = 20000; // 单个文档版本允许生成的最大分块数量
}
