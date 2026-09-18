package com.haha.supportai.common.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(description = "成功响应")
public class ApiResult<T> {

    @Schema(description = "业务结果码", example = "OK", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String code; // 业务结果码

    @Schema(description = "业务数据，结构由具体接口决定", requiredMode = Schema.RequiredMode.REQUIRED)
    private final T data; // 响应数据

    @Schema(description = "请求追踪标识", example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final String traceId; // 请求追踪标识

    public static <T> ApiResult<T> ok(T data, String traceId) {
        return new ApiResult<>("OK", data, traceId);
    }
}
