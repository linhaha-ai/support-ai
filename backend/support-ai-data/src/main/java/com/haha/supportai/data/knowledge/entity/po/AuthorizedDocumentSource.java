package com.haha.supportai.data.knowledge.entity.po;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuthorizedDocumentSource {

    private UUID knowledgeBaseId; // 来源所属知识库标识

    private UUID documentId; // 来源所属文档标识

    private String documentName; // 文档展示名称

    private UUID versionId; // 来源所属文档版本标识

    private int versionNo; // 文档内版本号

    private UUID chunkId; // 来源分块标识

    private int chunkIndex; // 文档版本内分块序号

    private String section; // 分块所属章节，可为空

    private Integer pageNo; // 原始页码，可为空

    private int startOffset; // 分块正文起始字符偏移量

    private int endOffset; // 分块正文结束字符偏移量

    private String content; // 向量存储中的分块正文

    private boolean current; // 是否仍为文档当前活动版本
}
