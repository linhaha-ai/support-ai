package com.haha.supportai.data.knowledge.mapper;

import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.AuthorizedDocumentSource;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuthorizedDocumentSourceMapper {

    @Select("""
            -- document_chunk、vector_store 及授权关系：按当前权限读取可追溯的 READY 历史或活动版本来源。
            SELECT kb.id AS knowledge_base_id,
                   document.id AS document_id,
                   document.name AS document_name,
                   version.id AS version_id,
                   version.version_no,
                   chunk.id AS chunk_id,
                   chunk.chunk_index,
                   chunk.section,
                   chunk.page_no,
                   chunk.start_offset,
                   chunk.end_offset,
                   vector.content,
                   COALESCE(document.active_version_id = version.id, FALSE) AS is_current
            FROM document_chunk chunk
            INNER JOIN vector_store vector ON vector.id = chunk.id
            INNER JOIN document_version version ON version.id = chunk.version_id
            INNER JOIN document ON document.id = version.document_id
            INNER JOIN knowledge_base kb ON kb.id = document.kb_id
            INNER JOIN kb_member member ON member.kb_id = kb.id
            INNER JOIN app_user account ON account.id = member.user_id
            WHERE chunk.id = #{chunkId,jdbcType=OTHER}
              AND member.user_id = #{userId,jdbcType=OTHER}
              AND member.permission IN ('READ', 'WRITE')
              AND account.enabled = TRUE
              AND kb.status = 'ACTIVE'
              AND document.deleted_at IS NULL
              AND version.status = 'READY'
              AND LENGTH(BTRIM(vector.content)) > 0
            """)
    @Results(id = "authorizedDocumentSourceResultMap", value = {
            @Result(column = "knowledge_base_id", property = "knowledgeBaseId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "document_id", property = "documentId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "document_name", property = "documentName"),
            @Result(column = "version_id", property = "versionId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "version_no", property = "versionNo"),
            @Result(column = "chunk_id", property = "chunkId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class, id = true),
            @Result(column = "chunk_index", property = "chunkIndex"),
            @Result(column = "section", property = "section"),
            @Result(column = "page_no", property = "pageNo"),
            @Result(column = "start_offset", property = "startOffset"),
            @Result(column = "end_offset", property = "endOffset"),
            @Result(column = "content", property = "content"),
            @Result(column = "is_current", property = "current")
    })
    Optional<AuthorizedDocumentSource> findReadableByChunkId(@Param("userId") UUID userId,
                                                               @Param("chunkId") UUID chunkId);
}
