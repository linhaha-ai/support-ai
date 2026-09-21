package com.haha.supportai.knowledge.document.embedding.config;

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
@ConfigurationProperties(prefix = "support.ai.document.embedding")
public class DocumentEmbeddingProperties {

    @Min(1)
    @Max(100)
    private int batchSize = 10; // 单次提交给向量模型的文档分块数量
}
