package com.haha.supportai.storage;

import java.io.InputStream;

/**
 * 受控原文件存储边界。
 */
public interface FileStore {

    /**
     * 写入上传内容并返回仅供服务端持久化的存储标识。
     *
     * @param inputStream 上传内容流
     * @param extension 已校验的文件扩展名，不包含原始文件名
     * @return 服务端生成的存储标识
     */
    String store(InputStream inputStream, String extension);

    /**
     * 按服务端存储标识打开原文件读取流，调用方负责关闭返回的流。
     *
     * @param storageKey 服务端生成的存储标识
     * @return 原文件读取流
     */
    InputStream open(String storageKey);

    /**
     * 按服务端存储标识删除文件；目标不存在时视为已完成。
     *
     * @param storageKey 服务端生成的存储标识
     */
    void delete(String storageKey);
}
