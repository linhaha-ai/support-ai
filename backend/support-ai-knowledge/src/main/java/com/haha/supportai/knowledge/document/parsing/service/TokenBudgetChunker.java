package com.haha.supportai.knowledge.document.parsing.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.haha.supportai.knowledge.document.parsing.config.DocumentParsingProperties;
import com.haha.supportai.knowledge.document.parsing.entity.dto.DocumentChunkDraft;
import com.haha.supportai.knowledge.document.parsing.entity.dto.DocumentSection;
import com.haha.supportai.knowledge.document.parsing.entity.dto.ParsedDocument;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentChunkLimitExceededException;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TokenBudgetChunker {

    private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    private final DocumentParsingProperties properties;

    public List<DocumentChunkDraft> chunk(ParsedDocument parsedDocument) {
        List<DocumentChunkDraft> chunks = new ArrayList<>();
        String normalizedText = parsedDocument.getNormalizedText();
        for (DocumentSection section : parsedDocument.getSections()) {
            int cursor = section.getStartOffset();
            while (cursor < section.getEndOffset()) {
                cursor = skipWhitespace(normalizedText, cursor, section.getEndOffset());
                if (cursor >= section.getEndOffset()) {
                    break;
                }
                int hardEnd = findLargestEndWithinBudget(normalizedText, cursor, section.getEndOffset());
                int endOffset = preferNaturalBoundary(normalizedText, cursor, hardEnd, section.getEndOffset());
                if (countTokens(normalizedText.substring(cursor, endOffset)) > properties.getMaxTokens()) {
                    endOffset = hardEnd;
                }
                String content = normalizedText.substring(cursor, endOffset);
                int tokenCount = countTokens(content);
                if (chunks.size() >= properties.getMaxChunks()) {
                    throw new DocumentChunkLimitExceededException();
                }
                chunks.add(new DocumentChunkDraft(chunks.size(), section.getSection(), cursor, endOffset,
                        sha256(content), content.length(), tokenCount, content));
                cursor = endOffset;
            }
        }
        return List.copyOf(chunks);
    }

    private int skipWhitespace(String text, int startOffset, int endOffset) {
        int offset = startOffset;
        while (offset < endOffset) {
            int codePoint = text.codePointAt(offset);
            if (!Character.isWhitespace(codePoint)) {
                break;
            }
            offset += Character.charCount(codePoint);
        }
        return offset;
    }

    private int findLargestEndWithinBudget(String text, int startOffset, int sectionEnd) {
        int low = nextCodePointBoundary(text, startOffset);
        int high = sectionEnd;
        int acceptedEnd = startOffset;
        while (low <= high) {
            int candidate = floorCodePointBoundary(text, low + (high - low) / 2);
            if (candidate <= startOffset) {
                candidate = nextCodePointBoundary(text, startOffset);
            }
            if (countTokens(text.substring(startOffset, candidate)) <= properties.getMaxTokens()) {
                acceptedEnd = candidate;
                low = candidate == sectionEnd
                        ? sectionEnd + 1
                        : candidate + Character.charCount(text.codePointAt(candidate));
            } else {
                high = candidate - Character.charCount(text.codePointBefore(candidate));
            }
        }
        if (acceptedEnd == startOffset) {
            throw new IllegalArgumentException("单个 Unicode 字符的 token 数超过了分块预算");
        }
        return acceptedEnd;
    }

    private int preferNaturalBoundary(String text, int startOffset, int hardEnd, int sectionEnd) {
        if (hardEnd == sectionEnd) {
            return hardEnd;
        }
        int paragraphEnd = text.lastIndexOf("\n\n", hardEnd - 2);
        if (paragraphEnd >= startOffset && isUsefulNaturalBoundary(startOffset, paragraphEnd + 2, hardEnd)) {
            return paragraphEnd + 2;
        }
        int lineEnd = text.lastIndexOf('\n', hardEnd - 1);
        if (lineEnd >= startOffset && isUsefulNaturalBoundary(startOffset, lineEnd + 1, hardEnd)) {
            return lineEnd + 1;
        }
        for (int index = hardEnd; index > startOffset; index--) {
            char previous = text.charAt(index - 1);
            if ((isSentenceBoundary(previous) || Character.isWhitespace(previous))
                    && isUsefulNaturalBoundary(startOffset, index, hardEnd)) {
                return index;
            }
        }
        return hardEnd;
    }

    private boolean isUsefulNaturalBoundary(int startOffset, int boundary, int hardEnd) {
        return boundary > startOffset && (boundary - startOffset) * 2 >= hardEnd - startOffset;
    }

    private boolean isSentenceBoundary(char value) {
        return value == '.' || value == '!' || value == '?' || value == ';'
                || value == '\u3002' || value == '\uFF01' || value == '\uFF1F' || value == '\uFF1B';
    }

    private int nextCodePointBoundary(String text, int offset) {
        return offset + Character.charCount(text.codePointAt(offset));
    }

    private int floorCodePointBoundary(String text, int offset) {
        if (offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset))) {
            return offset - 1;
        }
        return offset;
    }

    private int countTokens(String content) {
        return ENCODING.countTokensOrdinary(content);
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}
