package com.haha.supportai.knowledge.document.handler;

import com.haha.supportai.common.entity.vo.ApiError;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.knowledge.document.exception.DocumentFileEmptyException;
import com.haha.supportai.knowledge.document.exception.DocumentFileEncodingInvalidException;
import com.haha.supportai.knowledge.document.exception.DocumentFileRequiredException;
import com.haha.supportai.knowledge.document.exception.DocumentFileTooLargeException;
import com.haha.supportai.knowledge.document.exception.DocumentFileTypeUnsupportedException;
import com.haha.supportai.knowledge.document.exception.DocumentNameInvalidException;
import com.haha.supportai.knowledge.document.exception.DocumentNotFoundException;
import com.haha.supportai.knowledge.document.exception.DocumentQueryInvalidException;
import com.haha.supportai.knowledge.document.exception.DocumentStorageUnavailableException;
import com.haha.supportai.knowledge.document.exception.DocumentUploadFailedException;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentReadFailedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DocumentExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentExceptionHandler.class);

    @ExceptionHandler(DocumentFileRequiredException.class)
    ResponseEntity<ApiError> handleFileRequired(DocumentFileRequiredException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "DOCUMENT_FILE_REQUIRED", "请选择要上传的文件", request);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ApiError> handleMissingRequestPart(MissingServletRequestPartException exception,
            HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "DOCUMENT_FILE_REQUIRED", "请选择要上传的文件", request);
    }

    @ExceptionHandler(DocumentFileEmptyException.class)
    ResponseEntity<ApiError> handleFileEmpty(DocumentFileEmptyException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "DOCUMENT_FILE_EMPTY", "上传文件不能为空", request);
    }

    @ExceptionHandler({ DocumentFileTooLargeException.class, MaxUploadSizeExceededException.class })
    ResponseEntity<ApiError> handleFileTooLarge(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "DOCUMENT_FILE_TOO_LARGE", "上传文件超过大小限制", request);
    }

    @ExceptionHandler(DocumentFileTypeUnsupportedException.class)
    ResponseEntity<ApiError> handleUnsupportedType(DocumentFileTypeUnsupportedException exception,
            HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "DOCUMENT_FILE_TYPE_UNSUPPORTED",
                "仅支持 UTF-8 编码的 Markdown 或 TXT 文件", request);
    }

    @ExceptionHandler(DocumentFileEncodingInvalidException.class)
    ResponseEntity<ApiError> handleInvalidEncoding(DocumentFileEncodingInvalidException exception,
            HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "DOCUMENT_FILE_ENCODING_INVALID", "文件必须使用 UTF-8 编码", request);
    }

    @ExceptionHandler(DocumentNameInvalidException.class)
    ResponseEntity<ApiError> handleInvalidName(DocumentNameInvalidException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "DOCUMENT_NAME_INVALID", "文档名称不合法", request);
    }

    @ExceptionHandler(DocumentQueryInvalidException.class)
    ResponseEntity<ApiError> handleInvalidQuery(DocumentQueryInvalidException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "DOCUMENT_QUERY_INVALID", "分页参数或游标不合法", request);
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(DocumentNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "文档或版本不存在", request);
    }

    @ExceptionHandler(DocumentReadFailedException.class)
    ResponseEntity<ApiError> handleReadFailed(DocumentReadFailedException exception, HttpServletRequest request) {
        logFailure(request, exception);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DOCUMENT_READ_FAILED", "原文件暂时无法读取", request);
    }

    @ExceptionHandler(DocumentStorageUnavailableException.class)
    ResponseEntity<ApiError> handleStorageUnavailable(DocumentStorageUnavailableException exception,
            HttpServletRequest request) {
        logFailure(request, exception);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DOCUMENT_STORAGE_UNAVAILABLE", "原文件暂时无法保存", request);
    }

    @ExceptionHandler(DocumentUploadFailedException.class)
    ResponseEntity<ApiError> handleUploadFailed(DocumentUploadFailedException exception, HttpServletRequest request) {
        logFailure(request, exception);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DOCUMENT_UPLOAD_FAILED", "文档暂时无法受理", request);
    }

    private void logFailure(HttpServletRequest request, RuntimeException exception) {
        LOGGER.error("文档操作失败: traceId={}, type={}", TraceIdFilter.getTraceId(request),
                exception.getClass().getSimpleName());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, TraceIdFilter.getTraceId(request)));
    }
}
