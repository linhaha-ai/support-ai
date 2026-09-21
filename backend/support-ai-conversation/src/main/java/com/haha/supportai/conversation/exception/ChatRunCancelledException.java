package com.haha.supportai.conversation.exception;

public class ChatRunCancelledException extends RuntimeException {

    public ChatRunCancelledException() {
        super("本次回答已停止生成");
    }
}
