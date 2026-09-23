package com.haha.supportai.conversation.service.assistant;

import com.haha.supportai.conversation.enums.AssistantToolEventStatusEnum;

public interface AssistantStreamObserver {

    AssistantStreamObserver NONE = new AssistantStreamObserver() {
    };

    default void onStarted() {
    }

    default void onTool(String name, AssistantToolEventStatusEnum status) {
    }

    default void onDelta(String delta) {
    }
}
