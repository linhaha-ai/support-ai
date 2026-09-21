package com.haha.supportai.knowledge.document.entity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ProcessingConfigurationSnapshot {

    private final String embeddingModel; // 当前选定的向量模型名称

    private final int dimensions; // 当前选定的向量维度

    private final String configHash; // 配置快照 SHA-256 摘要

    private final String configSnapshot; // 不含密钥的配置 JSON 快照
}
