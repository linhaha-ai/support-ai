package com.haha.supportai.assistant.ticket.handler;

import com.haha.supportai.assistant.ticket.exception.TicketInvalidRequestException;
import com.haha.supportai.assistant.ticket.exception.TicketNotFoundException;
import com.haha.supportai.common.entity.vo.ApiError;
import com.haha.supportai.common.filter.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
// 工单不存在和越权统一返回同一响应，避免通过接口探测业务数据。
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
public class TicketExceptionHandler {

    @ExceptionHandler(TicketInvalidRequestException.class)
    ResponseEntity<ApiError> handleInvalidRequest(TicketInvalidRequestException exception,
            HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数不合法", request);
    }

    @ExceptionHandler(TicketNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(TicketNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在或不可访问", request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, TraceIdFilter.getTraceId(request)));
    }
}
