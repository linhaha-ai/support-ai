package com.haha.supportai.conversation.exception;

public class ChatUserConcurrencyLimitException extends RuntimeException {

    public ChatUserConcurrencyLimitException() {
        super("当前账号进行中的问答已达上限");
    }
}
