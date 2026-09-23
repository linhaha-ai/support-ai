package com.haha.supportai.assistant.agent.exception;

public class AssistantCitationException extends RuntimeException {

    public AssistantCitationException() {
        super("助手回答包含无效来源引用");
    }
}
