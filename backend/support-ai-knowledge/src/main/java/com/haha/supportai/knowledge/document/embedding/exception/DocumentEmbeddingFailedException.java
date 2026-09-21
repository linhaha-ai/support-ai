package com.haha.supportai.knowledge.document.embedding.exception;

import com.haha.supportai.knowledge.ingestion.exception.IngestionProcessingException;

public class DocumentEmbeddingFailedException extends IngestionProcessingException {

    public DocumentEmbeddingFailedException(Throwable cause) {
        super("DOCUMENT_EMBEDDING_FAILED", "文档向量生成暂时失败", cause);
    }
}
