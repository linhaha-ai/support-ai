package com.haha.supportai.assistant.agent.service;

import com.haha.supportai.assistant.agent.entity.dto.AssistantAgentRunResult;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.service.assistant.AssistantStreamObserver;

public interface AssistantAgentRunner {

    AssistantAgentRunResult run(PreparedChatRunResponse preparedRun, AssistantStreamObserver observer);
}
