package com.haha.supportai.assistant.agent.tool.entity.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.haha.supportai.data.assistant.enums.TicketStatusEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class TicketStatusToolResult {

    private final UUID sourceHandle; // 服务端生成的工单来源句柄

    private final String ticketNo; // 外部业务工单编号

    private final String title; // 工单标题，可为空

    private final String description; // 工单问题说明，可为空

    private final TicketStatusEnum status; // 工单当前状态

    private final String ownerName; // 当前模拟处理人，可为空

    private final OffsetDateTime updatedAt; // 工单最近状态更新时间

    private final OffsetDateTime queriedAt; // 本次实时查询时间

    // 与知识来源使用相同的完整引用标记，避免模型重新拼装句柄。
    public String getCitation() {
        return "[[source:" + sourceHandle + "]]";
    }
}
