package com.haha.supportai.conversation.entity.vo;

import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.ChatModelUsage;
import com.haha.supportai.conversation.entity.dto.RagMetrics;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatStreamDoneResponse", description = "done 事件数据")
public class ChatStreamDoneResponse {

    @Schema(description = "已完成的运行标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID runId; // 已完成的运行标识

    @Schema(description = "已提交的运行终态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final ChatRunStatusEnum status; // 已提交的运行终态

    @Schema(description = "模型用量；提供方未返回时整体为空", nullable = true)
    private final ChatModelUsage usage; // 模型用量，提供方未返回时为空

    @Schema(description = "检索与生成指标", requiredMode = Schema.RequiredMode.REQUIRED)
    private final RagMetrics metrics; // 检索与生成指标
}
