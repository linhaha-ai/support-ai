package com.haha.supportai.conversation.service.rag;

public interface ChatModelGateway {

    String generate(String systemPrompt, String userPrompt);
}
