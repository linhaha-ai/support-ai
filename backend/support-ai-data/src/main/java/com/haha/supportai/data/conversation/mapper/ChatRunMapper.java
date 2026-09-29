package com.haha.supportai.data.conversation.mapper;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.conversation.entity.po.ChatRun;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.EnumTypeHandler;

@Mapper
public interface ChatRunMapper extends BaseMapper<ChatRun> {

    @Insert("""
            -- chat_run：显式转换 JSONB 快照和指标，保存请求幂等记录。
            INSERT INTO chat_run (
                id, session_id, client_request_id, request_hash, status, config_snapshot, usage, metrics,
                error_code, error_detail, cancel_requested_at, started_at, finished_at, created_at, updated_at,
                trace_id
            ) VALUES (
                #{run.id,jdbcType=OTHER}, #{run.sessionId,jdbcType=OTHER}, #{run.clientRequestId,jdbcType=OTHER},
                #{run.requestHash}, #{run.status,jdbcType=VARCHAR},
                CAST(#{run.configSnapshot,jdbcType=VARCHAR} AS JSONB),
                CAST(#{run.usage,jdbcType=VARCHAR} AS JSONB),
                CAST(#{run.metrics,jdbcType=VARCHAR} AS JSONB),
                #{run.errorCode}, #{run.errorDetail}, #{run.cancelRequestedAt}, #{run.startedAt},
                #{run.finishedAt}, #{run.createdAt}, #{run.updatedAt}, #{run.traceId}
            )
            """)
    int insertChatRun(@Param("run") ChatRun run);

    @Select("""
            -- chat_run：按照会话内客户端幂等键读取既有运行。
            SELECT id, session_id, client_request_id, request_hash, status, config_snapshot, usage, metrics,
                   error_code, error_detail, cancel_requested_at, started_at, finished_at, created_at, updated_at,
                   trace_id
            FROM chat_run
            WHERE session_id = #{sessionId,jdbcType=OTHER}
              AND client_request_id = #{clientRequestId,jdbcType=OTHER}
            """)
    @Results(id = "chatRunResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "session_id", property = "sessionId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "client_request_id", property = "clientRequestId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "request_hash", property = "requestHash"),
            @Result(column = "status", property = "status", javaType = ChatRunStatusEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "config_snapshot", property = "configSnapshot"),
            @Result(column = "usage", property = "usage"),
            @Result(column = "metrics", property = "metrics"),
            @Result(column = "error_code", property = "errorCode"),
            @Result(column = "error_detail", property = "errorDetail"),
            @Result(column = "cancel_requested_at", property = "cancelRequestedAt"),
            @Result(column = "started_at", property = "startedAt"),
            @Result(column = "finished_at", property = "finishedAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "trace_id", property = "traceId")
    })
    Optional<ChatRun> selectBySessionIdAndClientRequestId(@Param("sessionId") UUID sessionId,
                                                          @Param("clientRequestId") UUID clientRequestId);

    @Select("""
            -- chat_run：按运行标识读取持久化状态，供应用内部协调恢复和执行。
            SELECT id, session_id, client_request_id, request_hash, status, config_snapshot, usage, metrics,
                   error_code, error_detail, cancel_requested_at, started_at, finished_at, created_at, updated_at,
                   trace_id
            FROM chat_run
            WHERE id = #{runId,jdbcType=OTHER}
            """)
    @ResultMap("chatRunResultMap")
    Optional<ChatRun> selectRunById(@Param("runId") UUID runId);

    @Select("""
            -- chat_run、chat_session：仅读取当前用户拥有且未删除会话中的运行，避免泄露已删除会话内容。
            SELECT run.id, run.session_id, run.client_request_id, run.request_hash, run.status,
                   run.config_snapshot, run.usage, run.metrics, run.error_code, run.error_detail,
                   run.cancel_requested_at, run.started_at, run.finished_at, run.created_at, run.updated_at,
                   run.trace_id
            FROM chat_run run
            INNER JOIN chat_session session ON session.id = run.session_id
            WHERE run.id = #{runId,jdbcType=OTHER}
              AND session.user_id = #{userId,jdbcType=OTHER}
              AND session.deleted_at IS NULL
            """)
    @ResultMap("chatRunResultMap")
    Optional<ChatRun> selectOwnedByIdAndUserId(@Param("runId") UUID runId, @Param("userId") UUID userId);

    @Select("""
            -- chat_run、chat_session：锁定当前用户拥有且未删除会话中的运行，串行化取消与终态写入。
            SELECT run.id, run.session_id, run.client_request_id, run.request_hash, run.status,
                   run.config_snapshot, run.usage, run.metrics, run.error_code, run.error_detail,
                   run.cancel_requested_at, run.started_at, run.finished_at, run.created_at, run.updated_at,
                   run.trace_id
            FROM chat_run run
            INNER JOIN chat_session session ON session.id = run.session_id
            WHERE run.id = #{runId,jdbcType=OTHER}
              AND session.user_id = #{userId,jdbcType=OTHER}
              AND session.deleted_at IS NULL
            FOR UPDATE OF run
            """)
    @ResultMap("chatRunResultMap")
    Optional<ChatRun> selectOwnedByIdAndUserIdForUpdate(@Param("runId") UUID runId,
                                                         @Param("userId") UUID userId);

