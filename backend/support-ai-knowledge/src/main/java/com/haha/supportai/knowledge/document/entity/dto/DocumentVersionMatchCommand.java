package com.haha.supportai.knowledge.document.entity.dto;

import java.util.UUID;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class DocumentVersionMatchCommand {

    private final UUID documentId; // 待更新的逻辑文档标识

    private final long expectedRevision; // 客户端读取到的文档修订号

    private final String fileHash; // 本次原文件 SHA-256 摘要

    private final String parserVersion; // 本次解析器版本

    private final String chunkerVersion; // 本次分块器版本

    private final String embeddingModel; // 本次嵌入模型名称

    private final int dimensions; // 本次目标向量维度

    private final String configHash; // 本次处理配置摘要
}
