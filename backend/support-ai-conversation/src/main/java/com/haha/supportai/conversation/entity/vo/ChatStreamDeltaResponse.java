package com.haha.supportai.conversation.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatStreamDeltaResponse", description = "delta 事件数据")
public class ChatStreamDeltaResponse {

    @Schema(description = "已持久化的本次文本增量", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String text; // 已持久化的本次文本增量
}
