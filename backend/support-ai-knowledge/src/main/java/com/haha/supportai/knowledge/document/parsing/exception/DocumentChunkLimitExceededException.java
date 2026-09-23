package com.haha.supportai.knowledge.document.parsing.exception;

import com.haha.supportai.knowledge.ingestion.exception.IngestionProcessingException;

public class DocumentChunkLimitExceededException extends IngestionProcessingException {

    public DocumentChunkLimitExceededException() {
        super("DOCUMENT_CHUNK_LIMIT_EXCEEDED", "文档分块数量超过处理上限", false);
    }
}
