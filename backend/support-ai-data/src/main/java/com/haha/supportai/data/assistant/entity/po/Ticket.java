package com.haha.supportai.data.assistant.entity.po;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.haha.supportai.data.assistant.enums.TicketStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.EnumTypeHandler;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ticket", autoResultMap = true)
public class Ticket {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id; // 工单内部标识，由业务或种子脚本生成 UUID

    @TableField("ticket_no")
    private String ticketNo; // 外部业务工单编号

    @TableField("kb_id")
    private UUID knowledgeBaseId; // 工单所属知识库标识

    @TableField("title")
    private String title; // 工单标题，可为空

    @TableField("description")
    private String description; // 工单问题说明，可为空

    @TableField(value = "status", typeHandler = EnumTypeHandler.class)
    private TicketStatusEnum status; // 工单当前状态，按枚举名称存储

    @TableField("owner_name")
    private String ownerName; // 当前模拟处理人名称，可为空

    @TableField("created_at")
    private OffsetDateTime createdAt; // 工单创建时间

    @TableField("updated_at")
    private OffsetDateTime updatedAt; // 工单最近状态更新时间
}
