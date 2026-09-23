package com.haha.supportai.conversation.service.rag;

import java.util.function.Consumer;

import com.haha.supportai.conversation.entity.dto.ChatModelChunk;
import com.haha.supportai.conversation.entity.dto.ChatModelUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringAiChatModelGateway implements ChatModelGateway {

    private final ChatClient chatClient;

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }

    @Override
    public void stream(String systemPrompt, String userPrompt, Consumer<ChatModelChunk> chunkConsumer) {
        chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .chatResponse()
                .doOnNext(response -> chunkConsumer.accept(toChunk(response)))
                .blockLast();
    }

    private ChatModelChunk toChunk(ChatResponse response) {
        return new ChatModelChunk(content(response), usage(response));
    }

    private String content(ChatResponse response) {
        if (response == null) {
            return null;
        }
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            return null;
        }
        return generation.getOutput().getText();
    }

    private ChatModelUsage usage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null || usage instanceof EmptyUsage) {
            return null;
        }
        Integer promptTokens = usage.getPromptTokens();
        Integer completionTokens = usage.getCompletionTokens();
        Integer totalTokens = usage.getTotalTokens();
        if (promptTokens == null && completionTokens == null && totalTokens == null) {
            return null;
        }
        if (isZero(promptTokens) && isZero(completionTokens) && isZero(totalTokens)
                && usage.getNativeUsage() == null) {
            return null;
        }
        return new ChatModelUsage(promptTokens, completionTokens, totalTokens);
    }

    private boolean isZero(Integer value) {
        return value != null && value == 0;
    }
}
