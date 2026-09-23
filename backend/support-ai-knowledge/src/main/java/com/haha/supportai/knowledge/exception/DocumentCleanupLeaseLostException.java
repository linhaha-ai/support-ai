package com.haha.supportai.knowledge.exception;

public class DocumentCleanupLeaseLostException extends RuntimeException {

    public DocumentCleanupLeaseLostException() {
        super("文档清理租约已失效");
    }
}
