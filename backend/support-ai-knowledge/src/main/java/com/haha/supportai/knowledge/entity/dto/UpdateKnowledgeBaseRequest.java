package com.haha.supportai.knowledge.entity.dto;

import com.haha.supportai.data.knowledge.enums.KnowledgeBaseStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(name = "UpdateKnowledgeBaseRequest", description = "更新知识库元数据请求")
public class UpdateKnowledgeBaseRequest {

    @Schema(description = "知识库名称；提供时去除首尾空白后不能为空", example = "订单支付故障处理", maxLength = 120)
    @Size(max = 120)
    private String name; // 知识库名称

    @Schema(description = "知识库业务范围；空字符串表示清空描述", example = "沉淀支付回调失败、订单状态异常的排查资料")
    private String description; // 知识库业务范围

    @Schema(description = "知识库状态", example = "DISABLED")
    private KnowledgeBaseStatusEnum status; // 知识库状态

    @Schema(description = "待更新元数据的当前版本号，用于乐观并发控制", example = "0",
            minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(0)
    private Long revision; // 当前版本号
}
