package com.haha.supportai.assistant.agent.tool.exception;

import lombok.Getter;

@Getter
public class AssistantToolException extends RuntimeException {

    private final String errorCode;

    public AssistantToolException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AssistantToolException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
