package com.haha.supportai.conversation.exception;

public class RagPersistenceException extends RuntimeException {

    public RagPersistenceException() {
        super("问答运行状态保存失败");
    }
}
