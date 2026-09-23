package com.haha.supportai.conversation.entity.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(name = "AgentMetrics", description = "受控助手的模型、工具与上下文预算指标")
public class AgentMetrics extends RagMetrics {

    @Schema(description = "实际模型调用次数", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int modelCallCount; // 实际模型调用次数

    @Schema(description = "实际工具调用次数", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int toolCallCount; // 实际工具调用次数

    @Schema(description = "单次模型调用观察到的最大上下文 Token 数", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int maxObservedContextTokens; // 单次调用观察到的最大上下文 Token 数

    @Schema(description = "本轮工具调用上限", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int toolCallLimit; // 本轮工具调用上限

    @Schema(description = "本轮模型调用上限", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int modelCallLimit; // 本轮模型调用上限

    @JsonCreator
    public AgentMetrics(@JsonProperty("evidenceCount") int evidenceCount,
            @JsonProperty("citedCount") int citedCount,
            @JsonProperty("noEvidence") boolean noEvidence,
            @JsonProperty("totalMs") long totalMs,
            @JsonProperty("firstDeltaMs") Long firstDeltaMs,
            @JsonProperty("modelCallCount") int modelCallCount,
            @JsonProperty("toolCallCount") int toolCallCount,
            @JsonProperty("maxObservedContextTokens") int maxObservedContextTokens,
            @JsonProperty("toolCallLimit") int toolCallLimit,
            @JsonProperty("modelCallLimit") int modelCallLimit) {
        super(evidenceCount, citedCount, noEvidence, totalMs, firstDeltaMs, null);
        this.modelCallCount = modelCallCount;
        this.toolCallCount = toolCallCount;
        this.maxObservedContextTokens = maxObservedContextTokens;
        this.toolCallLimit = toolCallLimit;
        this.modelCallLimit = modelCallLimit;
    }

    @Override
    public AgentMetrics withoutQueryRewriteText() {
        return this;
    }
}
