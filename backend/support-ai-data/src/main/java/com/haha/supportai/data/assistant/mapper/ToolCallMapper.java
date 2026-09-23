package com.haha.supportai.data.assistant.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.assistant.entity.po.ToolCall;
import com.haha.supportai.data.assistant.enums.ToolCallStatusEnum;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.EnumTypeHandler;

@Mapper
public interface ToolCallMapper extends BaseMapper<ToolCall> {

    @Select("""
            /* chat_message、chat_run、chat_session、app_user：锁定当前助手消息，
               使同一运行内的工具调用序号能够按事务顺序分配。 */
            SELECT COUNT(*)
            FROM (
                SELECT assistant_message.id
                FROM chat_message assistant_message
                INNER JOIN chat_run run
                    ON run.id = assistant_message.run_id
                   AND run.session_id = assistant_message.session_id
                INNER JOIN chat_session chat ON chat.id = run.session_id
                INNER JOIN app_user account ON account.id = chat.user_id
                WHERE assistant_message.id = #{assistantMessageId,jdbcType=OTHER}
                  AND run.id = #{runId,jdbcType=OTHER}
                  AND chat.id = #{sessionId,jdbcType=OTHER}
                  AND chat.user_id = #{userId,jdbcType=OTHER}
                  AND assistant_message.role = 'ASSISTANT'
                  AND assistant_message.status = 'STREAMING'
                  AND run.status = 'RUNNING'
                  AND chat.mode = 'ASSISTANT'
                  AND chat.deleted_at IS NULL
                  AND account.enabled = TRUE
                FOR UPDATE OF assistant_message
            ) valid_context
            """)
    int lockStartContext(@Param("runId") UUID runId,
                         @Param("sessionId") UUID sessionId,
                         @Param("userId") UUID userId,
                         @Param("assistantMessageId") UUID assistantMessageId);

    @Insert("""
            /* tool_call、chat_run、chat_session、chat_message、app_user：只允许服务端确认的启用用户
               在自己的 ASSISTANT 运行中登记白名单工具，调用序号由数据库按运行分配。 */
            INSERT INTO tool_call (
                id, run_id, call_no, provider_call_id, tool_name, arguments, result_snapshot,
                status, error_code, started_at, finished_at
            )
            SELECT
                #{toolCall.id,jdbcType=OTHER}, run.id,
                COALESCE((SELECT MAX(existing.call_no) + 1 FROM tool_call existing WHERE existing.run_id = run.id), 1),
                #{toolCall.providerCallId}, #{toolCall.toolName},
                CAST(#{toolCall.arguments,jdbcType=VARCHAR} AS JSONB), NULL,
                'RUNNING', NULL, #{toolCall.startedAt}, NULL
            FROM chat_run run
            INNER JOIN chat_session chat ON chat.id = run.session_id
            INNER JOIN app_user account ON account.id = chat.user_id
            INNER JOIN chat_message assistant_message
                ON assistant_message.id = #{assistantMessageId,jdbcType=OTHER}
               AND assistant_message.run_id = run.id
               AND assistant_message.session_id = chat.id
            WHERE run.id = #{toolCall.runId,jdbcType=OTHER}
              AND chat.id = #{sessionId,jdbcType=OTHER}
              AND chat.user_id = #{userId,jdbcType=OTHER}
              AND chat.deleted_at IS NULL
              AND chat.mode = 'ASSISTANT'
              AND account.enabled = TRUE
              AND run.status = 'RUNNING'
              AND assistant_message.role = 'ASSISTANT'
              AND assistant_message.status = 'STREAMING'
            """)
    int insertStarted(@Param("toolCall") ToolCall toolCall,
                      @Param("sessionId") UUID sessionId,
                      @Param("userId") UUID userId,
                      @Param("assistantMessageId") UUID assistantMessageId);

