package com.haha.supportai.conversation.entity.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(name = "RagSourceResponse", description = "流式回答实际引用的当前授权来源状态")
public class RagSourceResponse {

    @Schema(description = "回答中的引用编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int number; // 回答中的引用编号

    @Schema(description = "来源类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private final MessageSourceTypeEnum type; // 当前来源类型

    @Schema(description = "文档标识；工单来源为空", nullable = true)
    private final UUID documentId; // 文档标识

    @Schema(description = "文档版本标识；工单来源为空", nullable = true)
    private final UUID versionId; // 文档版本标识

    @Schema(description = "分块标识；工单来源为空", nullable = true)
    private final UUID chunkId; // 点击来源时使用的分块标识

    @Schema(description = "文档展示标题", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String title; // 文档展示标题

    @Schema(description = "来源章节", nullable = true)
    private final String section; // 来源章节

    @Schema(name = "isCurrent", description = "文档是否为当前版本；工单是否为该回答本轮直接查询，不代表展示时实时状态",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean current; // 文档当前版本或工单本轮直接查询标记

    @Schema(description = "当前是否仍可预览来源正文", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean available; // 是否仍可预览正文

    @Schema(description = "按当前身份重新鉴权的来源预览路径；不可用来源为 null", nullable = true)
    private final String previewPath; // 点击来源时重新鉴权的预览路径

    @Schema(description = "工单编号；文档来源为空", nullable = true)
    private final String ticketNo; // 工单来源的外部业务编号

    @Schema(description = "生成时查询到的工单状态；文档来源为空", nullable = true)
    private final String ticketStatus; // 生成时查询到的工单状态

    @Schema(description = "生成时查询到的工单更新时间；文档来源为空", nullable = true)
    private final OffsetDateTime ticketUpdatedAt; // 生成时查询到的工单更新时间

    @Schema(description = "原工具实际查询工单的时间；继承时保持原值", nullable = true)
    private final OffsetDateTime ticketQueriedAt; // 工单快照的真实查询时刻

    @Schema(description = "历史继承的直接根来源标识；直接来源为空", nullable = true)
    private final UUID originSourceId; // 可追溯的历史根来源标识

    @Schema(description = "真实工具调用标识；继承时指向历史调用，普通 RAG 来源为空", nullable = true)
    private final UUID toolCallId; // 原始工具调用审计标识

    public RagSourceResponse(int number, MessageSourceTypeEnum type, UUID documentId, UUID versionId, UUID chunkId,
            String title, String section, boolean current, boolean available, String previewPath) {
        this(number, type, documentId, versionId, chunkId, title, section, current, available, previewPath,
                null, null, null);
    }

    public RagSourceResponse(int number, MessageSourceTypeEnum type, UUID documentId, UUID versionId, UUID chunkId,
            String title, String section, boolean current, boolean available, String previewPath, String ticketNo,
            String ticketStatus, OffsetDateTime ticketUpdatedAt) {
        this(number, type, documentId, versionId, chunkId, title, section, current, available, previewPath,
                ticketNo, ticketStatus, ticketUpdatedAt, null, null, null);
    }

    public RagSourceResponse(int number, MessageSourceTypeEnum type, UUID documentId, UUID versionId, UUID chunkId,
            String title, String section, boolean current, boolean available, String previewPath, String ticketNo,
            String ticketStatus, OffsetDateTime ticketUpdatedAt, OffsetDateTime ticketQueriedAt,
            UUID originSourceId, UUID toolCallId) {
        this.number = number;
        this.type = type;
        this.documentId = documentId;
        this.versionId = versionId;
        this.chunkId = chunkId;
        this.title = title;
        this.section = section;
        this.current = current;
        this.available = available;
        this.previewPath = previewPath;
        this.ticketNo = ticketNo;
        this.ticketStatus = ticketStatus;
        this.ticketUpdatedAt = ticketUpdatedAt;
        this.ticketQueriedAt = ticketQueriedAt;
        this.originSourceId = originSourceId;
        this.toolCallId = toolCallId;
    }

    @Schema(description = "是否从历史完整轮次继承", requiredMode = Schema.RequiredMode.REQUIRED)
    public boolean isInherited() {
        return originSourceId != null;
    }

    @JsonProperty("isCurrent")
    public boolean isCurrent() {
        return current;
    }
}