    @Select("""
            -- chat_run：读取会话中尚未结束的运行，应用层在会话锁内据此拒绝并发提问。
            SELECT id, session_id, client_request_id, request_hash, status, config_snapshot, usage, metrics,
                   error_code, error_detail, cancel_requested_at, started_at, finished_at, created_at, updated_at,
                   trace_id
            FROM chat_run
            WHERE session_id = #{sessionId,jdbcType=OTHER}
              AND status IN ('QUEUED', 'RUNNING')
            """)
    @ResultMap("chatRunResultMap")
    Optional<ChatRun> selectActiveBySessionId(@Param("sessionId") UUID sessionId);

    @Select("""
            -- chat_run、chat_session：统计同一用户跨会话尚未结束的运行，配合账号行锁执行配额判断。
            SELECT COUNT(*)
            FROM chat_run run
            INNER JOIN chat_session session ON session.id = run.session_id
            WHERE session.user_id = #{userId,jdbcType=OTHER}
              AND session.deleted_at IS NULL
              AND run.status IN ('QUEUED', 'RUNNING')
            """)
    long countActiveByUserId(@Param("userId") UUID userId);

    @Update("""
            -- chat_run、chat_message：仅允许由同一会话、运行和已持久化问题组成的完整消息对领取运行。
            UPDATE chat_run run
            SET status = 'RUNNING',
                usage = NULL,
                error_code = NULL,
                error_detail = NULL,
                started_at = #{startedAt},
                finished_at = NULL,
                updated_at = #{startedAt}
            WHERE run.id = #{runId,jdbcType=OTHER}
              AND run.session_id = #{sessionId,jdbcType=OTHER}
              AND run.status = 'QUEUED'
              AND EXISTS (
                  SELECT 1
                  FROM chat_message user_message
                  INNER JOIN chat_message assistant_message
                      ON assistant_message.run_id = user_message.run_id
                     AND assistant_message.session_id = user_message.session_id
                  WHERE user_message.id = #{userMessageId,jdbcType=OTHER}
                    AND user_message.run_id = run.id
                    AND user_message.session_id = run.session_id
                    AND user_message.role = 'USER'
                    AND user_message.status = 'COMPLETED'
                    AND user_message.content = #{question}
                    AND assistant_message.id = #{assistantMessageId,jdbcType=OTHER}
                    AND assistant_message.role = 'ASSISTANT'
                    AND assistant_message.status = 'STREAMING'
              )
            """)
    int markRunning(@Param("runId") UUID runId,
                    @Param("sessionId") UUID sessionId,
                    @Param("userMessageId") UUID userMessageId,
                    @Param("assistantMessageId") UUID assistantMessageId,
                    @Param("question") String question,
                    @Param("startedAt") OffsetDateTime startedAt);

    @Update("""
            -- chat_run：仅将执行中的运行置为完成，并保存模型用量和执行指标 JSON。
            UPDATE chat_run
            SET status = 'COMPLETED',
                usage = CAST(#{usage,jdbcType=VARCHAR} AS JSONB),
                metrics = CAST(#{metrics,jdbcType=VARCHAR} AS JSONB),
                error_code = NULL,
                error_detail = NULL,
                finished_at = #{finishedAt},
                updated_at = #{finishedAt}
            WHERE id = #{runId,jdbcType=OTHER}
              AND status = 'RUNNING'
            """)
    int complete(@Param("runId") UUID runId,
                 @Param("usage") String usage,
                 @Param("metrics") String metrics,
                 @Param("finishedAt") OffsetDateTime finishedAt);

    @Update("""
            -- chat_run：排队或执行中的运行失败时写入脱敏错误、可用模型用量和可选执行指标。
            UPDATE chat_run
            SET status = 'FAILED',
                usage = CAST(#{usage,jdbcType=VARCHAR} AS JSONB),
                metrics = COALESCE(CAST(#{metrics,jdbcType=VARCHAR} AS JSONB), metrics),
                error_code = #{errorCode},
                error_detail = #{errorDetail},
                finished_at = #{finishedAt},
                updated_at = #{finishedAt}
            WHERE id = #{runId,jdbcType=OTHER}
              AND status IN ('QUEUED', 'RUNNING')
            """)
    int fail(@Param("runId") UUID runId,
             @Param("errorCode") String errorCode,
             @Param("errorDetail") String errorDetail,
             @Param("usage") String usage,
             @Param("metrics") String metrics,
             @Param("finishedAt") OffsetDateTime finishedAt);

    @Update("""
            -- chat_run：仅将排队或执行中的运行取消，终态记录不可被覆盖，错误详情固定为脱敏文案。
            UPDATE chat_run
            SET status = 'CANCELLED',
                error_code = 'RUN_CANCELLED',
                error_detail = '用户已取消生成',
                cancel_requested_at = #{cancelledAt},
                finished_at = #{cancelledAt},
                updated_at = #{cancelledAt}
            WHERE id = #{runId,jdbcType=OTHER}
              AND status IN ('QUEUED', 'RUNNING')
            """)
    int cancel(@Param("runId") UUID runId, @Param("cancelledAt") OffsetDateTime cancelledAt);

    @Update("""
            -- chat_run：启动恢复时仅终止本次启动边界前遗留的活跃运行，不自动重新提交模型调用。
            UPDATE chat_run
            SET status = 'FAILED',
                error_code = 'RUN_INTERRUPTED',
                error_detail = '应用重启导致运行中断',
                finished_at = #{recoveredAt},
                updated_at = #{recoveredAt}
            WHERE status IN ('QUEUED', 'RUNNING')
              AND created_at < #{startupBoundary}
            """)
    int interruptActiveBefore(@Param("startupBoundary") OffsetDateTime startupBoundary,
                              @Param("recoveredAt") OffsetDateTime recoveredAt);
}
