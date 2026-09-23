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
import org.apache.ibatis.annotations.ResultMap;
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

    @Select("""
            /* ticket、chat_session、chat_session_kb、knowledge_base、kb_member、app_user：
               工单工具按服务端运行用户和会话固定范围实时读取，不接受模型提供身份或范围。 */
            SELECT ticket.id, ticket.ticket_no, ticket.kb_id, ticket.title, ticket.description,
                   ticket.status, ticket.owner_name, ticket.created_at, ticket.updated_at
            FROM ticket
            INNER JOIN knowledge_base kb ON kb.id = ticket.kb_id
            INNER JOIN chat_session_kb session_kb ON session_kb.kb_id = kb.id
            INNER JOIN chat_session chat ON chat.id = session_kb.session_id
            INNER JOIN kb_member member
                ON member.kb_id = kb.id
               AND member.user_id = chat.user_id
            INNER JOIN app_user account ON account.id = chat.user_id
            WHERE ticket.ticket_no = #{ticketNo}
              AND chat.id = #{sessionId,jdbcType=OTHER}
              AND chat.user_id = #{userId,jdbcType=OTHER}
              AND chat.deleted_at IS NULL
              AND chat.mode = 'ASSISTANT'
              AND account.enabled = TRUE
              AND member.permission IN ('READ', 'WRITE')
              AND kb.status = 'ACTIVE'
            """)
    @ResultMap("ticketResultMap")
    Optional<Ticket> selectReadableWithinSession(@Param("userId") UUID userId,
                                                  @Param("sessionId") UUID sessionId,
                                                  @Param("ticketNo") String ticketNo);
}
