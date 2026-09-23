package com.haha.supportai.conversation.handler;

import com.haha.supportai.common.entity.vo.ApiError;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.conversation.exception.ChatIdempotencyConflictException;
import com.haha.supportai.conversation.exception.ChatRunInProgressException;
import com.haha.supportai.conversation.exception.ChatRunNotFoundException;
import com.haha.supportai.conversation.exception.ChatSessionActiveRunException;
import com.haha.supportai.conversation.exception.ChatSessionInvalidRequestException;
import com.haha.supportai.conversation.exception.ChatSessionKnowledgeBaseUnavailableException;
import com.haha.supportai.conversation.exception.ChatSessionNotFoundException;
import com.haha.supportai.conversation.exception.ChatSessionScopeRevokedException;
import com.haha.supportai.conversation.exception.ChatStreamCapacityException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ConversationExceptionHandler {

    @ExceptionHandler(ChatSessionInvalidRequestException.class)
    ResponseEntity<ApiError> handleInvalidRequest(ChatSessionInvalidRequestException exception,
            HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数不合法", request);
    }

    @ExceptionHandler(ChatSessionNotFoundException.class)
    ResponseEntity<ApiError> handleSessionNotFound(ChatSessionNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "CHAT_SESSION_NOT_FOUND", "会话不存在", request);
    }

    @ExceptionHandler(ChatSessionKnowledgeBaseUnavailableException.class)
    ResponseEntity<ApiError> handleKnowledgeBaseNotFound(ChatSessionKnowledgeBaseUnavailableException exception,
            HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在", request);
    }

    @ExceptionHandler(ChatSessionScopeRevokedException.class)
    ResponseEntity<ApiError> handleScopeRevoked(ChatSessionScopeRevokedException exception,
            HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "SESSION_SCOPE_REVOKED", "会话知识范围已失效", request);
    }

    @ExceptionHandler(ChatIdempotencyConflictException.class)
    ResponseEntity<ApiError> handleIdempotencyConflict(ChatIdempotencyConflictException exception,
            HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等请求标识与原请求不一致", request);
    }

    @ExceptionHandler(ChatRunInProgressException.class)
    ResponseEntity<ApiError> handleRunInProgress(ChatRunInProgressException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "RUN_IN_PROGRESS", "当前会话已有进行中的运行", request);
    }

    @ExceptionHandler(ChatSessionActiveRunException.class)
    ResponseEntity<ApiError> handleSessionActiveRun(ChatSessionActiveRunException exception,
            HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "SESSION_RUN_IN_PROGRESS", "会话存在进行中的运行，请先取消运行", request);
    }

    @ExceptionHandler(ChatRunNotFoundException.class)
    ResponseEntity<ApiError> handleRunNotFound(ChatRunNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "CHAT_RUN_NOT_FOUND", "问答运行不存在", request);
    }

    @ExceptionHandler(ChatStreamCapacityException.class)
    ResponseEntity<ApiError> handleStreamCapacity(ChatStreamCapacityException exception, HttpServletRequest request) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "REQUEST_LIMITED", "当前请求较多，请稍后重试", request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, TraceIdFilter.getTraceId(request)));
    }
}
