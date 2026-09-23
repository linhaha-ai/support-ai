package com.haha.supportai.data.conversation.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.conversation.entity.po.ChatSession;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.EnumTypeHandler;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    @Select("""
            <script>
            -- chat_session：按更新时间和会话标识倒序读取当前用户的未删除会话，游标条件保证翻页稳定。
            SELECT id, user_id, title, mode, next_message_seq, deleted_at, created_at, updated_at
            FROM chat_session
            WHERE user_id = #{userId,jdbcType=OTHER}
              AND deleted_at IS NULL
            <if test="beforeUpdatedAt != null and beforeId != null">
              AND (updated_at &lt; #{beforeUpdatedAt}
                   OR (updated_at = #{beforeUpdatedAt} AND id &lt; #{beforeId,jdbcType=OTHER}))
            </if>
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    @Results(id = "chatSessionResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "user_id", property = "userId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "title", property = "title"),
            @Result(column = "mode", property = "mode", javaType = ChatSessionModeEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "next_message_seq", property = "nextMessageSeq"),
            @Result(column = "deleted_at", property = "deletedAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<ChatSession> selectOwnedPage(@Param("userId") UUID userId,
                                      @Param("beforeUpdatedAt") OffsetDateTime beforeUpdatedAt,
                                      @Param("beforeId") UUID beforeId,
                                      @Param("limit") int limit);

    @Select("""
            -- chat_session：锁定当前用户可访问的未删除会话，串行化同一会话内的消息序号和运行创建。
            SELECT id, user_id, title, mode, next_message_seq, deleted_at, created_at, updated_at
            FROM chat_session
            WHERE id = #{sessionId,jdbcType=OTHER}
              AND user_id = #{userId,jdbcType=OTHER}
              AND deleted_at IS NULL
            FOR UPDATE
            """)
    @ResultMap("chatSessionResultMap")
    Optional<ChatSession> selectOwnedByIdForUpdate(@Param("sessionId") UUID sessionId, @Param("userId") UUID userId);

    @Select("""
            -- chat_session：读取当前用户的未删除会话，未知会话与其他用户会话返回相同结果。
            SELECT id, user_id, title, mode, next_message_seq, deleted_at, created_at, updated_at
            FROM chat_session
            WHERE id = #{sessionId,jdbcType=OTHER}
              AND user_id = #{userId,jdbcType=OTHER}
              AND deleted_at IS NULL
            """)
    @ResultMap("chatSessionResultMap")
    Optional<ChatSession> selectOwnedById(@Param("sessionId") UUID sessionId, @Param("userId") UUID userId);

    @Update("""
            -- chat_session：仅更新已锁定且仍未删除的本人会话标题。
            UPDATE chat_session
            SET title = #{title},
                updated_at = #{updatedAt}
            WHERE id = #{sessionId,jdbcType=OTHER}
              AND user_id = #{userId,jdbcType=OTHER}
              AND deleted_at IS NULL
            """)
    int updateOwnedTitle(@Param("sessionId") UUID sessionId,
                         @Param("userId") UUID userId,
                         @Param("title") String title,
                         @Param("updatedAt") OffsetDateTime updatedAt);

    @Update("""
            -- chat_session：仅对已锁定且仍未删除的本人会话写入逻辑删除时间。
            UPDATE chat_session
            SET deleted_at = #{deletedAt},
                updated_at = #{deletedAt}
            WHERE id = #{sessionId,jdbcType=OTHER}
              AND user_id = #{userId,jdbcType=OTHER}
              AND deleted_at IS NULL
            """)
    int softDeleteOwned(@Param("sessionId") UUID sessionId,
                        @Param("userId") UUID userId,
                        @Param("deletedAt") OffsetDateTime deletedAt);

    @Update("""
            -- chat_session：仅在当前消息序号未变化时预留一问一答两个序号。
            UPDATE chat_session
            SET next_message_seq = next_message_seq + 2,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{sessionId,jdbcType=OTHER}
              AND next_message_seq = #{expectedNextMessageSeq}
              AND deleted_at IS NULL
            """)
    int advanceNextMessageSequence(@Param("sessionId") UUID sessionId,
                                   @Param("expectedNextMessageSeq") long expectedNextMessageSeq);
}
