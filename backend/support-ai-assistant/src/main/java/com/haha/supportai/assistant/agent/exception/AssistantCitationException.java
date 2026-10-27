package com.haha.supportai.assistant.agent.exception;

public class AssistantCitationException extends RuntimeException {

    public enum Reason {
        EMPTY_ANSWER,
        UNTRUSTED_NUMBER,
        INVALID_REGISTRY,
        UNKNOWN_HANDLE,
        MALFORMED_HANDLE,
        REQUIRED_TICKET_NOT_CITED,
        REQUIRED_DOCUMENT_NOT_CITED,
        NO_CITED_SOURCE,
        SOURCE_UNAVAILABLE
    }

    private final Reason reason; // 仅保存固定诊断分类，禁止包含模型原文或来源正文

    public AssistantCitationException(Reason reason) {
        super("助手回答包含无效来源引用");
        this.reason = java.util.Objects.requireNonNull(reason);
    }

    public Reason getReason() {
        return reason;
    }
}
