package com.haha.supportai.assistant.agent.support;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.assistant.agent.entity.dto.AssistantConversationContext;
import com.haha.supportai.assistant.agent.entity.dto.AssistantHistoryTurn;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantPromptFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AssistantPromptFactory factory = new AssistantPromptFactory(mapper);

    @Test
    void historyProvidesCitationForCurrentRegisteredHandleAndRetainsFreshLookupRequirement() throws Exception {
        MessageSource source = new MessageSource();
        source.setId(UUID.randomUUID());
        source.setOriginSourceId(UUID.randomUUID());
        source.setSourceType(MessageSourceTypeEnum.TICKET);
        source.setSnapshot("{\"ticketNo\":\"T10086\",\"status\":\"OPEN\"}");
        var history = new AssistantConversationContext(List.of(
                new AssistantHistoryTurn("工单状态？", "先前待处理", Set.of("T10086"))), List.of(source));

        var messages = factory.create("现在呢？", history, Set.of("T10086"));

        assertThat(messages).hasSize(2);
        String historyMessage = messages.get(0).getText();
        var serializedSource = mapper.readTree(historyMessage.substring(historyMessage.indexOf('\n') + 1))
                .path("sources").get(0);
        assertThat(serializedSource.path("sourceHandle").asText()).isEqualTo(source.getId().toString());
        assertThat(serializedSource.path("citation").asText()).isEqualTo("[[source:" + source.getId() + "]]");
        assertThat(historyMessage).contains("原样复制", "先前查询结果", "不能充当当前状态")
                .doesNotContain(source.getOriginSourceId().toString());
        assertThat(messages.get(1).getText()).contains("现在呢？", "T10086", "必须调用 getTicketStatus");
    }
}
