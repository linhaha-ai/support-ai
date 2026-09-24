package com.haha.supportai.conversation.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

    @Min(1)
    @Max(120000)
    private int maxEvidenceTokens = 4096; // 单次问答允许送入模型的证据正文 Token 总量

    @DecimalMin("-1.0")
    @DecimalMax("1.0")
    private double minimumVectorScore = -1.0D; // 向量相似度低于该值时拒绝使用对应证据

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumFusionScore = 0.0D; // RRF 分数低于该值时拒绝使用对应证据

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumRerankScore = 0.0D; // 重排序分数低于该值时拒绝使用对应证据

    @Min(1)
    @Max(20)
    private int historyMaxRounds = 3; // 单次模型请求最多注入的完整历史问答轮数

    @Min(256)
    @Max(50000)
    private int historyMaxCharacters = 6000; // 历史问题和回答正文的字符预算

    private boolean queryRewriteEnabled = true; // 是否启用追问检索问题改写

    @Min(64)
    @Max(2000)
    private int queryRewriteMaxCharacters = 500; // 改写结果的最大字符数
}
