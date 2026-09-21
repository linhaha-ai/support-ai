package com.haha.supportai.conversation.entity.vo;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatSessionPageResponse", description = "当前用户的会话游标分页")
public class ChatSessionPageResponse {

    @Schema(description = "本页会话摘要", requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<ChatSessionSummaryResponse> items; // 本页会话摘要

    @Schema(description = "下一页不透明游标；没有下一页时为空", nullable = true)
    private final String nextCursor; // 下一页不透明游标
}
