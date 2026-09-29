package com.haha.supportai.assistant.agent.tool.entity.dto;

import java.util.UUID;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class KnowledgeToolSource {

    private final UUID sourceHandle; // 服务端生成的来源句柄，不接受模型指定

    private final String documentName; // 文档展示名称

    private final String section; // 来源章节，可为空

    private final Integer pageNo; // 来源页码，可为空

    private final String content; // 当前授权范围内的文档片段正文

    // 引用标记由可信句柄生成，模型只需复制，不承担 UUID 与括号拼接。
    public String getCitation() {
        return "[[source:" + sourceHandle + "]]";
    }
}
