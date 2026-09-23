package com.haha.supportai.conversation.entity.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesAgentFieldsAcrossPersistenceRoundTrip() throws Exception {
        AgentMetrics metrics = new AgentMetrics(2, 1, false, 120, 40L,
                3, 2, 640, 2, 3);

        AgentMetrics restored = objectMapper.readValue(objectMapper.writeValueAsString(metrics), AgentMetrics.class);

        assertThat(restored.getEvidenceCount()).isEqualTo(2);
        assertThat(restored.getCitedCount()).isEqualTo(1);
        assertThat(restored.getModelCallCount()).isEqualTo(3);
        assertThat(restored.getToolCallCount()).isEqualTo(2);
        assertThat(restored.getMaxObservedContextTokens()).isEqualTo(640);
        assertThat(restored.withoutQueryRewriteText()).isSameAs(restored);
    }
}
