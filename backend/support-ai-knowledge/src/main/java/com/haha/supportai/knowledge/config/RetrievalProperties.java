package com.haha.supportai.knowledge.config;

import com.haha.supportai.knowledge.entity.enums.RetrievalStrategyEnum;
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
@ConfigurationProperties(prefix = "support.ai.retrieval")
public class RetrievalProperties {

    @NotNull
    private RetrievalStrategyEnum strategy = RetrievalStrategyEnum.VECTOR; // 问答默认检索策略

    @Min(1)
    @Max(5)
    private int candidateMultiplier = 3; // 融合前每路候选数相对 TopK 的倍数

    @Min(1)
    @Max(1000)
    private int rrfK = 60; // RRF 排名平滑常数

    @Min(1)
    @Max(20)
    private int maxKeywords = 8; // 单次查询最多使用的明确关键词数量
}
