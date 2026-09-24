package com.haha.supportai.assistant.agent.service;

import java.util.Set;

import com.haha.supportai.assistant.agent.entity.dto.AssistantAgentRunResult;
import com.haha.supportai.assistant.agent.entity.dto.AssistantConversationContext;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.service.assistant.AssistantStreamObserver;

public interface AssistantAgentRunner {

    AssistantAgentRunResult run(PreparedChatRunResponse preparedRun, AssistantConversationContext history,
            Set<String> requiredTickets, AssistantStreamObserver observer);
}
