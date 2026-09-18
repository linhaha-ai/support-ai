package com.haha.supportai.common.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ApiError", description = "错误响应")
public class ApiError {

    @Schema(description = "稳定错误码", example = "INVALID_REQUEST", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String code; // 错误码

    @Schema(description = "可安全展示的错误信息", example = "请求参数不合法",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final String message; // 脱敏后的错误信息

    @Schema(description = "请求追踪标识", example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final String traceId; // 请求追踪标识
}
