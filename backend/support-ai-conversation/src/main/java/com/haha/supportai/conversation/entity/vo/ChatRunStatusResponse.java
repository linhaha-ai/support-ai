package com.haha.supportai.conversation.entity.vo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.ChatModelUsage;
import com.haha.supportai.conversation.entity.dto.RagMetrics;
import com.haha.supportai.data.conversation.enums.ChatMessageStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatRunStatusResponse", description = "本人问答运行的持久化状态")
public class ChatRunStatusResponse {

    @Schema(description = "运行标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID runId; // 运行标识

    @Schema(description = "所属会话标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID sessionId; // 所属会话标识

    @Schema(description = "用户消息标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID userMessageId; // 用户消息标识

    @Schema(description = "助手消息标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID assistantMessageId; // 助手消息标识

    @Schema(description = "运行状态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final ChatRunStatusEnum status; // 数据库中的实际运行状态

    @Schema(description = "助手消息状态", requiredMode = Schema.RequiredMode.REQUIRED)
    private final ChatMessageStatusEnum assistantStatus; // 助手消息的持久化状态

    @Schema(description = "已持久化的部分或完整回答；当前授权失效时为空", nullable = true)
    private final String answer; // 当前仍有权读取时返回的助手正文

    @Schema(description = "当前是否仍可读取会话范围中的回答正文", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean contentReadable; // 会话固定知识范围是否仍全部可读

    @Schema(description = "当前仍有权限查看的已引用来源", requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<RagSourceResponse> sources; // 经过当前权限复核的引用来源

    @Schema(description = "模型用量；提供方未返回或运行未结束时为空", nullable = true)
    private final ChatModelUsage usage; // 已保存的模型用量

    @Schema(description = "检索与生成指标；运行尚未形成指标时为空", nullable = true)
    private final RagMetrics metrics; // 已保存的 RAG 指标

    @Schema(description = "稳定且脱敏的终态错误码", nullable = true)
    private final String errorCode; // 失败、取消或重启中断错误码

    @Schema(description = "可安全展示的终态错误说明", nullable = true)
    private final String errorMessage; // 脱敏后的终态说明

    @Schema(description = "当前运行是否仍可请求停止", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean cancellable; // QUEUED 或 RUNNING 时为 true

    @Schema(description = "取消请求持久化时间", nullable = true)
    private final OffsetDateTime cancelRequestedAt; // 取消请求持久化时间

    @Schema(description = "运行开始时间", nullable = true)
    private final OffsetDateTime startedAt; // 运行开始时间

    @Schema(description = "运行终态完成时间", nullable = true)
    private final OffsetDateTime finishedAt; // 运行终态完成时间
}
