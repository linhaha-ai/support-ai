package com.haha.supportai.assistant.agent.support;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.haha.supportai.assistant.agent.entity.dto.AssistantConversationContext;
import com.haha.supportai.assistant.agent.exception.AssistantAgentBudgetException;
import org.springframework.stereotype.Component;

@Component
public class AssistantTicketFreshnessPolicy {

    private static final Pattern TICKET_NUMBER = Pattern.compile(
            "(?i)(?<![a-z0-9-])(?:TK-[a-z0-9-]{1,28}|[a-z](?=[a-z0-9-]*[0-9])[a-z0-9-]{1,31})(?![a-z0-9-])");
    private static final Pattern NAMED_TICKET = Pattern.compile(
            "(?i)(?:工单(?:编号)?|ticket\\s+(?:no\\.?|number))\\s*[:：#]?\\s*([a-z][a-z0-9-]{1,31})(?![a-z0-9-])");
    private static final Pattern CURRENT = Pattern.compile("(?i)现在|当前|最新|目前|此刻|current|latest|now");
    private static final Pattern HISTORICAL = Pattern.compile("(?i)之前|先前|当时|上次|原来|历史|previous|earlier");
    private static final Pattern STATE_CHANGE = Pattern.compile("(?i)(?:解决|完成|关闭|处理好)了?[吗么没]|是否已|仍然|还没");
    private static final Pattern FOLLOW_UP = Pattern.compile("(?i)工单|状态|进度|处理|解决|完成|它|这个|那个|怎么样|如何|status|ticket|\\bit\\b");
    private static final Pattern TICKET_WORD = Pattern.compile("(?i)工单|ticket");

    public Set<String> requiredTickets(String question, AssistantConversationContext history) {
        if (HISTORICAL.matcher(question).find() && !CURRENT.matcher(question).find()
                && !STATE_CHANGE.matcher(question).find()) {
            return Set.of();
        }
        Set<String> explicit = new LinkedHashSet<>();
        Matcher named = NAMED_TICKET.matcher(question);
        while (named.find()) {
            explicit.add(named.group(1).toUpperCase(Locale.ROOT));
        }
        Matcher matcher = TICKET_NUMBER.matcher(question);
        while (matcher.find()) {
            String number = matcher.group().toUpperCase(Locale.ROOT);
            boolean known = history.getTurns().stream().anyMatch(turn -> turn.getTicketNumbers().contains(number));
            if (number.matches("T[0-9]{4,31}|TK-[A-Z0-9-]+") || known) {
                explicit.add(number);
            }
        }
        if (!explicit.isEmpty()) {
            return Set.copyOf(explicit);
        }
        if (CURRENT.matcher(question).find() || FOLLOW_UP.matcher(question).find()) {
            for (int index = history.getTurns().size() - 1; index >= 0; index--) {
                Set<String> tickets = history.getTurns().get(index).getTicketNumbers();
                if (tickets.size() > 1) {
                    throw new AssistantAgentBudgetException("AGENT_TICKET_CONTEXT_AMBIGUOUS",
                            "历史涉及多个工单，请明确需要查询的工单编号");
                }
                if (!tickets.isEmpty()) {
                    return tickets;
                }
            }
        }
        if (CURRENT.matcher(question).find() && (TICKET_WORD.matcher(question).find()
                || question.replaceAll("[？?！!。\\s]", "").matches("现在呢|当前呢|现在|now"))) {
            throw new AssistantAgentBudgetException("AGENT_TICKET_CONTEXT_MISSING",
                    "没有可用的工单历史，请提供需要查询的工单编号");
        }
        return Set.of();
    }
}
