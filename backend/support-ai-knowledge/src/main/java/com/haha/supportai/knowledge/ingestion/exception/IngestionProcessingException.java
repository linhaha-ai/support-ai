package com.haha.supportai.knowledge.ingestion.exception;

import lombok.Getter;

@Getter
public class IngestionProcessingException extends RuntimeException {

    private final String errorCode; // 可向任务查询接口公开的稳定错误码

    private final String safeDetail; // 不包含路径、堆栈或供应商信息的错误摘要

    public IngestionProcessingException(String errorCode, String safeDetail) {
        super(safeDetail);
        this.errorCode = errorCode;
        this.safeDetail = safeDetail;
    }

    public IngestionProcessingException(String errorCode, String safeDetail, Throwable cause) {
        super(safeDetail, cause);
        this.errorCode = errorCode;
        this.safeDetail = safeDetail;
    }
}
