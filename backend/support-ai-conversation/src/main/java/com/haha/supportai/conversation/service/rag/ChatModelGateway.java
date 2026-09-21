package com.haha.supportai.conversation.service.rag;

import java.util.function.Consumer;

import com.haha.supportai.conversation.entity.dto.ChatModelChunk;

public interface ChatModelGateway {

    void stream(String systemPrompt, String userPrompt, Consumer<ChatModelChunk> chunkConsumer);

    String generate(String systemPrompt, String userPrompt);
}
