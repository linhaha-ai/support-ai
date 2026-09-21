package com.haha.supportai.conversation.entity.vo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.conversation.entity.po.ChatMessage;
import com.haha.supportai.data.conversation.enums.ChatMessageRoleEnum;
import com.haha.supportai.data.conversation.enums.ChatMessageStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatMessageHistoryResponse", description = "会话历史消息")
public class ChatMessageHistoryResponse {

    @Schema(description = "消息标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID messageId; // 消息标识

    @Schema(description = "所属运行标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID runId; // 所属运行标识

    @Schema(description = "会话内递增序号", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long sequence; // 会话内递增序号

    @Schema(description = "消息角色", requiredMode = Schema.RequiredMode.REQUIRED)
    private final ChatMessageRoleEnum role; // 消息角色

    @Schema(description = "消息正文；知识范围失效时为空", nullable = true)
    private final String content; // 当前有权限时返回的消息正文

    @Schema(description = "消息状态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final ChatMessageStatusEnum status; // 消息状态

    @Schema(description = "当前授权下的实际引用来源；已删除资料保留不可用标记", requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<RagSourceResponse> sources; // 当前授权下的引用来源状态

    @Schema(description = "创建时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime createdAt; // 创建时间

    @Schema(description = "最近更新时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime updatedAt; // 最近更新时间

    public static ChatMessageHistoryResponse from(ChatMessage message, String content,
            List<RagSourceResponse> sources) {
        return new ChatMessageHistoryResponse(message.getId(), message.getRunId(), message.getSequence(),
                message.getRole(), content, message.getStatus(), List.copyOf(sources), message.getCreatedAt(),
                message.getUpdatedAt());
    }
}
