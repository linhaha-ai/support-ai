package com.haha.supportai.assistant.agent.exception;

import lombok.Getter;

@Getter
public class AssistantAgentBudgetException extends RuntimeException {

    private final String errorCode;

    public AssistantAgentBudgetException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
