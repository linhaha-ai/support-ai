package com.haha.supportai.conversation.entity.vo;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatStreamStartResponse", description = "start 事件数据")
public class ChatStreamStartResponse {

    @Schema(description = "已进入执行状态的运行标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID runId; // 已进入执行状态的运行标识

    @Schema(description = "接收增量的助手消息标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID assistantMessageId; // 接收增量的助手消息标识
}
