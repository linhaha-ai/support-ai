package com.haha.supportai.data.assistant.mapper;

import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.assistant.entity.po.Ticket;
import com.haha.supportai.data.assistant.enums.TicketStatusEnum;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.EnumTypeHandler;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {

    @Select("""
            -- ticket：按外部唯一编号读取当前工单状态；成员授权由业务服务统一复核。
            SELECT id, ticket_no, kb_id, title, description, status, owner_name, created_at, updated_at
            FROM ticket
            WHERE ticket_no = #{ticketNo}
            """)
    @Results(id = "ticketResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "ticket_no", property = "ticketNo"),
            @Result(column = "kb_id", property = "knowledgeBaseId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "title", property = "title"),
            @Result(column = "description", property = "description"),
            @Result(column = "status", property = "status", javaType = TicketStatusEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "owner_name", property = "ownerName"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    Optional<Ticket> selectByTicketNo(@Param("ticketNo") String ticketNo);
}
