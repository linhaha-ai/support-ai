package com.haha.supportai.conversation.support;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

@Component
public class CitationParser {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[([1-9]\\d*)]");

    /**
     * 仅返回本轮已登记且在回答中实际出现的编号，未知编号保留在正文但不会成为可点击来源。
     */
    public List<Integer> findRegisteredCitations(String answer, int sourceCount) {
        if (answer == null || answer.isBlank() || sourceCount < 1) {
            return List.of();
        }
        Set<Integer> registeredNumbers = IntStream.rangeClosed(1, sourceCount)
                .boxed()
                .collect(Collectors.toSet());
        Set<Integer> citedNumbers = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            try {
                int citationNumber = Integer.parseInt(matcher.group(1));
                if (registeredNumbers.contains(citationNumber)) {
                    citedNumbers.add(citationNumber);
                }
            } catch (NumberFormatException ignored) {
                // 超长数字不是合法来源编号，保留原文并忽略链接。
            }
        }
        return List.copyOf(citedNumbers);
    }
}
