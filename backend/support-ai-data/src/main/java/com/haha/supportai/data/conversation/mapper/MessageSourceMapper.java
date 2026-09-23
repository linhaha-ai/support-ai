package com.haha.supportai.data.conversation.mapper;

import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.conversation.entity.po.HistoricalMessageSource;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
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
public interface MessageSourceMapper extends BaseMapper<MessageSource> {

    @Insert("""
            <script>
            /* message_source：模型调用前按消息所属会话、当前成员关系及 active READY 版本复核来源归属；
               任一来源失效时由业务事务按写入数量不一致回滚整批来源。 */
            INSERT INTO message_source (
                id, message_id, origin_source_id, citation_no, is_cited, source_type,
                kb_id, chunk_id, ticket_id, tool_call_id, locator, snapshot, created_at
            )
            <foreach collection="sources" item="source" separator=" UNION ALL ">
            SELECT
                #{source.id,jdbcType=OTHER}, #{source.messageId,jdbcType=OTHER},
                CAST(#{source.originSourceId,jdbcType=OTHER} AS UUID), #{source.citationNo}, #{source.cited},
                #{source.sourceType,jdbcType=VARCHAR}, #{source.knowledgeBaseId,jdbcType=OTHER},
                #{source.chunkId,jdbcType=OTHER}, NULL, NULL,
                CAST(#{source.locator,jdbcType=VARCHAR} AS JSONB),
                CAST(#{source.snapshot,jdbcType=VARCHAR} AS JSONB), #{source.createdAt}
            WHERE EXISTS (
                SELECT 1
                FROM chat_message message
                INNER JOIN chat_run run
                    ON run.id = message.run_id
                   AND run.session_id = message.session_id
                INNER JOIN chat_session chat ON chat.id = message.session_id
                INNER JOIN document_chunk chunk ON chunk.id = #{source.chunkId,jdbcType=OTHER}
                INNER JOIN document_version version ON version.id = chunk.version_id
                INNER JOIN document document ON document.id = version.document_id
                INNER JOIN knowledge_base kb ON kb.id = document.kb_id
                INNER JOIN chat_session_kb session_kb
                    ON session_kb.session_id = chat.id
                   AND session_kb.kb_id = kb.id
                INNER JOIN kb_member member
                    ON member.kb_id = kb.id
                   AND member.user_id = chat.user_id
                INNER JOIN app_user account ON account.id = chat.user_id
                INNER JOIN vector_store vector ON vector.id = chunk.id
                WHERE message.id = #{source.messageId,jdbcType=OTHER}
                  AND run.id = #{runId,jdbcType=OTHER}
                  AND message.role = 'ASSISTANT'
                  AND message.status = 'STREAMING'
                  AND run.status = 'RUNNING'
                  AND chat.deleted_at IS NULL
                  AND chat.mode = 'RAG'
                  AND account.enabled = TRUE
                  AND member.permission IN ('READ', 'WRITE')
                  AND kb.id = #{source.knowledgeBaseId,jdbcType=OTHER}
                  AND kb.status = 'ACTIVE'
                  AND document.deleted_at IS NULL
                  AND document.active_version_id = version.id
                  AND version.status = 'READY'
                  AND LENGTH(BTRIM(vector.content)) > 0
                  AND (
                      CAST(#{source.originSourceId,jdbcType=OTHER} AS UUID) IS NULL
                      OR EXISTS (
                          SELECT 1
                          FROM message_source origin
                          INNER JOIN chat_message origin_message
                              ON origin_message.id = origin.message_id
                          INNER JOIN chat_run origin_run
                              ON origin_run.id = origin_message.run_id
                             AND origin_run.session_id = origin_message.session_id
                          INNER JOIN chat_session origin_session
                              ON origin_session.id = origin_message.session_id
                          WHERE origin.id = CAST(#{source.originSourceId,jdbcType=OTHER} AS UUID)
                            AND origin.origin_source_id IS NULL
                            AND origin_message.role = 'ASSISTANT'
                            AND origin_message.status = 'COMPLETED'
                            AND origin_run.status = 'COMPLETED'
                            AND origin_session.id = chat.id
                            AND origin_session.user_id = chat.user_id
                            AND origin_message.seq < message.seq
                            AND origin.source_type = #{source.sourceType,jdbcType=VARCHAR}
                            AND origin.kb_id = #{source.knowledgeBaseId,jdbcType=OTHER}
                            AND origin.chunk_id = #{source.chunkId,jdbcType=OTHER}
                            AND origin.locator = CAST(#{source.locator,jdbcType=VARCHAR} AS JSONB)
                            AND origin.snapshot = CAST(#{source.snapshot,jdbcType=VARCHAR} AS JSONB)
                      )
                  )
            )
            </foreach>
            </script>
            """)
    int insertAll(@Param("runId") UUID runId,
                  @Param("sources") List<MessageSource> sources);

