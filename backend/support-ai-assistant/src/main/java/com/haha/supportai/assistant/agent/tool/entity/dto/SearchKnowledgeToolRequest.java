package com.haha.supportai.assistant.agent.tool.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SearchKnowledgeToolRequest {

    private String query; // 模型可提供的知识检索问题
}
