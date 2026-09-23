package com.haha.supportai.conversation.entity.vo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.conversation.entity.po.ChatSession;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatSessionSummaryResponse", description = "当前用户的会话摘要")
public class ChatSessionSummaryResponse {

    @Schema(description = "会话标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID sessionId; // 会话标识

    @Schema(description = "会话展示标题", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String title; // 会话展示标题

    @Schema(description = "会话模式", requiredMode = Schema.RequiredMode.REQUIRED)
    private final ChatSessionModeEnum mode; // 会话模式

    @Schema(description = "创建时固定的知识库范围", requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<UUID> knowledgeBaseIds; // 固定知识库范围

    @Schema(description = "当前是否仍可读取会话内容", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean contentReadable; // 当前知识范围是否仍完整可读

    @Schema(description = "创建时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime createdAt; // 创建时间

    @Schema(description = "最近更新时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime updatedAt; // 最近更新时间

    public static ChatSessionSummaryResponse from(ChatSession session, List<UUID> knowledgeBaseIds,
            boolean contentReadable) {
        return new ChatSessionSummaryResponse(session.getId(), session.getTitle(), session.getMode(),
                List.copyOf(knowledgeBaseIds), contentReadable, session.getCreatedAt(), session.getUpdatedAt());
    }
}
