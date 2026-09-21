package com.haha.supportai.knowledge.document.embedding.exception;

import com.haha.supportai.knowledge.ingestion.exception.IngestionProcessingException;

public class DocumentIndexIntegrityException extends IngestionProcessingException {

    public DocumentIndexIntegrityException() {
        super("DOCUMENT_INDEX_INCONSISTENT", "文档索引数据不完整");
    }
}
