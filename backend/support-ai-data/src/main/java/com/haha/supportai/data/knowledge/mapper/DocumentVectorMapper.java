package com.haha.supportai.data.knowledge.mapper;

import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.knowledge.entity.po.DocumentVector;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import com.haha.supportai.data.typehandler.VectorTypeHandler;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DocumentVectorMapper extends BaseMapper<DocumentVector> {

    @Insert("""
            <script>
            -- vector_store：按稳定分块标识批量幂等写入正文、元数据和向量，重试不新增重复向量。
            INSERT INTO vector_store (id, content, metadata, embedding)
            VALUES
            <foreach collection="documentVectors" item="documentVector" separator=",">
            (
                #{documentVector.id,jdbcType=OTHER},
                #{documentVector.content},
                CAST(#{documentVector.metadata,jdbcType=VARCHAR} AS JSON),
                CAST(#{documentVector.embedding,jdbcType=VARCHAR,typeHandler=VectorTypeHandler} AS vector)
            )
            </foreach>
            ON CONFLICT (id) DO UPDATE
            SET content = EXCLUDED.content,
                metadata = EXCLUDED.metadata,
                embedding = EXCLUDED.embedding
            </script>
            """)
    int upsertBatch(@Param("documentVectors") List<DocumentVector> documentVectors);

    @Select("""
            -- vector_store、document_chunk：按版本读取既有向量的分块标识和正文，用于重试时校验幂等状态。
            SELECT vector.id, vector.content, vector.metadata
            FROM vector_store vector
            INNER JOIN document_chunk chunk ON chunk.id = vector.id
            WHERE chunk.version_id = #{versionId,jdbcType=OTHER}
            ORDER BY chunk.chunk_index
            """)
    @Results(id = "documentVectorContentResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "content", property = "content"),
            @Result(column = "metadata", property = "metadata")
    })
    List<DocumentVector> selectByVersionId(@Param("versionId") UUID versionId);

    @Select("""
            -- vector_store、document_chunk：统计指定文档版本的已持久化向量数量。
            SELECT COUNT(*)
            FROM vector_store vector
            INNER JOIN document_chunk chunk ON chunk.id = vector.id
            WHERE chunk.version_id = #{versionId,jdbcType=OTHER}
            """)
    long countByVersionId(@Param("versionId") UUID versionId);

    @Select("""
            -- vector_store、document_chunk：按指定维度统计有效向量，发布前用于完整性校验。
            SELECT COUNT(*)
            FROM vector_store vector
            INNER JOIN document_chunk chunk ON chunk.id = vector.id
            WHERE chunk.version_id = #{versionId,jdbcType=OTHER}
              AND vector_dims(vector.embedding) = #{dimensions}
            """)
    long countByVersionIdAndDimensions(@Param("versionId") UUID versionId, @Param("dimensions") int dimensions);

    @Select("""
            -- vector_store：按分块标识读取完整向量，用于数据层测试和后续检索适配。
            SELECT id, content, metadata, embedding
            FROM vector_store
            WHERE id = #{id,jdbcType=OTHER}
            """)
    @Results(id = "documentVectorResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "content", property = "content"),
            @Result(column = "metadata", property = "metadata"),
            @Result(column = "embedding", property = "embedding", javaType = float[].class,
                    typeHandler = VectorTypeHandler.class)
    })
    DocumentVector selectVectorById(@Param("id") UUID id);
}
