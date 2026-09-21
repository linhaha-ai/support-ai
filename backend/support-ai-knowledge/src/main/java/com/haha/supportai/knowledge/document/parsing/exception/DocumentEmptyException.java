package com.haha.supportai.knowledge.document.parsing.exception;

import com.haha.supportai.knowledge.ingestion.exception.IngestionProcessingException;

public class DocumentEmptyException extends IngestionProcessingException {

    public DocumentEmptyException() {
        super("DOCUMENT_EMPTY", "文档没有可处理内容");
    }
}
