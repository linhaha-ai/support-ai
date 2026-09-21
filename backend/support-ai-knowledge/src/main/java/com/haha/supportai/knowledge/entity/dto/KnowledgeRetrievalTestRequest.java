package com.haha.supportai.knowledge.entity.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.haha.supportai.knowledge.exception.KnowledgeRetrievalInvalidRequestException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(name = "KnowledgeRetrievalTestRequest", description = "知识库检索调试请求")
public class KnowledgeRetrievalTestRequest {

    @Schema(description = "用于生成查询向量的检索问题，去除首尾空白后不能为空", example = "退款订单状态为什么没有更新？",
            maxLength = 2000, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 2000)
    private String query; // 检索问题

    @Schema(description = "返回证据数量，范围为 1 至 50", example = "5", minimum = "1", maximum = "50",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(1)
    @Max(50)
    private Integer topK; // 返回证据数量

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        // 检索范围、过滤条件等必须由服务端固定，客户端不能通过扩展字段影响授权范围。
        throw new KnowledgeRetrievalInvalidRequestException();
    }
}
