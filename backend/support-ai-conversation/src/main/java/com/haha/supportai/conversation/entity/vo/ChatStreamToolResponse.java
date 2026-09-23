package com.haha.supportai.conversation.entity.vo;

import com.haha.supportai.conversation.enums.AssistantToolEventStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatStreamToolResponse", description = "tool 事件中的白名单工具执行状态")
public class ChatStreamToolResponse {

    @Schema(description = "服务端注册的工具名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String name; // 服务端注册的白名单工具名称

    @Schema(description = "工具执行状态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final AssistantToolEventStatusEnum status; // 可公开的工具执行状态
}
