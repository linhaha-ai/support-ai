package com.haha.supportai.conversation.entity.vo;

import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatStreamErrorResponse", description = "error 事件数据")
public class ChatStreamErrorResponse {

    @Schema(description = "稳定且脱敏的错误码", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String code; // 稳定且脱敏的错误码

    @Schema(description = "可安全展示的错误说明", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String message; // 可安全展示的错误说明

    @Schema(description = "已提交的失败终态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final ChatRunStatusEnum status; // 已提交的失败终态

    @Schema(description = "用于排查本次请求的跟踪标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String traceId; // 用于排查本次请求的跟踪标识
}
