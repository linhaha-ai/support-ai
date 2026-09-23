package com.haha.supportai.ai.entity.dto;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class RerankModelResult {

    private final List<RerankDocumentScore> rankings; // 按相关性降序返回的候选下标和分数

    private final Integer totalTokens; // 提供方返回的本次调用总 Token，未知时为空
}
