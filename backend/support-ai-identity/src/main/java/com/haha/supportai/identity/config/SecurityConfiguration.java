package com.haha.supportai.identity.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.filter.SaServletFilter;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.common.entity.vo.ApiError;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.identity.exception.AccountUnavailableException;
import com.haha.supportai.identity.security.ActiveAccountGuard;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class SecurityConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityConfiguration.class);

    private final ActiveAccountGuard activeAccountGuard;
    private final ObjectMapper objectMapper;

    @Bean
    public SaServletFilter authenticationFilter() {
        // 同时保护 MVC、Actuator 和接口文档，鉴权不能只依赖控制器拦截器。
        return new SaServletFilter()
                .addInclude("/**")
                .addExclude("/api/health", "/api/auth/csrf", "/api/auth/login")
                .setAuth(ignored -> {
                    StpUtil.checkLogin();
                    activeAccountGuard.currentUser();
                })
                .setError(this::writeAuthenticationError);
    }

    private String writeAuthenticationError(Throwable exception) {
        HttpStatus status;
        String code;
        String message;
        if (exception instanceof AccountUnavailableException) {
            status = HttpStatus.UNAUTHORIZED;
            code = "ACCOUNT_UNAVAILABLE";
            message = "登录状态已失效";
        } else if (exception instanceof NotLoginException) {
            status = HttpStatus.UNAUTHORIZED;
            code = "UNAUTHORIZED";
            message = "请先登录";
        } else if (exception instanceof NotPermissionException || exception instanceof NotRoleException) {
            status = HttpStatus.FORBIDDEN;
            code = "ACCESS_DENIED";
            message = "请求被拒绝";
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            code = "INTERNAL_ERROR";
            message = "服务暂时不可用";
            LOGGER.error("Sa-Token 鉴权过滤器异常: traceId={}, type={}", TraceIdFilter.currentTraceId(),
                    exception.getClass().getName());
        }

        SaHolder.getResponse()
                .setStatus(status.value())
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
                .setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        try {
            return objectMapper.writeValueAsString(new ApiError(code, message, TraceIdFilter.currentTraceId()));
        } catch (JsonProcessingException serializationException) {
            LOGGER.error("鉴权错误响应序列化失败: traceId={}", TraceIdFilter.currentTraceId());
            SaHolder.getResponse().setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            return "{\"code\":\"INTERNAL_ERROR\",\"message\":\"服务暂时不可用\",\"traceId\":\"unknown\"}";
        }
    }
}
