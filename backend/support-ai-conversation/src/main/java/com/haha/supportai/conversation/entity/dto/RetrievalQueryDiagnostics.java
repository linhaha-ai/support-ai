package com.haha.supportai.conversation.entity.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.haha.supportai.conversation.enums.RetrievalQueryRewriteStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(name = "RetrievalQueryDiagnostics", description = "追问检索问题改写诊断")
public class RetrievalQueryDiagnostics {

    @Schema(description = "用户提交的原始问题；当前知识范围不可读时为空", nullable = true)
    private final String originalQuestion; // 用户本轮原始问题

    @Schema(description = "实际用于授权检索的问题；当前知识范围不可读时为空", nullable = true)
    private final String retrievalQuestion; // 实际用于授权检索的问题

    @Schema(description = "检索问题改写状态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final RetrievalQueryRewriteStatusEnum status; // 改写处理状态

    @JsonCreator
    public RetrievalQueryDiagnostics(@JsonProperty("originalQuestion") String originalQuestion,
            @JsonProperty("retrievalQuestion") String retrievalQuestion,
            @JsonProperty("status") RetrievalQueryRewriteStatusEnum status) {
        this.originalQuestion = originalQuestion;
        this.retrievalQuestion = retrievalQuestion;
        this.status = status;
    }
}
