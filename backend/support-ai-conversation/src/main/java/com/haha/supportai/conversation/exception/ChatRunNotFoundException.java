package com.haha.supportai.conversation.exception;

public class ChatRunNotFoundException extends RuntimeException {

    public ChatRunNotFoundException() {
        super("问答运行不存在");
    }
}
