package com.haha.supportai.identity.handler;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.haha.supportai.common.entity.vo.ApiError;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.identity.exception.AccountUnavailableException;
import com.haha.supportai.identity.exception.InvalidCredentialsException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
// 认证异常须先于公共兜底处理器解析，避免原本的 401/403 被转换为 500。
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdentityExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException exception,
            HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误", request);
    }

    @ExceptionHandler(NotLoginException.class)
    ResponseEntity<ApiError> handleNotLogin(NotLoginException exception, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录", request);
    }

    @ExceptionHandler(AccountUnavailableException.class)
    ResponseEntity<ApiError> handleAccountUnavailable(AccountUnavailableException exception,
            HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE", "登录状态已失效", request);
    }

    @ExceptionHandler({ NotPermissionException.class, NotRoleException.class })
    ResponseEntity<ApiError> handleAccessDenied(RuntimeException exception, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "请求被拒绝", request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, TraceIdFilter.getTraceId(request)));
    }
}
