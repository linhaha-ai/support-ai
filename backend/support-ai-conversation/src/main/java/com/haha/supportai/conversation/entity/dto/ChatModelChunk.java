package com.haha.supportai.conversation.entity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ChatModelChunk {

    private final String content; // 本次模型响应携带的文本增量

    private final ChatModelUsage usage; // 本次响应携带的模型用量，提供方未返回时为空
}
