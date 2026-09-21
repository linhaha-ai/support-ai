package com.haha.supportai.knowledge.document.parsing.entity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class DocumentChunkDraft {

    private final int chunkIndex; // 当前文档版本内从零开始的稳定分块序号

    private final String section; // 分块对应的 Markdown 标题层级，纯文本为空字符串

    private final int startOffset; // 在规范化文本中的 UTF-16 起始偏移，包含该位置

    private final int endOffset; // 在规范化文本中的 UTF-16 结束偏移，不包含该位置

    private final String contentHash; // 分块原文的 SHA-256 十六进制摘要

    private final int charCount; // 分块内容包含的 UTF-16 代码单元数量

    private final int tokenCount; // 使用 CL100K_BASE 估算的 token 数量

    private final String content; // 可由起止偏移在规范化全文中原样还原的分块内容
}
