package com.haha.supportai.knowledge.ingestion.exception;

public class IngestionLeaseLostException extends RuntimeException {

    public IngestionLeaseLostException() {
        super("入库任务租约已失效");
    }
}
