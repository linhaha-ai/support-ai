package com.haha.supportai.conversation.exception;

public class RagGenerationException extends RuntimeException {

    private final String errorCode;

    public RagGenerationException() {
        this("RAG_GENERATION_FAILED", "本次问答生成失败，请稍后重试");
    }

    public RagGenerationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
