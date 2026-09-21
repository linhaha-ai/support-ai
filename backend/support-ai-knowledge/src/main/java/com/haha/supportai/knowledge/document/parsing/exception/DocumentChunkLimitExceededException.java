package com.haha.supportai.knowledge.document.parsing.exception;

import com.haha.supportai.knowledge.ingestion.exception.IngestionProcessingException;

public class DocumentChunkLimitExceededException extends IngestionProcessingException {

    public DocumentChunkLimitExceededException() {
        super("INGESTION_PROCESSING_FAILED", "文档处理暂时失败");
    }
}
