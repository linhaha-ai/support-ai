package com.haha.supportai.assistant.ticket.entity.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.haha.supportai.data.assistant.entity.po.Ticket;
import com.haha.supportai.data.assistant.enums.TicketStatusEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class TicketToolQueryResult {

    private final UUID ticketId; // 工单内部标识，仅用于服务端来源映射

    private final UUID knowledgeBaseId; // 工单所属知识库标识，仅用于服务端授权与来源映射

    private final String ticketNo; // 外部业务工单编号

    private final String title; // 工单标题，可为空

    private final String description; // 工单问题说明，可为空

    private final TicketStatusEnum status; // 工单当前状态

    private final String ownerName; // 当前模拟处理人，可为空

    private final OffsetDateTime updatedAt; // 工单最近状态更新时间

    private final OffsetDateTime queriedAt; // 本次实时查询时间

    public static TicketToolQueryResult from(Ticket ticket, OffsetDateTime queriedAt) {
        return new TicketToolQueryResult(ticket.getId(), ticket.getKnowledgeBaseId(), ticket.getTicketNo(),
                ticket.getTitle(), ticket.getDescription(), ticket.getStatus(), ticket.getOwnerName(),
                ticket.getUpdatedAt(), queriedAt);
    }
}
