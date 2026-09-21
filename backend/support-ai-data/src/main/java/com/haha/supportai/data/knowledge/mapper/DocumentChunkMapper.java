package com.haha.supportai.data.knowledge.mapper;

import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.knowledge.entity.po.DocumentChunk;
import com.haha.supportai.data.knowledge.entity.po.DocumentChunkContent;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    @Select("""
            <script>
            -- document_chunk、vector_store：按稳定分块序号读取位置元数据和正文；正文以 vector_store 为唯一副本。
            SELECT chunk.id, chunk.version_id, chunk.chunk_index, chunk.section, chunk.page_no,
                   chunk.start_offset, chunk.end_offset, chunk.char_count, chunk.token_count, vector.content
            FROM document_chunk chunk
            LEFT JOIN vector_store vector ON vector.id = chunk.id
            WHERE chunk.version_id = #{versionId,jdbcType=OTHER}
            <if test="afterChunkIndex != null">
              AND chunk.chunk_index &gt; #{afterChunkIndex}
            </if>
            ORDER BY chunk.chunk_index
            LIMIT #{limit}
            </script>
            """)
    @Results(id = "documentChunkContentResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "version_id", property = "versionId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "chunk_index", property = "chunkIndex"),
            @Result(column = "section", property = "section"),
            @Result(column = "page_no", property = "pageNo"),
            @Result(column = "start_offset", property = "startOffset"),
            @Result(column = "end_offset", property = "endOffset"),
            @Result(column = "char_count", property = "charCount"),
            @Result(column = "token_count", property = "tokenCount"),
            @Result(column = "content", property = "content")
    })
    List<DocumentChunkContent> selectContentByVersionId(@Param("versionId") UUID versionId,
                                                         @Param("afterChunkIndex") Integer afterChunkIndex,
                                                         @Param("limit") int limit);

    @Insert("""
            <script>
            -- document_chunk：按版本和分块序号批量幂等写入；冲突时保留既有稳定分块标识。
            INSERT INTO document_chunk (
                id, version_id, chunk_index, section, page_no, start_offset, end_offset,
                content_hash, char_count, token_count
            ) VALUES
            <foreach collection="documentChunks" item="documentChunk" separator=",">
            (
                #{documentChunk.id,jdbcType=OTHER}, #{documentChunk.versionId,jdbcType=OTHER},
                #{documentChunk.chunkIndex}, #{documentChunk.section}, #{documentChunk.pageNo},
                #{documentChunk.startOffset}, #{documentChunk.endOffset}, #{documentChunk.contentHash},
                #{documentChunk.charCount}, #{documentChunk.tokenCount}
            )
            </foreach>
            ON CONFLICT (version_id, chunk_index) DO UPDATE
            SET section = EXCLUDED.section,
                page_no = EXCLUDED.page_no,
                start_offset = EXCLUDED.start_offset,
                end_offset = EXCLUDED.end_offset,
                content_hash = EXCLUDED.content_hash,
                char_count = EXCLUDED.char_count,
                token_count = EXCLUDED.token_count
            </script>
            """)
    int upsertBatch(@Param("documentChunks") List<DocumentChunk> documentChunks);

    @Select("""
            -- document_chunk：按版本读取分块，固定使用分块序号作为展示和后续处理顺序。
            SELECT id, version_id, chunk_index, section, page_no, start_offset, end_offset,
                   content_hash, char_count, token_count
            FROM document_chunk
            WHERE version_id = #{versionId,jdbcType=OTHER}
            ORDER BY chunk_index
            """)
    @Results(id = "documentChunkResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "version_id", property = "versionId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "chunk_index", property = "chunkIndex"),
            @Result(column = "section", property = "section"),
            @Result(column = "page_no", property = "pageNo"),
            @Result(column = "start_offset", property = "startOffset"),
            @Result(column = "end_offset", property = "endOffset"),
            @Result(column = "content_hash", property = "contentHash"),
            @Result(column = "char_count", property = "charCount"),
            @Result(column = "token_count", property = "tokenCount")
    })
    List<DocumentChunk> selectByVersionId(@Param("versionId") UUID versionId);

    @Select("""
            -- document_chunk：统计版本已持久化分块数量，发布前需与 document_version.chunk_count 一致。
            SELECT COUNT(*)
            FROM document_chunk
            WHERE version_id = #{versionId,jdbcType=OTHER}
            """)
    long countByVersionId(@Param("versionId") UUID versionId);

    @Delete("""
            -- document_chunk：重新解析后删除新分块数量之外的旧记录，避免遗留片段参与后续向量化。
            DELETE FROM document_chunk
            WHERE version_id = #{versionId,jdbcType=OTHER}
              AND chunk_index >= #{chunkCount}
            """)
    int deleteByVersionIdAndChunkIndexGreaterThanOrEqual(@Param("versionId") UUID versionId,
                                                          @Param("chunkCount") int chunkCount);
}
