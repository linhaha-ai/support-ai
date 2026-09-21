package com.haha.supportai.conversation.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