    @Select("""
            -- message_source：按本轮引用编号读取助手消息的全部模型输入来源。
            SELECT id, message_id, origin_source_id, citation_no, is_cited, source_type,
                   kb_id, chunk_id, ticket_id, tool_call_id, locator, snapshot, created_at
            FROM message_source
            WHERE message_id = #{messageId,jdbcType=OTHER}
            ORDER BY citation_no
            """)
    @Results(id = "messageSourceResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "message_id", property = "messageId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "origin_source_id", property = "originSourceId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "citation_no", property = "citationNo"),
            @Result(column = "is_cited", property = "cited"),
            @Result(column = "source_type", property = "sourceType", javaType = MessageSourceTypeEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "kb_id", property = "knowledgeBaseId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "chunk_id", property = "chunkId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "ticket_id", property = "ticketId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "tool_call_id", property = "toolCallId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "locator", property = "locator"),
            @Result(column = "snapshot", property = "snapshot"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<MessageSource> selectByMessageId(@Param("messageId") UUID messageId);

    @Select("""
            -- message_source：读取历史助手消息的全部来源，并显式标记其继承链是否仍为同会话直接根来源。
            SELECT source.id AS source_id,
                   COALESCE(source.origin_source_id, source.id) AS root_source_id,
                   source.citation_no,
                   source.source_type,
                   source.kb_id,
                   source.chunk_id,
                   source.locator,
                   source.snapshot,
                   CASE
                       WHEN source.origin_source_id IS NULL THEN TRUE
                       ELSE root.id IS NOT NULL
                           AND root.origin_source_id IS NULL
                           AND root_message.role = 'ASSISTANT'
                           AND root_message.status = 'COMPLETED'
                           AND root_run.status = 'COMPLETED'
                           AND root_message.session_id = history_message.session_id
                           AND root_session.user_id = history_session.user_id
                           AND root_message.seq < history_message.seq
                           AND root.source_type = source.source_type
                           AND root.kb_id = source.kb_id
                           AND root.chunk_id = source.chunk_id
                           AND root.locator = source.locator
                           AND root.snapshot = source.snapshot
                   END AS lineage_valid
            FROM chat_message history_message
            INNER JOIN chat_run history_run
                ON history_run.id = history_message.run_id
               AND history_run.session_id = history_message.session_id
            INNER JOIN chat_session history_session
                ON history_session.id = history_message.session_id
            INNER JOIN message_source source
                ON source.message_id = history_message.id
            LEFT JOIN message_source root
                ON root.id = source.origin_source_id
            LEFT JOIN chat_message root_message
                ON root_message.id = root.message_id
            LEFT JOIN chat_run root_run
                ON root_run.id = root_message.run_id
               AND root_run.session_id = root_message.session_id
            LEFT JOIN chat_session root_session
                ON root_session.id = root_message.session_id
            WHERE history_message.id = #{assistantMessageId,jdbcType=OTHER}
              AND history_message.session_id = #{sessionId,jdbcType=OTHER}
              AND history_message.role = 'ASSISTANT'
              AND history_message.status = 'COMPLETED'
              AND history_run.status = 'COMPLETED'
              AND history_session.user_id = #{userId,jdbcType=OTHER}
              AND history_session.deleted_at IS NULL
            ORDER BY source.citation_no
            """)
    @Results(id = "historicalMessageSourceResultMap", value = {
            @Result(column = "source_id", property = "sourceId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class, id = true),
            @Result(column = "root_source_id", property = "rootSourceId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "citation_no", property = "citationNo"),
            @Result(column = "source_type", property = "sourceType", javaType = MessageSourceTypeEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "kb_id", property = "knowledgeBaseId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "chunk_id", property = "chunkId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "locator", property = "locator"),
            @Result(column = "snapshot", property = "snapshot"),
            @Result(column = "lineage_valid", property = "lineageValid")
    })
    List<HistoricalMessageSource> selectHistoricalSources(@Param("assistantMessageId") UUID assistantMessageId,
                                                          @Param("sessionId") UUID sessionId,
                                                          @Param("userId") UUID userId);

    @Update({
            "<script>",
            "/* message_source：仅将最终答案实际引用的已登记编号标记为已引用。 */",
            "UPDATE message_source",
            "SET is_cited = TRUE",
            "WHERE message_id = #{messageId,jdbcType=OTHER}",
            "  AND citation_no IN",
            "<foreach collection=\"citationNumbers\" item=\"citationNumber\" open=\"(\" separator=\",\" close=\")\">",
            "  #{citationNumber}",
            "</foreach>",
            "</script>"
    })
    int markCited(@Param("messageId") UUID messageId,
                  @Param("citationNumbers") List<Integer> citationNumbers);
}
