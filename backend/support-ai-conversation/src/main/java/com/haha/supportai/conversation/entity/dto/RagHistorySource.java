package com.haha.supportai.conversation.entity.dto;

import java.util.UUID;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class RagHistorySource {

    private final int citationNumber; // 本轮重新分配的连续来源编号

    private final UUID rootSourceId; // 首次直接检索时创建的根来源标识

    private final UUID knowledgeBaseId; // 来源所属知识库标识

    private final UUID chunkId; // 当前仍有效的文档分块标识

    private final String locator; // 从根来源复制的定位信息 JSON

    private final String snapshot; // 从根来源复制的文档版本快照 JSON
}
