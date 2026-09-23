package com.haha.supportai.ai.entity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class RerankDocumentScore {

    private final int index; // 对应输入候选列表的原始下标

    private final double score; // 当前请求内的相对相关性分数
}
