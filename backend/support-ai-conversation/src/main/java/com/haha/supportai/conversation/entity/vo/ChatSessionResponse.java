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
@Schema(name = "ChatSessionResponse", description = "固定知识范围的 RAG 会话")
public class ChatSessionResponse {

    @Schema(description = "会话标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID sessionId; // 会话标识

    @Schema(description = "会话展示标题", example = "新会话", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String title; // 会话展示标题

    @Schema(description = "会话模式", example = "RAG", requiredMode = Schema.RequiredMode.REQUIRED)
    private final ChatSessionModeEnum mode; // 会话模式

    @Schema(description = "创建后不可由消息参数扩展的知识库范围", requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<UUID> knowledgeBaseIds; // 固定知识库范围

    @Schema(description = "创建时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime createdAt; // 创建时间

    @Schema(description = "最近更新时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime updatedAt; // 最近更新时间

    public static ChatSessionResponse from(ChatSession session, List<UUID> knowledgeBaseIds) {
        return new ChatSessionResponse(session.getId(), session.getTitle(), session.getMode(), List.copyOf(knowledgeBaseIds),
                session.getCreatedAt(), session.getUpdatedAt());
    }
}
