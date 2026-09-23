package com.haha.supportai.knowledge.document.parsing.exception;

import com.haha.supportai.knowledge.ingestion.exception.IngestionProcessingException;

public class DocumentProcessingConfigurationException extends IngestionProcessingException {

    public DocumentProcessingConfigurationException() {
        super("DOCUMENT_CONFIGURATION_INVALID", "文档处理配置已失效", false);
    }

    public DocumentProcessingConfigurationException(Throwable cause) {
        super("DOCUMENT_CONFIGURATION_INVALID", "文档处理配置已失效", false, cause);
    }
}
