package com.haha.supportai.knowledge.handler;

import com.haha.supportai.common.entity.vo.ApiError;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.knowledge.exception.KnowledgeBaseAccessDeniedException;
import com.haha.supportai.knowledge.exception.KnowledgeBaseConflictException;
import com.haha.supportai.knowledge.exception.KnowledgeBaseInvalidRequestException;
import com.haha.supportai.knowledge.exception.KnowledgeBaseNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
// 知识库业务异常须先于公共兜底处理器解析，维持稳定的业务状态码。
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class KnowledgeBaseExceptionHandler {

    @ExceptionHandler(KnowledgeBaseInvalidRequestException.class)
    ResponseEntity<ApiError> handleInvalidRequest(KnowledgeBaseInvalidRequestException exception,
            HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数不合法", request);
    }

    @ExceptionHandler(KnowledgeBaseNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(KnowledgeBaseNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在", request);
    }

    @ExceptionHandler(KnowledgeBaseConflictException.class)
    ResponseEntity<ApiError> handleConflict(KnowledgeBaseConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "KNOWLEDGE_BASE_CONFLICT", "知识库已被其他操作更新", request);
    }

    @ExceptionHandler(KnowledgeBaseAccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(KnowledgeBaseAccessDeniedException exception, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "请求被拒绝", request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, TraceIdFilter.getTraceId(request)));
    }
}
