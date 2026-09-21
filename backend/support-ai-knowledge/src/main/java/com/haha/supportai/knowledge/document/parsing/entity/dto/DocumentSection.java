package com.haha.supportai.knowledge.document.parsing.entity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class DocumentSection {

    private final String section; // 当前内容所在的 Markdown 标题层级，纯文本为空字符串

    private final int startOffset; // 在规范化文本中的 UTF-16 起始偏移，包含该位置

    private final int endOffset; // 在规范化文本中的 UTF-16 结束偏移，不包含该位置
}
