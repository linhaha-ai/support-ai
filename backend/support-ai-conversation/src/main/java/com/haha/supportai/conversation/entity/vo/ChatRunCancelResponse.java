package com.haha.supportai.conversation.entity.vo;

import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.ChatRunCancellationResult;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatRunCancelResponse", description = "问答运行取消结果")
public class ChatRunCancelResponse {

    @Schema(description = "运行标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID runId; // 运行标识

    @Schema(description = "取消竞争结束后的实际运行状态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final ChatRunStatusEnum status; // 取消竞争结束后的实际运行状态

    @Schema(description = "本次请求是否新完成取消", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean newlyCancelled; // 本次请求是否赢得终态竞争

    public static ChatRunCancelResponse from(ChatRunCancellationResult result) {
        return new ChatRunCancelResponse(result.getRunId(), result.getStatus(), result.isNewlyCancelled());
    }
}