    @Select("""
            /* chat_message：锁定服务端上下文对应的助手消息，串行分配本轮来源编号。 */
            SELECT COUNT(*)
            FROM (
                SELECT assistant_message.id
                FROM chat_message assistant_message
                INNER JOIN chat_run run
                    ON run.id = assistant_message.run_id
                   AND run.session_id = assistant_message.session_id
                INNER JOIN chat_session chat ON chat.id = run.session_id
                INNER JOIN app_user account ON account.id = chat.user_id
                INNER JOIN tool_call call ON call.run_id = run.id
                WHERE assistant_message.id = #{assistantMessageId,jdbcType=OTHER}
                  AND run.id = #{runId,jdbcType=OTHER}
                  AND chat.id = #{sessionId,jdbcType=OTHER}
                  AND chat.user_id = #{userId,jdbcType=OTHER}
                  AND call.id = #{toolCallId,jdbcType=OTHER}
                  AND assistant_message.role = 'ASSISTANT'
                  AND assistant_message.status = 'STREAMING'
                  AND run.status = 'RUNNING'
                  AND chat.mode = 'ASSISTANT'
                  AND chat.deleted_at IS NULL
                  AND account.enabled = TRUE
                  AND call.status = 'RUNNING'
                FOR UPDATE OF assistant_message
            ) valid_context
            """)
    int lockExecutionContext(@Param("runId") UUID runId,
                             @Param("sessionId") UUID sessionId,
                             @Param("userId") UUID userId,
                             @Param("assistantMessageId") UUID assistantMessageId,
                             @Param("toolCallId") UUID toolCallId);

    @Select("""
            -- message_source：在助手消息锁内计算下一个正整数来源编号。
            SELECT COALESCE(MAX(citation_no), 0) + 1
            FROM message_source
            WHERE message_id = #{assistantMessageId,jdbcType=OTHER}
            """)
    int selectNextCitationNo(@Param("assistantMessageId") UUID assistantMessageId);

    @Update("""
            -- tool_call：仅将当前上下文中的执行中调用置为成功并保存受限结果快照。
            UPDATE tool_call
            SET status = 'SUCCEEDED',
                result_snapshot = CAST(#{resultSnapshot,jdbcType=VARCHAR} AS JSONB),
                error_code = NULL,
                finished_at = #{finishedAt}
            WHERE id = #{toolCallId,jdbcType=OTHER}
              AND run_id = #{runId,jdbcType=OTHER}
              AND status = 'RUNNING'
            """)
    int completeSuccess(@Param("runId") UUID runId,
                        @Param("toolCallId") UUID toolCallId,
                        @Param("resultSnapshot") String resultSnapshot,
                        @Param("finishedAt") OffsetDateTime finishedAt);

    @Update("""
            -- tool_call：失败调用只保存稳定错误码，不持久化底层异常或未经授权的结果。
            UPDATE tool_call
            SET status = 'FAILED',
                result_snapshot = NULL,
                error_code = #{errorCode},
                finished_at = #{finishedAt}
            WHERE id = #{toolCallId,jdbcType=OTHER}
              AND run_id = #{runId,jdbcType=OTHER}
              AND status = 'RUNNING'
            """)
    int completeFailure(@Param("runId") UUID runId,
                        @Param("toolCallId") UUID toolCallId,
                        @Param("errorCode") String errorCode,
                        @Param("finishedAt") OffsetDateTime finishedAt);

