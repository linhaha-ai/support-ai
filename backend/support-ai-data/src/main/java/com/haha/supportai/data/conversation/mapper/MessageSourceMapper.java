package com.haha.supportai.data.conversation.mapper;

import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
                kb_id, chunk_id, locator, snapshot, created_at
            )
            <foreach collection="sources" item="source" separator=" UNION ALL ">
            SELECT
                #{source.id,jdbcType=OTHER}, #{source.messageId,jdbcType=OTHER},
                CAST(#{source.originSourceId,jdbcType=OTHER} AS UUID), #{source.citationNo}, #{source.cited},
                #{source.sourceType,jdbcType=VARCHAR}, #{source.knowledgeBaseId,jdbcType=OTHER},
                #{source.chunkId,jdbcType=OTHER}, CAST(#{source.locator,jdbcType=VARCHAR} AS JSONB),
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
            )
            </foreach>
            </script>
            """)
    int insertAll(@Param("runId") UUID runId,
                  @Param("sources") List<MessageSource> sources);

    @Select("""
            -- message_source：按本轮引用编号读取助手消息的全部模型输入来源。
            SELECT id, message_id, origin_source_id, citation_no, is_cited, source_type,
                   kb_id, chunk_id, locator, snapshot, created_at
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
            @Result(column = "locator", property = "locator"),
            @Result(column = "snapshot", property = "snapshot"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<MessageSource> selectByMessageId(@Param("messageId") UUID messageId);

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
