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
public class AuthorizedRetrievalHit {

    private UUID knowledgeBaseId; // 命中的知识库标识

    private UUID documentId; // 命中的文档标识

    private String documentName; // 命中文档展示名称

    private UUID versionId; // 命中的已发布文档版本标识

    private int versionNo; // 文档内的已发布版本号

    private UUID chunkId; // 命中的稳定分块标识

    private int chunkIndex; // 文档版本内的分块序号

    private String section; // 分块所属章节，可为空

    private Integer pageNo; // 原始页码，可为空

    private int startOffset; // 分块正文起始字符偏移量

    private int endOffset; // 分块正文结束字符偏移量

    private String content; // 向量存储中的分块正文

    private double score; // 基于余弦距离换算的相似度分数
}
