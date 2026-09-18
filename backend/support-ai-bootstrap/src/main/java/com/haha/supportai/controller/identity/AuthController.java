package com.haha.supportai.controller.identity;

import cn.dev33.satoken.stp.StpUtil;
import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.entity.dto.LoginRequest;
import com.haha.supportai.identity.entity.vo.CsrfTokenResponse;
import com.haha.supportai.identity.entity.vo.CurrentUserResponse;
import com.haha.supportai.identity.security.ActiveAccountGuard;
import com.haha.supportai.identity.service.AuthenticationService;
import com.haha.supportai.identity.service.CsrfTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "身份认证", description = "CSRF 令牌、登录态和当前用户接口")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final ActiveAccountGuard activeAccountGuard;
    private final CsrfTokenService csrfTokenService;

    @Operation(
            operationId = "getCsrfToken",
            summary = "获取 CSRF 令牌",
            description = "匿名获取或复用当前 Servlet Session 的 CSRF 令牌，响应禁止缓存")
    @GetMapping(value = "/csrf", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResult<CsrfTokenResponse>> csrf(
            @Parameter(hidden = true) HttpServletRequest request) {
        String csrfToken = csrfTokenService.getOrCreateToken(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.ok(CsrfTokenResponse.from(csrfToken), TraceIdFilter.getTraceId(request)));
    }

    @Operation(
            operationId = "login",
            summary = "登录",
            description = "校验账号密码，轮换 CSRF Session，并通过 HttpOnly Cookie 签发新的 Sa-Token",
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<CurrentUserResponse> login(
            @Valid @RequestBody LoginRequest loginRequest,
            @Parameter(hidden = true) HttpServletRequest request) {
        AuthenticatedUser user = authenticationService.authenticate(loginRequest.getUsername(),
                loginRequest.getPassword());

        request.getSession(true);
        request.changeSessionId();
        csrfTokenService.rotateToken(request);
        // 重新登录先注销当前浏览器的令牌，保证旧令牌不能继续使用。
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
        StpUtil.login(user.getId().toString(), false);
        return ApiResult.ok(CurrentUserResponse.from(user), TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "logout",
            summary = "退出登录",
            description = "注销当前 Sa-Token，并使当前 CSRF Session 失效",
            security = @SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE),
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> logout(@Parameter(hidden = true) HttpServletRequest request) {
        StpUtil.logout();
        csrfTokenService.clear(request);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(
            operationId = "getCurrentUser",
            summary = "获取当前用户",
            description = "返回当前登录且仍处于启用状态的用户信息",
            security = @SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE))
    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<CurrentUserResponse> me(@Parameter(hidden = true) HttpServletRequest request) {
        AuthenticatedUser user = activeAccountGuard.currentUser();
        return ApiResult.ok(CurrentUserResponse.from(user), TraceIdFilter.getTraceId(request));
    }
}
