package com.haha.supportai.knowledge.document.parsing.exception;

import com.haha.supportai.knowledge.ingestion.exception.IngestionProcessingException;

public class DocumentReadFailedException extends IngestionProcessingException {

    public DocumentReadFailedException(Throwable cause) {
        super("DOCUMENT_READ_FAILED", "原文件暂时无法读取", cause);
    }
}
