package com.haha.supportai.ai.exception;

public class RerankInvalidResponseException extends RuntimeException {

    public RerankInvalidResponseException() {
        super("重排序模型返回无效结果");
    }
}
