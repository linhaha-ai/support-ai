package com.haha.supportai.conversation.service.rag;

import java.util.function.Consumer;

import com.haha.supportai.conversation.entity.dto.ChatModelChunk;

public interface ChatModelGateway {

    void stream(String systemPrompt, String userPrompt, Consumer<ChatModelChunk> chunkConsumer);

    default String generate(String systemPrompt, String userPrompt) {
        StringBuilder content = new StringBuilder();
        stream(systemPrompt, userPrompt, chunk -> {
            if (chunk.getContent() != null) {
                content.append(chunk.getContent());
            }
        });
        return content.toString();
    }
}
