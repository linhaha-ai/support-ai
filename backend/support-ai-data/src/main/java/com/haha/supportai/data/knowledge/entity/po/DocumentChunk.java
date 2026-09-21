package com.haha.supportai.data.knowledge.entity.po;

import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("document_chunk")
public class DocumentChunk {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id; // 稳定分块标识，由业务层生成 UUID

    @TableField("version_id")
    private UUID versionId; // 所属文档版本标识

    @TableField("chunk_index")
    private int chunkIndex; // 文档版本内分块序号，从零开始

    @TableField("section")
    private String section; // 分块所属章节层级，可为空

    @TableField("page_no")
    private Integer pageNo; // 原始页码，预留 PDF 扩展使用

    @TableField("start_offset")
    private int startOffset; // 规范化文本起始字符偏移量，左闭区间

    @TableField("end_offset")
    private int endOffset; // 规范化文本结束字符偏移量，右开区间

    @TableField("content_hash")
    private String contentHash; // 分块正文 SHA-256 小写十六进制摘要

    @TableField("char_count")
    private int charCount; // 分块正文字符数

    @TableField("token_count")
    private Integer tokenCount; // 分块正文 token 估计值，可为空
}
