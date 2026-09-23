package com.haha.supportai.knowledge.support;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class RetrievalKeywordExtractor {

    private static final Pattern METHOD_PATH_PATTERN = Pattern.compile(
            "(?i)(?<![A-Za-z])(GET|POST|PUT|PATCH|DELETE)\\s+(/[A-Za-z0-9{}._~%/-]{1,255})");
    private static final Pattern API_PATH_PATTERN = Pattern.compile("/[A-Za-z0-9{}._~%/-]{1,255}");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_-])[A-Za-z][A-Za-z0-9_-]{2,63}(?![A-Za-z0-9_-])");
    private static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("[a-z][A-Z]");
    private static final Pattern SIMPLE_IDENTIFIER_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{2,63}");
    private static final Pattern QUOTED_TERM_PATTERN = Pattern.compile(
            "`([^`]{2,64})`|“([^”]{2,64})”|\"([^\"]{2,64})\"");
    private static final Set<String> GENERIC_UPPERCASE_TERMS = Set.of(
            "API", "DELETE", "GET", "HTTP", "HTTPS", "PATCH", "POST", "PUT", "URL");
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");
    private static final String IDENTIFIER_BOUNDARY = "A-Za-z0-9_-";
    private static final String PATH_BOUNDARY = "A-Za-z0-9_{}./%-";

    public List<String> extractPatterns(String query, int maxKeywords) {
        LinkedHashSet<String> literals = new LinkedHashSet<>();
        collectMethodPaths(query, literals);
        collectPaths(query, literals);
        collectQuotedTerms(query, literals);
        collectIdentifiers(query, literals);
        return literals.stream()
                .limit(maxKeywords)
                .map(this::toExactPattern)
                .toList();
    }

    private void collectMethodPaths(String query, Set<String> literals) {
        Matcher matcher = METHOD_PATH_PATTERN.matcher(query);
        while (matcher.find()) {
            literals.add(normalize(matcher.group(1) + " " + matcher.group(2)));
        }
    }

    private void collectPaths(String query, Set<String> literals) {
        Matcher matcher = API_PATH_PATTERN.matcher(query);
        while (matcher.find()) {
            literals.add(normalize(matcher.group()));
        }
    }

    private void collectIdentifiers(String query, Set<String> literals) {
        Matcher matcher = IDENTIFIER_PATTERN.matcher(query);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (isHighSignalIdentifier(candidate)) {
                literals.add(normalize(candidate));
            }
        }
    }

    private void collectQuotedTerms(String query, Set<String> literals) {
        Matcher matcher = QUOTED_TERM_PATTERN.matcher(query);
        while (matcher.find()) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
                if (matcher.group(group) != null) {
                    literals.add(normalize(matcher.group(group)));
                    break;
                }
            }
        }
    }

    private boolean isHighSignalIdentifier(String candidate) {
        String uppercase = candidate.toUpperCase(Locale.ROOT);
        if (GENERIC_UPPERCASE_TERMS.contains(uppercase)) {
            return false;
        }
        boolean hasDigitOrSeparator = candidate.chars()
                .anyMatch(character -> Character.isDigit(character) || character == '-' || character == '_');
        boolean hasCamelCase = CAMEL_CASE_PATTERN.matcher(candidate).find();
        boolean uppercaseTerm = candidate.equals(uppercase) && candidate.length() >= 4;
        return hasDigitOrSeparator || hasCamelCase || uppercaseTerm;
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String toExactPattern(String literal) {
        int separator = literal.indexOf(' ');
        if (separator > 0 && HTTP_METHODS.contains(literal.substring(0, separator))) {
            String method = literal.substring(0, separator);
            String path = literal.substring(separator + 1);
            return "(^|[^A-Za-z])" + method + "\\s+" + escapeRegex(path)
                    + "([^" + PATH_BOUNDARY + "]|$)";
        }
        String escaped = escapeRegex(literal);
        if (literal.startsWith("/")) {
            return boundaryPattern(escaped, PATH_BOUNDARY);
        }
        if (SIMPLE_IDENTIFIER_PATTERN.matcher(literal).matches()) {
            return boundaryPattern(escaped, IDENTIFIER_BOUNDARY);
        }
        return escaped;
    }

    private String boundaryPattern(String escaped, String boundaryCharacters) {
        return "(^|[^" + boundaryCharacters + "])" + escaped + "([^" + boundaryCharacters + "]|$)";
    }

    private String escapeRegex(String literal) {
        StringBuilder escaped = new StringBuilder(literal.length());
        for (int index = 0; index < literal.length(); index++) {
            char character = literal.charAt(index);
            if ("\\.^$|?*+()[]{}".indexOf(character) >= 0) {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }
}
