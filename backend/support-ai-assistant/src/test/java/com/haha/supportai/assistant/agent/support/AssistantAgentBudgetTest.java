package com.haha.supportai.assistant.agent.support;

import com.haha.supportai.assistant.agent.exception.AssistantAgentBudgetException;
import com.haha.supportai.conversation.config.AssistantAgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantAgentBudgetTest {

    @Test
    void stopsToolCallsAtConfiguredLimit() {
        AssistantAgentProperties properties = new AssistantAgentProperties();
        properties.setMaxToolCalls(1);
        AssistantAgentBudget budget = new AssistantAgentBudget(properties);

        budget.beforeTool();

        assertThatThrownBy(budget::beforeTool)
                .isInstanceOfSatisfying(AssistantAgentBudgetException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_TOOL_CALL_LIMIT"));
        assertThat(budget.getToolCallCount()).isEqualTo(2);
    }

    @Test
    void stopsModelCallsAtConfiguredLimit() {
        AssistantAgentProperties properties = new AssistantAgentProperties();
        properties.setMaxModelCalls(1);
        AssistantAgentBudget budget = new AssistantAgentBudget(properties);

        budget.beforeModel(new Prompt("第一次调用"));

        assertThatThrownBy(() -> budget.beforeModel(new Prompt("第二次调用")))
                .isInstanceOfSatisfying(AssistantAgentBudgetException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_MODEL_CALL_LIMIT"));
        assertThat(budget.getModelCallCount()).isEqualTo(2);
    }

    @Test
    void stopsOversizedModelContext() {
        AssistantAgentProperties properties = new AssistantAgentProperties();
        properties.setMaxContextTokens(1);
        AssistantAgentBudget budget = new AssistantAgentBudget(properties);

        assertThatThrownBy(() -> budget.beforeModel(new Prompt("这是一段超过一个 Token 的上下文")))
                .isInstanceOfSatisfying(AssistantAgentBudgetException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_CONTEXT_LIMIT"));
        assertThat(budget.getMaxObservedContextTokens()).isGreaterThan(1);
    }
}
