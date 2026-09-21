package com.haha.supportai.knowledge.document.parsing.service;

import com.haha.supportai.knowledge.document.parsing.entity.dto.ParsedDocument;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentEmptyException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTextParserTest {

    private final DocumentTextParser parser = new DocumentTextParser();

    @Test
    void normalizesBomNewlinesAndTrailingWhitespaceForPlainText() {
        ParsedDocument parsed = parser.parse("\uFEFF第一\u0000行\u0007  \r\n第二行\t\r第三行 ", "text/plain");

        assertThat(parsed.getNormalizedText()).isEqualTo("第一行\n第二行\n第三行");
        assertThat(parsed.getSections()).singleElement().satisfies(section -> {
            assertThat(section.getSection()).isEmpty();
            assertThat(section.getStartOffset()).isZero();
            assertThat(section.getEndOffset()).isEqualTo(parsed.getNormalizedText().length());
        });
    }

    @Test
    void preservesMarkdownHeadingHierarchyAndSourceOffsets() {
        ParsedDocument parsed = parser.parse("引言\n\n# 概述  \r\n第一段。\r\n\r\n## 细节\r第二段\t \r\n", "text/markdown");

        assertThat(parsed.getNormalizedText()).isEqualTo("引言\n\n# 概述\n第一段。\n\n## 细节\n第二段\n");
        assertThat(parsed.getSections()).hasSize(3);
        assertThat(parsed.getSections()).extracting(section -> section.getSection())
                .containsExactly("", "概述", "概述 / 细节");
        assertThat(parsed.getSections()).allSatisfy(section ->
                assertThat(parsed.getNormalizedText().substring(section.getStartOffset(), section.getEndOffset()))
                        .isNotBlank());
        assertThat(parsed.getNormalizedText().substring(parsed.getSections().get(1).getStartOffset()))
                .startsWith("# 概述");
    }

    @Test
    void rejectsContentThatIsEmptyAfterNormalization() {
        assertThatThrownBy(() -> parser.parse("\uFEFF \t\r\n\t", "text/plain"))
                .isInstanceOfSatisfying(DocumentEmptyException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo("DOCUMENT_EMPTY");
                    assertThat(exception).hasMessage("文档没有可处理内容");
                });
    }
}
