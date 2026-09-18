package com.haha.supportai.identity.entity.vo;

import com.haha.supportai.identity.service.CsrfTokenService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "CsrfTokenResponse", description = "CSRF 令牌及提交位置")
public class CsrfTokenResponse {

    @Schema(description = "CSRF 令牌值", example = "random-csrf-token",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final String token; // CSRF 令牌值

    @Schema(description = "推荐提交的请求头名称", example = "X-CSRF-TOKEN",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final String headerName; // 请求头名称

    @Schema(description = "兼容的请求参数名称", example = "_csrf", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String parameterName; // 请求参数名称

    public static CsrfTokenResponse from(String token) {
        return new CsrfTokenResponse(token, CsrfTokenService.HEADER_NAME, CsrfTokenService.PARAMETER_NAME);
    }
}