    @Insert("""
            <script>
            /* message_source、tool_call 及真实来源表：成功工具结果返回模型前，按当前运行、
               ASSISTANT 会话固定范围、实时成员权和工具类型再次核对每一个来源。 */
            INSERT INTO message_source (
                id, message_id, origin_source_id, citation_no, is_cited, source_type,
                kb_id, chunk_id, ticket_id, tool_call_id, locator, snapshot, created_at
            )
            <foreach collection="sources" item="source" separator=" UNION ALL ">
            SELECT
                #{source.id,jdbcType=OTHER}, #{source.messageId,jdbcType=OTHER},
                CAST(#{source.originSourceId,jdbcType=OTHER} AS UUID), #{source.citationNo}, #{source.cited},
                #{source.sourceType,jdbcType=VARCHAR}, #{source.knowledgeBaseId,jdbcType=OTHER},
                CAST(#{source.chunkId,jdbcType=OTHER} AS UUID),
                CAST(#{source.ticketId,jdbcType=OTHER} AS UUID),
                #{source.toolCallId,jdbcType=OTHER},
                CAST(#{source.locator,jdbcType=VARCHAR} AS JSONB),
                CAST(#{source.snapshot,jdbcType=VARCHAR} AS JSONB), #{source.createdAt}
            WHERE EXISTS (
                SELECT 1
                FROM chat_message assistant_message
                INNER JOIN chat_run run
                    ON run.id = assistant_message.run_id
                   AND run.session_id = assistant_message.session_id
                INNER JOIN chat_session chat ON chat.id = run.session_id
                INNER JOIN app_user account ON account.id = chat.user_id
                INNER JOIN tool_call call
                    ON call.id = #{source.toolCallId,jdbcType=OTHER}
                   AND call.run_id = run.id
                INNER JOIN knowledge_base kb ON kb.id = #{source.knowledgeBaseId,jdbcType=OTHER}
                INNER JOIN chat_session_kb session_kb
                    ON session_kb.session_id = chat.id
                   AND session_kb.kb_id = kb.id
                INNER JOIN kb_member member
                    ON member.kb_id = kb.id
                   AND member.user_id = chat.user_id
                WHERE assistant_message.id = #{source.messageId,jdbcType=OTHER}
                  AND assistant_message.id = #{assistantMessageId,jdbcType=OTHER}
                  AND run.id = #{runId,jdbcType=OTHER}
                  AND chat.id = #{sessionId,jdbcType=OTHER}
                  AND chat.user_id = #{userId,jdbcType=OTHER}
                  AND assistant_message.role = 'ASSISTANT'
                  AND assistant_message.status = 'STREAMING'
                  AND run.status = 'RUNNING'
                  AND chat.mode = 'ASSISTANT'
                  AND chat.deleted_at IS NULL
                  AND account.enabled = TRUE
                  AND call.status = 'SUCCEEDED'
                  AND member.permission IN ('READ', 'WRITE')
                  AND kb.status = 'ACTIVE'
                  AND CAST(#{source.originSourceId,jdbcType=OTHER} AS UUID) IS NULL
                  AND (
                      (
                          #{source.sourceType,jdbcType=VARCHAR} = 'DOCUMENT'
                          AND call.tool_name = 'searchKnowledge'
                          AND EXISTS (
                              SELECT 1
                              FROM document_chunk chunk
                              INNER JOIN document_version version ON version.id = chunk.version_id
                              INNER JOIN document document ON document.id = version.document_id
                              INNER JOIN vector_store vector ON vector.id = chunk.id
                              WHERE chunk.id = #{source.chunkId,jdbcType=OTHER}
                                AND document.kb_id = kb.id
                                AND document.deleted_at IS NULL
                                AND document.active_version_id = version.id
                                AND version.status = 'READY'
                                AND LENGTH(BTRIM(vector.content)) &gt; 0
                          )
                      )
                      OR (
                          #{source.sourceType,jdbcType=VARCHAR} = 'TICKET'
                          AND call.tool_name = 'getTicketStatus'
                          AND EXISTS (
                              SELECT 1
                              FROM ticket
                              WHERE ticket.id = #{source.ticketId,jdbcType=OTHER}
                                AND ticket.kb_id = kb.id
                          )
                      )
                  )
            )
            </foreach>
            </script>
            """)
    int insertToolSources(@Param("runId") UUID runId,
                          @Param("sessionId") UUID sessionId,
                          @Param("userId") UUID userId,
                          @Param("assistantMessageId") UUID assistantMessageId,
                          @Param("sources") List<MessageSource> sources);

    @Select("""
            -- tool_call：按标识读取工具审计结果，供内部验收和后续运行展示使用。
            SELECT id, run_id, call_no, provider_call_id, tool_name, arguments, result_snapshot,
                   status, error_code, started_at, finished_at
            FROM tool_call
            WHERE id = #{toolCallId,jdbcType=OTHER}
            """)
    @Results(id = "toolCallResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "run_id", property = "runId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "call_no", property = "callNo"),
            @Result(column = "provider_call_id", property = "providerCallId"),
            @Result(column = "tool_name", property = "toolName"),
            @Result(column = "arguments", property = "arguments"),
            @Result(column = "result_snapshot", property = "resultSnapshot"),
            @Result(column = "status", property = "status", javaType = ToolCallStatusEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "error_code", property = "errorCode"),
            @Result(column = "started_at", property = "startedAt"),
            @Result(column = "finished_at", property = "finishedAt")
    })
    Optional<ToolCall> selectById(@Param("toolCallId") UUID toolCallId);
}
