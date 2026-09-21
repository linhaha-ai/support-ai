package com.haha.supportai.conversation.service.rag;

import java.util.ArrayList;
import java.util.List;

import com.haha.supportai.conversation.entity.dto.ChatModelChunk;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiChatModelGatewayTest {

    @Test
    void streamsChatResponseTextAndReportedUsage() {
        ChatResponse response = response("回答增量", new DefaultUsage(10, 4, 14));
        ChatModelGateway gateway = gateway(Flux.just(response));
        List<ChatModelChunk> chunks = new ArrayList<>();

        gateway.stream("系统提示", "用户提示", chunks::add);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.getContent()).isEqualTo("回答增量");
            assertThat(chunk.getUsage().getPromptTokens()).isEqualTo(10);
            assertThat(chunk.getUsage().getCompletionTokens()).isEqualTo(4);
            assertThat(chunk.getUsage().getTotalTokens()).isEqualTo(14);
        });
    }

    @Test
    void keepsUsageUnknownWhenProviderReturnsEmptyUsage() {
        ChatResponse response = response("回答增量", new EmptyUsage());
        ChatModelGateway gateway = gateway(Flux.just(response));
        List<ChatModelChunk> chunks = new ArrayList<>();

        gateway.stream("系统提示", "用户提示", chunks::add);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.getContent()).isEqualTo("回答增量");
            assertThat(chunk.getUsage()).isNull();
        });
    }

    private ChatModelGateway gateway(Flux<ChatResponse> responses) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system("系统提示")).thenReturn(requestSpec);
        when(requestSpec.user("用户提示")).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(responses);
        return new SpringAiChatModelGateway(chatClient);
    }

    private ChatResponse response(String content, Usage usage) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(usage)
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))), metadata);
    }
}
