package com.haha.supportai.conversation.service.assistant;

import com.haha.supportai.conversation.entity.dto.AssistantAnswerResult;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;

public interface AssistantAnswerService {

    AssistantAnswerResult generateStreaming(PreparedChatRunResponse preparedRun, AssistantStreamObserver observer);
}
