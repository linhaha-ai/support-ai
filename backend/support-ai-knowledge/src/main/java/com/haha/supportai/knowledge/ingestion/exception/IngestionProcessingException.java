package com.haha.supportai.knowledge.ingestion.exception;

import lombok.Getter;

@Getter
public class IngestionProcessingException extends RuntimeException {

    private final String errorCode; // 可向任务查询接口公开的稳定错误码

    private final String safeDetail; // 不包含路径、堆栈或供应商信息的错误摘要

    private final boolean automaticRetryAllowed; // 是否允许工作者自动重试当前失败

    public IngestionProcessingException(String errorCode, String safeDetail) {
        this(errorCode, safeDetail, true, null);
    }

    public IngestionProcessingException(String errorCode, String safeDetail, boolean automaticRetryAllowed) {
        this(errorCode, safeDetail, automaticRetryAllowed, null);
    }

    public IngestionProcessingException(String errorCode, String safeDetail, Throwable cause) {
        this(errorCode, safeDetail, true, cause);
    }

    public IngestionProcessingException(String errorCode, String safeDetail, boolean automaticRetryAllowed,
            Throwable cause) {
        super(safeDetail, cause);
        this.errorCode = errorCode;
        this.safeDetail = safeDetail;
        this.automaticRetryAllowed = automaticRetryAllowed;
    }
}
