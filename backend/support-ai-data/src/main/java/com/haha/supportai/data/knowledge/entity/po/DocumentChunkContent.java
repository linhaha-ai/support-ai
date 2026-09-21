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
public class DocumentChunkContent {

    private UUID id; // 稳定分块标识

    private UUID versionId; // 所属文档版本标识

    private int chunkIndex; // 文档版本内分块序号，从零开始

    private String section; // 分块所属章节层级，可为空

    private Integer pageNo; // 原始页码，可为空

    private int startOffset; // 规范化文本起始字符偏移量，左闭区间

    private int endOffset; // 规范化文本结束字符偏移量，右开区间

    private int charCount; // 分块正文字符数

    private Integer tokenCount; // 分块正文 token 估计值，可为空

    private String content; // vector_store 中的分块正文，嵌入未完成时为空
}
