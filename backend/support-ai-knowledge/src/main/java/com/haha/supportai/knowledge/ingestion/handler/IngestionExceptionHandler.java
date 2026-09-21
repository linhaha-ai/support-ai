package com.haha.supportai.knowledge.ingestion.handler;

import com.haha.supportai.common.entity.vo.ApiError;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.knowledge.ingestion.exception.IngestionJobNotFoundException;
import com.haha.supportai.knowledge.ingestion.exception.IngestionLeaseLostException;
import com.haha.supportai.knowledge.ingestion.exception.IngestionStateConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class IngestionExceptionHandler {

    @ExceptionHandler(IngestionJobNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(IngestionJobNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "INGESTION_JOB_NOT_FOUND", "处理任务不存在", request);
    }

    @ExceptionHandler({ IngestionLeaseLostException.class, IngestionStateConflictException.class })
    ResponseEntity<ApiError> handleConflict(RuntimeException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "INGESTION_JOB_CONFLICT", "处理任务状态已变化", request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, TraceIdFilter.getTraceId(request)));
    }
}
