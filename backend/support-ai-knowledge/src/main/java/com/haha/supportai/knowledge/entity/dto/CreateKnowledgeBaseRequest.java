package com.haha.supportai.knowledge.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(name = "CreateKnowledgeBaseRequest", description = "创建知识库请求")
public class CreateKnowledgeBaseRequest {

    @Schema(description = "知识库名称，去除首尾空白后不能为空", example = "订单支付故障处理",
            maxLength = 120, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 120)
    private String name; // 知识库名称

    @Schema(description = "知识库业务范围，可为空", example = "沉淀支付回调失败、订单状态异常的排查资料")
    private String description; // 知识库业务范围
}
