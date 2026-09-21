package com.haha.supportai.conversation.exception;

public class RagGenerationException extends RuntimeException {

    public RagGenerationException() {
        super("本次问答生成失败，请稍后重试");
    }
}
