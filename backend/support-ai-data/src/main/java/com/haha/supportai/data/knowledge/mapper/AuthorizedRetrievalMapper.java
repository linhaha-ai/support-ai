package com.haha.supportai.data.knowledge.mapper;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.AuthorizedRetrievalHit;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import com.haha.supportai.data.typehandler.VectorTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuthorizedRetrievalMapper {

    @Select({
            "<script>",
            "/* vector_store、document_chunk、document_version、document、knowledge_base、kb_member、app_user：",
            "   在 pgvector 余弦排序前完成成员权限、启用账号、知识库状态、文档发布版本和嵌入空间过滤。 */",
            "SELECT kb.id AS knowledge_base_id, document.id AS document_id, document.name AS document_name,",
            "       version.id AS version_id, version.version_no, chunk.id AS chunk_id, chunk.chunk_index,",
            "       chunk.section, chunk.page_no, chunk.start_offset, chunk.end_offset, vector.content,",
            "       (1 - (vector.embedding &lt;=&gt; CAST(#{queryEmbedding,jdbcType=VARCHAR,typeHandler=VectorTypeHandler} AS vector))) AS score",
            "FROM vector_store vector",
            "INNER JOIN document_chunk chunk ON chunk.id = vector.id",
            "INNER JOIN document_version version ON version.id = chunk.version_id",
            "INNER JOIN document ON document.id = version.document_id",
            "INNER JOIN knowledge_base kb ON kb.id = document.kb_id",
            "INNER JOIN kb_member member ON member.kb_id = kb.id",
            "INNER JOIN app_user account ON account.id = member.user_id",
            "WHERE member.user_id = #{userId,jdbcType=OTHER}",
            "  AND member.permission = 'WRITE'",
            "  AND account.enabled = TRUE",
            "  AND kb.status = 'ACTIVE'",
            "  AND document.deleted_at IS NULL",
            "  AND document.active_version_id = version.id",
            "  AND version.status = 'READY'",
            "  AND version.dimensions = #{dimensions}",
            "  AND vector_dims(vector.embedding) = #{dimensions}",
            "  AND version.embedding_model = #{embeddingModel}",
            "  AND version.config_snapshot -&gt; 'embedding' -&gt;&gt; 'provider' = #{embeddingProvider}",
            "  AND version.config_snapshot -&gt; 'embedding' -&gt;&gt; 'model' = #{embeddingModel}",
            "  AND version.config_snapshot -&gt; 'embedding' -&gt;&gt; 'dimensions' = CAST(#{dimensions} AS TEXT)",
            "  AND kb.id = #{knowledgeBaseId,jdbcType=OTHER}",
            "ORDER BY vector.embedding &lt;=&gt; CAST(#{queryEmbedding,jdbcType=VARCHAR,typeHandler=VectorTypeHandler} AS vector),",
            "         chunk.id",
            "LIMIT #{topK}",
            "</script>"
    })
    @Results(id = "authorizedRetrievalHitResultMap", value = {
            @Result(column = "knowledge_base_id", property = "knowledgeBaseId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "document_id", property = "documentId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "document_name", property = "documentName"),
            @Result(column = "version_id", property = "versionId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "version_no", property = "versionNo"),
            @Result(column = "chunk_id", property = "chunkId", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "chunk_index", property = "chunkIndex"),
            @Result(column = "section", property = "section"),
            @Result(column = "page_no", property = "pageNo"),
            @Result(column = "start_offset", property = "startOffset"),
            @Result(column = "end_offset", property = "endOffset"),
            @Result(column = "content", property = "content"),
            @Result(column = "score", property = "score")
    })
    List<AuthorizedRetrievalHit> selectWritable(@Param("userId") UUID userId,
                                                 @Param("knowledgeBaseId") UUID knowledgeBaseId,
                                                 @Param("queryEmbedding") float[] queryEmbedding,
                                                 @Param("topK") int topK,
                                                 @Param("embeddingProvider") String embeddingProvider,
                                                 @Param("embeddingModel") String embeddingModel,
                                                 @Param("dimensions") int dimensions);

    @Select({
            "<script>",
            "/* vector_store、document_chunk、document_version、document、knowledge_base、kb_member、app_user、",
            "   chat_session、chat_session_kb：在 pgvector 余弦排序前，以会话范围和当前成员关系的交集过滤已发布分块。 */",
            "SELECT kb.id AS knowledge_base_id, document.id AS document_id, document.name AS document_name,",
            "       version.id AS version_id, version.version_no, chunk.id AS chunk_id, chunk.chunk_index,",
            "       chunk.section, chunk.page_no, chunk.start_offset, chunk.end_offset, vector.content,",
            "       (1 - (vector.embedding &lt;=&gt; CAST(#{queryEmbedding,jdbcType=VARCHAR,typeHandler=VectorTypeHandler} AS vector))) AS score",
            "FROM vector_store vector",
            "INNER JOIN document_chunk chunk ON chunk.id = vector.id",
            "INNER JOIN document_version version ON version.id = chunk.version_id",
            "INNER JOIN document ON document.id = version.document_id",
            "INNER JOIN knowledge_base kb ON kb.id = document.kb_id",
            "INNER JOIN kb_member member ON member.kb_id = kb.id",
            "INNER JOIN app_user account ON account.id = member.user_id",
            "INNER JOIN chat_session_kb session_kb ON session_kb.kb_id = kb.id",
            "INNER JOIN chat_session chat ON chat.id = session_kb.session_id",
            "WHERE chat.id = #{sessionId,jdbcType=OTHER}",
            "  AND chat.user_id = #{userId,jdbcType=OTHER}",
            "  AND chat.deleted_at IS NULL",
            "  AND chat.mode = 'RAG'",
            "  AND member.user_id = #{userId,jdbcType=OTHER}",
            "  AND account.enabled = TRUE",
            "  AND kb.status = 'ACTIVE'",
            "  AND document.deleted_at IS NULL",
            "  AND document.active_version_id = version.id",
            "  AND version.status = 'READY'",
            "  AND version.dimensions = #{dimensions}",
            "  AND vector_dims(vector.embedding) = #{dimensions}",
            "  AND version.embedding_model = #{embeddingModel}",
            "  AND version.config_snapshot -&gt; 'embedding' -&gt;&gt; 'provider' = #{embeddingProvider}",
            "  AND version.config_snapshot -&gt; 'embedding' -&gt;&gt; 'model' = #{embeddingModel}",
            "  AND version.config_snapshot -&gt; 'embedding' -&gt;&gt; 'dimensions' = CAST(#{dimensions} AS TEXT)",
            "ORDER BY vector.embedding &lt;=&gt; CAST(#{queryEmbedding,jdbcType=VARCHAR,typeHandler=VectorTypeHandler} AS vector),",
            "         chunk.id",
            "LIMIT #{topK}",
            "</script>"
    })
    @Results(id = "readableAuthorizedRetrievalHitResultMap", value = {
            @Result(column = "knowledge_base_id", property = "knowledgeBaseId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "document_id", property = "documentId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "document_name", property = "documentName"),
            @Result(column = "version_id", property = "versionId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "version_no", property = "versionNo"),
            @Result(column = "chunk_id", property = "chunkId", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "chunk_index", property = "chunkIndex"),
            @Result(column = "section", property = "section"),
            @Result(column = "page_no", property = "pageNo"),
            @Result(column = "start_offset", property = "startOffset"),
            @Result(column = "end_offset", property = "endOffset"),
            @Result(column = "content", property = "content"),
            @Result(column = "score", property = "score")
    })
    List<AuthorizedRetrievalHit> selectReadableWithinSession(@Param("userId") UUID userId,
                                                              @Param("sessionId") UUID sessionId,
                                                              @Param("queryEmbedding") float[] queryEmbedding,
                                                              @Param("topK") int topK,
                                                              @Param("embeddingProvider") String embeddingProvider,
                                                              @Param("embeddingModel") String embeddingModel,
                                                              @Param("dimensions") int dimensions);
}
