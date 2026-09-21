package com.haha.supportai.knowledge.document.parsing.service;

import java.util.List;
import java.util.stream.IntStream;

import com.haha.supportai.knowledge.document.parsing.config.DocumentParsingProperties;
import com.haha.supportai.knowledge.document.parsing.entity.dto.DocumentChunkDraft;
import com.haha.supportai.knowledge.document.parsing.entity.dto.ParsedDocument;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentChunkLimitExceededException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBudgetChunkerTest {

    private final DocumentTextParser parser = new DocumentTextParser();

    @Test
    void keepsEveryLongParagraphChunkWithinTokenBudget() {
        DocumentParsingProperties properties = properties(32);
        TokenBudgetChunker chunker = new TokenBudgetChunker(properties);
        ParsedDocument parsed = parser.parse("知识库内容用于验证超长中文段落。".repeat(300), "text/plain");

        List<DocumentChunkDraft> chunks = chunker.chunk(parsed);

        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk -> {
            assertThat(chunk.getTokenCount()).isBetween(1, 32);
            assertThat(chunk.getContent())
                    .isEqualTo(parsed.getNormalizedText().substring(chunk.getStartOffset(), chunk.getEndOffset()));
            assertThat(chunk.getCharCount()).isEqualTo(chunk.getEndOffset() - chunk.getStartOffset());
            assertThat(chunk.getContentHash()).hasSize(64);
        });
        assertThat(chunks).extracting(DocumentChunkDraft::getChunkIndex)
                .containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks.stream().map(DocumentChunkDraft::getContent).reduce("", String::concat))
                .isEqualTo(parsed.getNormalizedText());
    }

    @Test
    void prefersNaturalBoundariesAndCarriesHeadingPath() {
        DocumentParsingProperties properties = properties(20);
        TokenBudgetChunker chunker = new TokenBudgetChunker(properties);
        ParsedDocument parsed = parser.parse("# 指南\n第一段介绍。\n\n## 操作\n第二段说明。第三段说明。", "text/markdown");

        List<DocumentChunkDraft> chunks = chunker.chunk(parsed);

        assertThat(chunks).extracting(DocumentChunkDraft::getSection)
                .contains("指南", "指南 / 操作");
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.getContent())
                        .isEqualTo(parsed.getNormalizedText().substring(chunk.getStartOffset(), chunk.getEndOffset())));
    }

    @Test
    void producesStableChunksForTheSameInputAndConfiguration() {
        DocumentParsingProperties properties = properties(24);
        TokenBudgetChunker chunker = new TokenBudgetChunker(properties);
        ParsedDocument parsed = parser.parse("# 稳定性\n同一份输入必须生成相同分块。\n".repeat(20), "text/markdown");

        List<DocumentChunkDraft> first = chunker.chunk(parsed);
        List<DocumentChunkDraft> second = chunker.chunk(parsed);

        assertThat(second).usingRecursiveFieldByFieldElementComparator().containsExactlyElementsOf(first);
    }

    @Test
    void rejectsDocumentsThatExceedTheConfiguredChunkLimit() {
        DocumentParsingProperties properties = properties(8);
        properties.setMaxChunks(1);
        TokenBudgetChunker chunker = new TokenBudgetChunker(properties);
        ParsedDocument parsed = parser.parse("需要拆成多个分块的中文内容。".repeat(20), "text/plain");

        assertThatThrownBy(() -> chunker.chunk(parsed))
                .isInstanceOf(DocumentChunkLimitExceededException.class)
                .hasMessage("文档处理暂时失败");
    }

    @Test
    void doesNotCreateChunksForLeadingWhitespaceOnlyRanges() {
        TokenBudgetChunker chunker = new TokenBudgetChunker(properties(32));
        ParsedDocument parsed = parser.parse("  \n\t\n正文内容", "text/plain");

        List<DocumentChunkDraft> chunks = chunker.chunk(parsed);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.getContent()).isEqualTo("正文内容");
            assertThat(chunk.getStartOffset()).isEqualTo(parsed.getNormalizedText().indexOf("正文内容"));
            assertThat(chunk.getTokenCount()).isPositive();
        });
    }

    @Test
    void neverSplitsUtf16SurrogatePairs() {
        TokenBudgetChunker chunker = new TokenBudgetChunker(properties(12));
        ParsedDocument parsed = parser.parse("知识😀检索🚀".repeat(40), "text/plain");

        List<DocumentChunkDraft> chunks = chunker.chunk(parsed);

        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk -> {
            assertThat(chunk.getContent()).doesNotContain("\uFFFD");
            assertThat(Character.isLowSurrogate(
                    parsed.getNormalizedText().charAt(chunk.getStartOffset()))).isFalse();
            if (chunk.getEndOffset() < parsed.getNormalizedText().length()) {
                assertThat(Character.isLowSurrogate(
                        parsed.getNormalizedText().charAt(chunk.getEndOffset()))).isFalse();
            }
        });
    }

    @Test
    void treatsTokenizerSpecialMarkersAsOrdinaryDocumentText() {
        TokenBudgetChunker chunker = new TokenBudgetChunker(properties(16));
        ParsedDocument parsed = parser.parse("错误样本 <|endoftext|> 必须按普通文本处理。".repeat(20), "text/plain");

        List<DocumentChunkDraft> chunks = chunker.chunk(parsed);

        assertThat(chunks).isNotEmpty().allSatisfy(chunk ->
                assertThat(chunk.getTokenCount()).isBetween(1, 16));
    }

    private DocumentParsingProperties properties(int maxTokens) {
        DocumentParsingProperties properties = new DocumentParsingProperties();
        properties.setMaxTokens(maxTokens);
        return properties;
    }
}
