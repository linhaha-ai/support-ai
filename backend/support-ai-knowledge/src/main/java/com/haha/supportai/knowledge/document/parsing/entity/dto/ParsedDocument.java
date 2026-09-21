package com.haha.supportai.knowledge.document.parsing.entity.dto;

import java.util.List;

import lombok.Getter;

@Getter
public class ParsedDocument {

    private final String normalizedText; // 完成换行与行尾空白清理后的全文

    private final List<DocumentSection> sections; // 按标题边界划分的可定位章节

    public ParsedDocument(String normalizedText, List<DocumentSection> sections) {
        this.normalizedText = normalizedText;
        this.sections = List.copyOf(sections);
    }
}
