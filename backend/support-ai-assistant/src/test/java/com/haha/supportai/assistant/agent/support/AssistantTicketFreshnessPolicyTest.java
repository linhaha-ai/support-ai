package com.haha.supportai.assistant.agent.support;

import java.util.List;
import java.util.Set;

import com.haha.supportai.assistant.agent.entity.dto.AssistantConversationContext;
import com.haha.supportai.assistant.agent.entity.dto.AssistantHistoryTurn;
import com.haha.supportai.assistant.agent.exception.AssistantAgentBudgetException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantTicketFreshnessPolicyTest {

    private final AssistantTicketFreshnessPolicy policy = new AssistantTicketFreshnessPolicy();

    @Test
    void resolvesImplicitCurrentQuestionFromLatestValidTurn() {
        AssistantConversationContext history = history(Set.of("T10086"));
        assertThat(policy.requiredTickets("现在呢？", history)).containsExactly("T10086");
        assertThat(policy.requiredTickets("它解决了吗？", history)).containsExactly("T10086");
    }

    @Test
    void explicitTargetOverridesHistoryAndSupportsBusinessTicketNumbers() {
        assertThat(policy.requiredTickets("查询工单 tk-20260924-001 的当前状态", history(Set.of("T10086"))))
                .containsExactly("TK-20260924-001");
        assertThat(policy.requiredTickets("工单 ABC 当前状态", AssistantConversationContext.empty()))
                .containsExactly("ABC");
        assertThat(policy.requiredTickets("工单 T10086 报 E10086，结合处理规范解释当前状态",
                AssistantConversationContext.empty())).containsExactly("T10086");
    }

    @Test
    void historicalQuestionMayUseSnapshotButComparisonMustRefresh() {
        assertThat(policy.requiredTickets("上次工单是什么状态？", history(Set.of("T10086")))).isEmpty();
        assertThat(policy.requiredTickets("现在和上次有何区别？", history(Set.of("T10086"))))
                .containsExactly("T10086");
    }

    @Test
    void referenceToEarlierTicketDoesNotTurnResolutionQuestionIntoHistoricalQuery() {
        assertThat(policy.requiredTickets("上次那个工单解决了吗？", history(Set.of("T10086"))))
                .containsExactly("T10086");
    }

    @Test
    void ambiguousHistoryRequiresExplicitTicketNumber() {
        assertThatThrownBy(() -> policy.requiredTickets("现在呢？", history(Set.of("T10086", "T10087"))))
                .isInstanceOfSatisfying(AssistantAgentBudgetException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_TICKET_CONTEXT_AMBIGUOUS"));
    }

    @Test
    void knowledgeQuestionWithoutTicketContextDoesNotInventTarget() {
        assertThat(policy.requiredTickets("接口 E10086 如何排查？", AssistantConversationContext.empty()))
                .isEmpty();
        assertThat(policy.requiredTickets("解释限流策略", AssistantConversationContext.empty())).isEmpty();
    }

    @Test
    void missingOrInvalidHistoryRequiresClarificationForBareCurrentQuestion() {
        assertThatThrownBy(() -> policy.requiredTickets("现在呢？", AssistantConversationContext.empty()))
                .isInstanceOfSatisfying(AssistantAgentBudgetException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("AGENT_TICKET_CONTEXT_MISSING"));
    }

    private AssistantConversationContext history(Set<String> tickets) {
        return new AssistantConversationContext(List.of(new AssistantHistoryTurn("先前问题", "先前结果", tickets)),
                List.of());
    }
}
