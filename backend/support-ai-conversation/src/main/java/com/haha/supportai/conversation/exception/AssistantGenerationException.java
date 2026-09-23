package com.haha.supportai.conversation.exception;

import lombok.Getter;

@Getter
public class AssistantGenerationException extends RuntimeException {

    private final String errorCode;

    public AssistantGenerationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
