package com.haha.supportai.assistant.agent.support;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.assistant.agent.exception.AssistantCitationException;
import com.haha.supportai.assistant.agent.exception.AssistantCitationException.Reason;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantCitationParserTest {

    private static final UUID FIRST_SOURCE_ID = UUID.fromString("27100000-0000-4000-8000-000000000001");
    private static final UUID SECOND_SOURCE_ID = UUID.fromString("27100000-0000-4000-8000-000000000002");

    private final AssistantCitationParser parser = new AssistantCitationParser();

    @Test
    void convertsRegisteredHandlesAndKeepsFirstCitationOrder() {
        String answer = "先检查网络[[source:" + SECOND_SOURCE_ID + "]]，再核对配置[[source:"
                + FIRST_SOURCE_ID + "]]，最后复查网络[[source:" + SECOND_SOURCE_ID + "]]。";

        var result = parser.parse(answer, List.of(source(FIRST_SOURCE_ID, 1), source(SECOND_SOURCE_ID, 2)));

        assertThat(result.getAnswer()).isEqualTo("先检查网络[2]，再核对配置[1]，最后复查网络[2]。");
        assertThat(result.getCitationNumbers()).containsExactly(2, 1);
    }

    @Test
    void rejectsModelGeneratedNumericCitation() {
        assertThatThrownBy(() -> parser.parse("未经登记的结论[1]", List.of(source(FIRST_SOURCE_ID, 1))))
                .isInstanceOfSatisfying(AssistantCitationException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo(Reason.UNTRUSTED_NUMBER));
    }

    @Test
    void rejectsUnknownSourceHandle() {
        String answer = "未知来源[[source:" + SECOND_SOURCE_ID + "]]";

        assertThatThrownBy(() -> parser.parse(answer, List.of(source(FIRST_SOURCE_ID, 1))))
                .isInstanceOfSatisfying(AssistantCitationException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo(Reason.UNKNOWN_HANDLE));
    }

    @Test
    void rejectsDuplicateCitationNumbers() {
        String answer = "来源[[source:" + FIRST_SOURCE_ID + "]]";

        assertThatThrownBy(() -> parser.parse(answer,
                List.of(source(FIRST_SOURCE_ID, 1), source(SECOND_SOURCE_ID, 1))))
                .isInstanceOfSatisfying(AssistantCitationException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo(Reason.INVALID_REGISTRY));
    }

    @Test
    void identifiesMalformedHandleWithoutIncludingRawAnswer() {
        assertThatThrownBy(() -> parser.parse("敏感正文[[source:not-a-uuid]]", List.of()))
                .isInstanceOfSatisfying(AssistantCitationException.class, exception -> {
                    assertThat(exception.getReason()).isEqualTo(Reason.MALFORMED_HANDLE);
                    assertThat(exception.getMessage()).doesNotContain("敏感正文", "not-a-uuid");
                });
    }

    @Test
    void identifiesEmptyAnswer() {
        assertThatThrownBy(() -> parser.parse(" ", List.of()))
                .isInstanceOfSatisfying(AssistantCitationException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo(Reason.EMPTY_ANSWER));
    }

    private MessageSource source(UUID id, int citationNo) {
        return new MessageSource(id, UUID.randomUUID(), null, citationNo, false,
                MessageSourceTypeEnum.DOCUMENT, UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
                "{}", "{}", OffsetDateTime.now());
    }
}
