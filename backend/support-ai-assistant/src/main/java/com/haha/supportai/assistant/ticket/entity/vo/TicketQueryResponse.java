package com.haha.supportai.assistant.ticket.entity.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.haha.supportai.data.assistant.entity.po.Ticket;
import com.haha.supportai.data.assistant.enums.TicketStatusEnum;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeBase;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "TicketQueryResponse", description = "当前用户可读取的模拟工单实时状态")
public class TicketQueryResponse {

    @Schema(description = "外部业务工单编号", example = "T10086", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String ticketNo; // 外部业务工单编号

    @Schema(description = "工单标题，可为空", example = "订单关闭结果未同步", nullable = true)
    private final String title; // 工单标题

    @Schema(description = "工单问题说明，可为空", nullable = true)
    private final String description; // 工单问题说明

    @Schema(description = "工单当前状态", example = "IN_PROGRESS", requiredMode = Schema.RequiredMode.REQUIRED)
    private final TicketStatusEnum status; // 工单当前状态

    @Schema(description = "当前模拟处理人，可为空", example = "支持工程师1", nullable = true)
    private final String ownerName; // 当前模拟处理人

    @Schema(description = "所属知识库标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID knowledgeBaseId; // 所属知识库标识

    @Schema(description = "所属知识库名称", example = "模拟订单系统工单",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final String knowledgeBaseName; // 所属知识库名称

    @Schema(description = "工单最近状态更新时间", format = "date-time",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime updatedAt; // 工单最近状态更新时间

    @Schema(description = "本次从业务表查询完成时间", format = "date-time",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime queriedAt; // 本次实时查询时间

    public static TicketQueryResponse from(Ticket ticket, KnowledgeBase knowledgeBase, OffsetDateTime queriedAt) {
        return new TicketQueryResponse(ticket.getTicketNo(), ticket.getTitle(), ticket.getDescription(),
                ticket.getStatus(), ticket.getOwnerName(), knowledgeBase.getId(), knowledgeBase.getName(),
                ticket.getUpdatedAt(), queriedAt);
    }
}
