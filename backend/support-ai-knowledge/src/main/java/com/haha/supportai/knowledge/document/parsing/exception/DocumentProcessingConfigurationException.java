package com.haha.supportai.knowledge.document.parsing.exception;

import com.haha.supportai.knowledge.ingestion.exception.IngestionProcessingException;

public class DocumentProcessingConfigurationException extends IngestionProcessingException {

    public DocumentProcessingConfigurationException() {
        super("INGESTION_PROCESSING_FAILED", "文档处理暂时失败");
    }

    public DocumentProcessingConfigurationException(Throwable cause) {
        super("INGESTION_PROCESSING_FAILED", "文档处理暂时失败", cause);
    }
}
