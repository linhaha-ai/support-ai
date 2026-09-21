package com.haha.supportai.knowledge.document.entity.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class NewDocumentVersionCommand {

    private final UUID knowledgeBaseId; // 目标知识库标识

    private final UUID documentId; // 逻辑文档标识

    private final UUID versionId; // 首个文档版本标识

    private final UUID jobId; // 持久导入任务标识

    private final String displayName; // 文档展示名称

    private final String storageKey; // 文件存储服务生成的内部键

    private final String fileHash; // 原文件 SHA-256 摘要

    private final long fileSize; // 原文件字节数

    private final String mediaType; // 规范化媒体类型

    private final String originalFilename; // 已去除路径信息的原始文件名

    private final String parserVersion; // 解析器版本

    private final String chunkerVersion; // 分块器版本

    private final String embeddingModel; // 向量模型名称

    private final int dimensions; // 目标向量维度

    private final String configHash; // 处理配置 SHA-256 摘要

    private final String configSnapshot; // 不含密钥的处理配置 JSON 快照

    private final int maxAttempts; // 持久导入任务最大尝试次数

    private final OffsetDateTime createdAt; // 统一创建时间
}
