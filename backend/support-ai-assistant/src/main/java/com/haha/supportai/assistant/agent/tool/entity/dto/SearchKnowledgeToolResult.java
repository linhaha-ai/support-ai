package com.haha.supportai.assistant.agent.tool.entity.dto;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class SearchKnowledgeToolResult {

    private final List<KnowledgeToolSource> sources; // 带服务端来源句柄的授权知识片段
}
