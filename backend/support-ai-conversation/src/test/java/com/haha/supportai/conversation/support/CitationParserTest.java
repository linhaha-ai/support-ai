package com.haha.supportai.conversation.support;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class CitationParserTest {

    private final CitationParser citationParser = new CitationParser();

    @Test
    void keepsRegisteredCitationsInFirstOccurrenceOrderWithoutDuplicates() {
        assertThat(citationParser.findRegisteredCitations("先看[2]，再看[1]，重复[2]。", 2))
                .containsExactly(2, 1);
    }

    @Test
    void ignoresUnknownAndNonCanonicalCitationNumbers() {
        assertThat(citationParser.findRegisteredCitations(
                "合法[1]，未知[3]，零值[0]，前导零[01]，超长[999999999999999999999999999999]。", 2))
                .containsExactly(1);
    }

    @Test
    void includesEveryRegisteredRangeMemberAndFootnoteInFirstOccurrenceOrder() {
        assertThat(citationParser.findRegisteredCitations("先看[^3]，再看[1-3]，也可看[2]至[4]。", 4))
                .containsExactly(3, 1, 2, 4);
        assertThat(citationParser.findRegisteredCitations("[1] 到 [3]、[2–4]、[3—4]", 4))
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    void rejectsInvalidRangeAsAWholeRatherThanKeepingItsRegisteredEndpoints() {
        assertThat(citationParser.findRegisteredCitations(
                "[1]至[9] [1-9] [3]到[1] [3-1] [01]至[2] [0-2] [^01] [^9] 合法[^2]", 3))
                .containsExactly(2);
    }

    @Test
    void boundsRangeExpansionWithoutAllocatingForTheSourceCountOrOverflowingNumbers() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertThat(citationParser.findRegisteredCitations(
                    "[1-2147483647] [1]至[999999999999999999999999] [2147483646-2147483647] [1-101]",
                    Integer.MAX_VALUE)).containsExactly(2147483646, 2147483647);
        });
        assertThat(citationParser.findRegisteredCitations("[1-100]", 100)).hasSize(100);
    }

    @Test
    void remapsEveryRangeMemberWithoutInferringAnIntervalBetweenNewNumbers() {
        assertThat(citationParser.remapRegisteredCitations("参考[1]至[3]，补充[^2]。",
                Map.of(1, 7, 2, 2, 3, 5)))
                .isEqualTo("参考[7][2][5]，补充[2]。");
        assertThat(citationParser.remapRegisteredCitations("[1-3] [^3] [999999999999999]",
                Map.of(1, 7, 3, 5)))
                .isEqualTo("[历史来源未登记] [5] [历史来源未登记]");
    }
}
