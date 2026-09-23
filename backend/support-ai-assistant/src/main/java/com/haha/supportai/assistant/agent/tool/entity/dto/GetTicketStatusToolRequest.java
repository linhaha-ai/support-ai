package com.haha.supportai.assistant.agent.tool.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GetTicketStatusToolRequest {

    private String ticketNo; // 模型可提供的外部业务工单编号
}
