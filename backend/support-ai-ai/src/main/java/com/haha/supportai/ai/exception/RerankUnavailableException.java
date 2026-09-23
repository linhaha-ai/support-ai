package com.haha.supportai.ai.exception;

public class RerankUnavailableException extends RuntimeException {

    public RerankUnavailableException(Throwable cause) {
        super("重排序模型暂不可用", cause);
    }
}
