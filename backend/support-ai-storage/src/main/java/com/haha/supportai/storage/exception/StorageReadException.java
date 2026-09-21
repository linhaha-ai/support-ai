package com.haha.supportai.storage.exception;

/**
 * 受控文件读取失败异常。
 */
public class StorageReadException extends RuntimeException {

    public StorageReadException(Throwable cause) {
        super("文件读取失败", cause);
    }
}
