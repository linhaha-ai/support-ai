package com.haha.supportai.ai.exception;

public class RerankInputLimitException extends RuntimeException {

    public RerankInputLimitException() {
        super("重排序输入超过安全限制");
    }
}
