package com.haha.supportai.conversation.service.rag;

public interface RagStreamObserver {

    RagStreamObserver NONE = new RagStreamObserver() {
    };

    default void onStarted() {
    }

    default void onDelta(String delta) {
    }
}
