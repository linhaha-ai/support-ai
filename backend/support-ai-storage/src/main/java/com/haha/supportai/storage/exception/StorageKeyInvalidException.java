package com.haha.supportai.storage.exception;

public class StorageKeyInvalidException extends RuntimeException {

    public StorageKeyInvalidException() {
        super("非法文件存储标识");
    }
}
