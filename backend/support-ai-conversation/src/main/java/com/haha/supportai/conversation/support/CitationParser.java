package com.haha.supportai.conversation.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class CitationParser {

    private static final int MAX_RANGE_SIZE = 100;
    private static final String INVALID_HISTORY_CITATION = "[历史来源未登记]";
    // 先整体匹配范围，防止无效范围的两个端点被误认为独立有效引用。
    private static final Pattern CITATION_PATTERN = Pattern.compile(
            "\\[([0-9]+)]\\h*(?:[-–—]|至|到)\\h*\\[([0-9]+)]"
                    + "|\\[([0-9]+)\\h*[-–—]\\h*([0-9]+)]"
                    + "|\\[\\^?([0-9]+)]");

    /**
     * 仅返回本轮已登记且在回答中实际出现的编号，未知编号保留在正文但不会成为可点击来源。
     */
    public List<Integer> findRegisteredCitations(String answer, int sourceCount) {
        if (answer == null || answer.isBlank() || sourceCount < 1) {
            return List.of();
        }
        Set<Integer> citedNumbers = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            List<Integer> numbers = citationNumbers(matcher);
            if (numbers.stream().allMatch(number -> number <= sourceCount)) {
                citedNumbers.addAll(numbers);
            }
        }
        return List.copyOf(citedNumbers);
    }

    /** 历史编号须逐项映射；不得只替换范围端点，以免把无关的新编号纳入引用。 */
    public String remapRegisteredCitations(String answer, Map<Integer, Integer> citationMapping) {
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        StringBuilder rewritten = new StringBuilder(answer.length());
        while (matcher.find()) {
            List<Integer> numbers = citationNumbers(matcher);
            String replacement = INVALID_HISTORY_CITATION;
            if (!numbers.isEmpty() && numbers.stream().allMatch(number ->
                    citationMapping.get(number) != null && citationMapping.get(number) > 0)) {
                StringBuilder references = new StringBuilder();
                for (Integer number : numbers) {
                    references.append('[').append(citationMapping.get(number)).append(']');
                }
                replacement = references.toString();
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private List<Integer> citationNumbers(Matcher matcher) {
        if (matcher.group(5) != null) {
            Integer number = canonicalNumber(matcher.group(5));
            return number == null ? List.of() : List.of(number);
        }
        int startGroup = matcher.group(1) == null ? 3 : 1;
        Integer start = canonicalNumber(matcher.group(startGroup));
        Integer end = canonicalNumber(matcher.group(startGroup + 1));
        if (start == null || end == null || end < start) {
            return List.of();
        }
        long length = (long) end - start + 1;
        // 展开量与外部编号大小无关，避免超长或溢出范围造成循环和内存耗尽。
        if (length > MAX_RANGE_SIZE) {
            return List.of();
        }
        List<Integer> numbers = new ArrayList<>((int) length);
        for (int offset = 0; offset < length; offset++) {
            numbers.add(start + offset);
        }
        return numbers;
    }

    private Integer canonicalNumber(String value) {
        if (value.charAt(0) == '0' || value.length() > 10) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
