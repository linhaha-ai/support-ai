package com.haha.supportai.conversation.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatModelUsage", description = "模型提供方返回的令牌用量")
public class ChatModelUsage {

    @Schema(description = "输入令牌数", nullable = true)
    private final Integer promptTokens; // 模型报告的输入令牌数

    @Schema(description = "输出令牌数", nullable = true)
    private final Integer completionTokens; // 模型报告的输出令牌数

    @Schema(description = "总令牌数", nullable = true)
    private final Integer totalTokens; // 模型报告的总令牌数
}
