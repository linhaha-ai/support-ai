package com.haha.supportai.data.conversation.mapper;

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
import org.apache.ibatis.type.EnumTypeHandler;

@Mapper
public interface ChatRunMapper extends BaseMapper<ChatRun> {

    @Insert("""
            -- chat_run：显式转换 JSONB 快照和指标，保存请求幂等记录。
            INSERT INTO chat_run (
                id, session_id, client_request_id, request_hash, status, config_snapshot, usage, metrics,
                error_code, error_detail, cancel_requested_at, started_at, finished_at, created_at, updated_at
            ) VALUES (
                #{run.id,jdbcType=OTHER}, #{run.sessionId,jdbcType=OTHER}, #{run.clientRequestId,jdbcType=OTHER},
                #{run.requestHash}, #{run.status,jdbcType=VARCHAR},
                CAST(#{run.configSnapshot,jdbcType=VARCHAR} AS JSONB),
                CAST(#{run.usage,jdbcType=VARCHAR} AS JSONB),
                CAST(#{run.metrics,jdbcType=VARCHAR} AS JSONB),
                #{run.errorCode}, #{run.errorDetail}, #{run.cancelRequestedAt}, #{run.startedAt},
                #{run.finishedAt}, #{run.createdAt}, #{run.updatedAt}
            )
            """)
    int insertChatRun(@Param("run") ChatRun run);

    @Select("""
            -- chat_run：按照会话内客户端幂等键读取既有运行。
            SELECT id, session_id, client_request_id, request_hash, status, config_snapshot, usage, metrics,
                   error_code, error_detail, cancel_requested_at, started_at, finished_at, created_at, updated_at
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
            @Result(column = "updated_at", property = "updatedAt")
    })
    Optional<ChatRun> selectBySessionIdAndClientRequestId(@Param("sessionId") UUID sessionId,
                                                          @Param("clientRequestId") UUID clientRequestId);

    @Select("""
            -- chat_run：读取会话中尚未结束的运行，应用层在会话锁内据此拒绝并发提问。
            SELECT id, session_id, client_request_id, request_hash, status, config_snapshot, usage, metrics,
                   error_code, error_detail, cancel_requested_at, started_at, finished_at, created_at, updated_at
            FROM chat_run
            WHERE session_id = #{sessionId,jdbcType=OTHER}
              AND status IN ('QUEUED', 'RUNNING')
            """)
    @ResultMap("chatRunResultMap")
    Optional<ChatRun> selectActiveBySessionId(@Param("sessionId") UUID sessionId);
}
