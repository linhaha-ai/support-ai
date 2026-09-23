package com.haha.supportai.conversation.entity.vo;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatMessagePageResponse", description = "会话历史消息游标分页")
public class ChatMessagePageResponse {

    @Schema(description = "会话标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID sessionId; // 会话标识

    @Schema(description = "当前是否仍可读取会话正文", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean contentReadable; // 当前知识范围是否仍完整可读

    @Schema(description = "本页消息", requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<ChatMessageHistoryResponse> items; // 本页消息

    @Schema(description = "下一页不透明游标；没有下一页时为空", nullable = true)
    private final String nextCursor; // 下一页不透明游标
}
