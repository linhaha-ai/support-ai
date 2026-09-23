package com.haha.supportai.assistant.agent.support;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.haha.supportai.assistant.agent.entity.dto.AssistantCitationResult;
import com.haha.supportai.assistant.agent.exception.AssistantCitationException;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import org.springframework.stereotype.Component;

@Component
public class AssistantCitationParser {

    private static final Pattern SOURCE_HANDLE_PATTERN = Pattern.compile(
            "\\[\\[source:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12})]]");
    private static final Pattern UNTRUSTED_NUMBER_PATTERN = Pattern.compile("\\[[1-9][0-9]*]");

    public AssistantCitationResult parse(String rawAnswer, List<MessageSource> sources) {
        if (rawAnswer == null || rawAnswer.isBlank() || sources == null) {
            throw new AssistantCitationException();
        }
        if (UNTRUSTED_NUMBER_PATTERN.matcher(rawAnswer).find()) {
            throw new AssistantCitationException();
        }
        Map<UUID, Integer> citationNumbers = new LinkedHashMap<>();
        Set<Integer> registeredNumbers = new LinkedHashSet<>();
        for (MessageSource source : sources) {
            if (source == null || source.getId() == null || source.getCitationNo() < 1
                    || citationNumbers.putIfAbsent(source.getId(), source.getCitationNo()) != null
                    || !registeredNumbers.add(source.getCitationNo())) {
                throw new AssistantCitationException();
            }
        }

        Matcher matcher = SOURCE_HANDLE_PATTERN.matcher(rawAnswer);
        StringBuffer normalized = new StringBuffer();
        Set<Integer> cited = new LinkedHashSet<>();
        while (matcher.find()) {
            Integer citationNumber = citationNumbers.get(UUID.fromString(matcher.group(1)));
            if (citationNumber == null) {
                throw new AssistantCitationException();
            }
            cited.add(citationNumber);
            matcher.appendReplacement(normalized, Matcher.quoteReplacement("[" + citationNumber + "]"));
        }
        matcher.appendTail(normalized);
        if (normalized.indexOf("[[source:") >= 0) {
            throw new AssistantCitationException();
        }
        String answer = normalized.toString().trim();
        if (answer.isEmpty()) {
            throw new AssistantCitationException();
        }
        return new AssistantCitationResult(answer, List.copyOf(cited));
    }
}
