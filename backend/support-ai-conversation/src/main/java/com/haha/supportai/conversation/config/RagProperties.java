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
