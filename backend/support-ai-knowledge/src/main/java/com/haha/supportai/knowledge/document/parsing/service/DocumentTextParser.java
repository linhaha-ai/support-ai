package com.haha.supportai.knowledge.document.parsing.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.haha.supportai.knowledge.document.parsing.entity.dto.DocumentSection;
import com.haha.supportai.knowledge.document.parsing.entity.dto.ParsedDocument;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentEmptyException;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

@Component
public class DocumentTextParser {

    private static final Pattern LINE_END_WHITESPACE = Pattern.compile("[\\t ]+(?=\\n|$)");
    private static final Pattern UNSUPPORTED_C0_CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");
    private static final String SECTION_SEPARATOR = " / ";

    private final Parser markdownParser = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    public ParsedDocument parse(String content, String mediaType) {
        String normalizedText = normalize(content);
        if (normalizedText.isBlank()) {
            throw new DocumentEmptyException();
        }
        if (!isMarkdown(mediaType)) {
            return new ParsedDocument(normalizedText,
                    List.of(new DocumentSection("", 0, normalizedText.length())));
        }
        return new ParsedDocument(normalizedText, parseMarkdownSections(normalizedText));
    }

    private String normalize(String content) {
        String normalized = content == null ? "" : content;
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replace("\r\n", "\n").replace('\r', '\n');
        normalized = UNSUPPORTED_C0_CONTROL.matcher(normalized).replaceAll("");
        return LINE_END_WHITESPACE.matcher(normalized).replaceAll("");
    }

    private boolean isMarkdown(String mediaType) {
        return mediaType != null && mediaType.toLowerCase(Locale.ROOT).contains("markdown");
    }

    private List<DocumentSection> parseMarkdownSections(String normalizedText) {
        Node document = markdownParser.parse(normalizedText);
        List<Integer> lineStartOffsets = calculateLineStartOffsets(normalizedText);
        List<HeadingPosition> headings = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                List<SourceSpan> sourceSpans = heading.getSourceSpans();
                if (!sourceSpans.isEmpty()) {
                    SourceSpan firstSpan = sourceSpans.getFirst();
                    int startOffset = lineStartOffsets.get(firstSpan.getLineIndex()) + firstSpan.getColumnIndex();
                    headings.add(new HeadingPosition(heading.getLevel(), startOffset, extractHeadingText(heading)));
                }
                visitChildren(heading);
            }
        });
        if (headings.isEmpty()) {
            return List.of(new DocumentSection("", 0, normalizedText.length()));
        }

        List<DocumentSection> sections = new ArrayList<>();
        int firstHeadingStart = headings.getFirst().startOffset();
        if (firstHeadingStart > 0 && !normalizedText.substring(0, firstHeadingStart).isBlank()) {
            sections.add(new DocumentSection("", 0, firstHeadingStart));
        }
        String[] titleHierarchy = new String[6];
        for (int index = 0; index < headings.size(); index++) {
            HeadingPosition heading = headings.get(index);
            titleHierarchy[heading.level() - 1] = heading.title();
            Arrays.fill(titleHierarchy, heading.level(), titleHierarchy.length, null);
            int endOffset = index + 1 < headings.size()
                    ? headings.get(index + 1).startOffset()
                    : normalizedText.length();
            sections.add(new DocumentSection(joinTitleHierarchy(titleHierarchy), heading.startOffset(), endOffset));
        }
        return sections;
    }

    private List<Integer> calculateLineStartOffsets(String text) {
        List<Integer> lineStartOffsets = new ArrayList<>();
        lineStartOffsets.add(0);
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lineStartOffsets.add(index + 1);
            }
        }
        return lineStartOffsets;
    }

    private String extractHeadingText(Heading heading) {
        StringBuilder title = new StringBuilder();
        appendInlineText(heading.getFirstChild(), title);
        return title.toString().trim().replaceAll("\\s+", " ");
    }

    private void appendInlineText(Node node, StringBuilder target) {
        Node current = node;
        while (current != null) {
            if (current instanceof Text text) {
                target.append(text.getLiteral());
            } else if (current instanceof Code code) {
                target.append(code.getLiteral());
            } else if (current instanceof SoftLineBreak || current instanceof HardLineBreak) {
                target.append(' ');
            } else if (current.getFirstChild() != null) {
                appendInlineText(current.getFirstChild(), target);
            }
            current = current.getNext();
        }
    }

    private String joinTitleHierarchy(String[] titleHierarchy) {
        List<String> titles = Arrays.stream(titleHierarchy)
                .filter(title -> title != null && !title.isBlank())
                .toList();
        return String.join(SECTION_SEPARATOR, titles);
    }

    private record HeadingPosition(int level, int startOffset, String title) {
    }
}
