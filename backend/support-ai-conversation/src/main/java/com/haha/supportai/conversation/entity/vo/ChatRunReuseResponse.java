package com.haha.supportai.conversation.entity.vo;

import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatRunReuseResponse", description = "命中消息提交幂等键时返回的已有运行")
public class ChatRunReuseResponse {

    @Schema(description = "已存在的运行标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID runId; // 已存在的运行标识

    @Schema(description = "已存在运行的当前状态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final ChatRunStatusEnum status; // 已存在运行的当前状态

    @Schema(description = "固定为 true，表示未启动新的模型调用", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean reused; // 固定表示本次命中幂等请求

    public static ChatRunReuseResponse from(PreparedChatRunResponse preparedRun) {
        if (preparedRun == null || !preparedRun.isReused()) {
            throw new IllegalArgumentException("仅幂等复用结果可转换为复用响应");
        }
        return new ChatRunReuseResponse(preparedRun.getRunId(), preparedRun.getStatus(), true);
    }
}
