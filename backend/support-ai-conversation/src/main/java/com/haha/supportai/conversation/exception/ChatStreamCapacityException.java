package com.haha.supportai.conversation.exception;

public class ChatStreamCapacityException extends RuntimeException {

    public ChatStreamCapacityException() {
        super("当前请求较多，请稍后重试");
    }
}
