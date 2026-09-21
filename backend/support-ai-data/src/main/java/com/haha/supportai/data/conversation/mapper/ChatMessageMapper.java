package com.haha.supportai.data.conversation.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.conversation.entity.po.ChatMessage;
import com.haha.supportai.data.conversation.enums.ChatMessageRoleEnum;
import com.haha.supportai.data.conversation.enums.ChatMessageStatusEnum;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.EnumTypeHandler;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    @Select("""
            -- chat_message：按会话消息顺序读取某个运行的用户和助手消息。
            SELECT id, run_id, session_id, seq, role, content, status, created_at, updated_at
            FROM chat_message
            WHERE run_id = #{runId,jdbcType=OTHER}
            ORDER BY seq
            """)
    @Results(id = "chatMessageResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "run_id", property = "runId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "session_id", property = "sessionId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "seq", property = "sequence"),
            @Result(column = "role", property = "role", javaType = ChatMessageRoleEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "content", property = "content"),
            @Result(column = "status", property = "status", javaType = ChatMessageStatusEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<ChatMessage> selectByRunId(@Param("runId") UUID runId);

    @Update("""
            -- chat_message、chat_run：仅为执行中的运行原子追加非空助手增量，终态消息不得再写入。
            UPDATE chat_message message
            SET content = message.content || #{delta,jdbcType=VARCHAR},
                updated_at = #{updatedAt}
            WHERE message.id = #{messageId,jdbcType=OTHER}
              AND message.run_id = #{runId,jdbcType=OTHER}
              AND message.role = 'ASSISTANT'
              AND message.status = 'STREAMING'
              AND #{delta,jdbcType=VARCHAR} IS NOT NULL
              AND LENGTH(#{delta,jdbcType=VARCHAR}) > 0
              AND EXISTS (
                  SELECT 1
                  FROM chat_run run
                  WHERE run.id = message.run_id
                    AND run.status = 'RUNNING'
              )
            """)
    int appendAssistantContent(@Param("runId") UUID runId,
                               @Param("messageId") UUID messageId,
                               @Param("delta") String delta,
                               @Param("updatedAt") OffsetDateTime updatedAt);

    @Update("""
            -- chat_message：仅完成仍在生成中的助手消息，空白答案不得进入完成状态。
            UPDATE chat_message
            SET content = #{content},
                status = 'COMPLETED',
                updated_at = #{completedAt}
            WHERE id = #{messageId,jdbcType=OTHER}
              AND run_id = #{runId,jdbcType=OTHER}
              AND role = 'ASSISTANT'
              AND status = 'STREAMING'
              AND LENGTH(BTRIM(#{content})) > 0
            """)
    int completeAssistant(@Param("runId") UUID runId,
                          @Param("messageId") UUID messageId,
                          @Param("content") String content,
                          @Param("completedAt") OffsetDateTime completedAt);

    @Update("""
            -- chat_message、chat_run：原子保存显式部分正文并失败仍在生成中的助手消息。
            UPDATE chat_message message
            SET content = COALESCE(#{partialContent,jdbcType=VARCHAR}, message.content),
                status = 'FAILED',
                updated_at = #{failedAt}
            WHERE message.id = #{messageId,jdbcType=OTHER}
              AND message.run_id = #{runId,jdbcType=OTHER}
              AND message.role = 'ASSISTANT'
              AND message.status = 'STREAMING'
              AND EXISTS (
                  SELECT 1
                  FROM chat_run run
                  WHERE run.id = message.run_id
                    AND run.status IN ('QUEUED', 'RUNNING')
              )
            """)
    int failAssistant(@Param("runId") UUID runId,
                      @Param("messageId") UUID messageId,
                      @Param("partialContent") String partialContent,
                      @Param("failedAt") OffsetDateTime failedAt);
}
