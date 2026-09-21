package com.haha.supportai.data.knowledge.mapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.data.knowledge.enums.DocumentVersionStatusEnum;
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
public interface DocumentVersionMapper extends BaseMapper<DocumentVersion> {

    @Select("""
            <script>
            -- document_version、document、knowledge_base、kb_member、app_user：读取当前成员可见文档的历史版本。
            SELECT version.id, version.document_id, version.version_no, version.storage_key, version.file_hash,
                   version.file_size, version.media_type, version.original_filename, version.status,
                   version.parser_version, version.chunker_version, version.embedding_model, version.dimensions,
                   version.config_hash, version.config_snapshot, version.chunk_count, version.created_at,
                   version.updated_at
            FROM document_version version
            INNER JOIN document ON document.id = version.document_id
            INNER JOIN knowledge_base kb ON kb.id = document.kb_id
            INNER JOIN kb_member member ON member.kb_id = kb.id
            INNER JOIN app_user account ON account.id = member.user_id
            WHERE version.document_id = #{documentId,jdbcType=OTHER}
              AND member.user_id = #{userId,jdbcType=OTHER}
              AND kb.status = 'ACTIVE'
              AND account.enabled = TRUE
              AND document.deleted_at IS NULL
            <if test="beforeVersionNo != null">
              AND version.version_no &lt; #{beforeVersionNo}
            </if>
            ORDER BY version.version_no DESC
            LIMIT #{limit}
            </script>
            """)
    @Results(id = "documentVersionResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "document_id", property = "documentId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "version_no", property = "versionNo"),
            @Result(column = "storage_key", property = "storageKey"),
            @Result(column = "file_hash", property = "fileHash"),
            @Result(column = "file_size", property = "fileSize"),
            @Result(column = "media_type", property = "mediaType"),
            @Result(column = "original_filename", property = "originalFilename"),
            @Result(column = "status", property = "status", javaType = DocumentVersionStatusEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "parser_version", property = "parserVersion"),
            @Result(column = "chunker_version", property = "chunkerVersion"),
            @Result(column = "embedding_model", property = "embeddingModel"),
            @Result(column = "dimensions", property = "dimensions"),
            @Result(column = "config_hash", property = "configHash"),
            @Result(column = "config_snapshot", property = "configSnapshot"),
            @Result(column = "chunk_count", property = "chunkCount"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<DocumentVersion> selectAccessibleByDocumentId(@Param("documentId") UUID documentId,
                                                       @Param("userId") UUID userId,
                                                       @Param("beforeVersionNo") Integer beforeVersionNo,
                                                       @Param("limit") int limit);

    @Select("""
            -- document_version、document、knowledge_base、kb_member、app_user：禁止跨文档或越权读取单个版本。
            SELECT version.id, version.document_id, version.version_no, version.storage_key, version.file_hash,
                   version.file_size, version.media_type, version.original_filename, version.status,
                   version.parser_version, version.chunker_version, version.embedding_model, version.dimensions,
                   version.config_hash, version.config_snapshot, version.chunk_count, version.created_at,
                   version.updated_at
            FROM document_version version
            INNER JOIN document ON document.id = version.document_id
            INNER JOIN knowledge_base kb ON kb.id = document.kb_id
            INNER JOIN kb_member member ON member.kb_id = kb.id
            INNER JOIN app_user account ON account.id = member.user_id
            WHERE version.id = #{versionId,jdbcType=OTHER}
              AND version.document_id = #{documentId,jdbcType=OTHER}
              AND member.user_id = #{userId,jdbcType=OTHER}
              AND kb.status = 'ACTIVE'
              AND account.enabled = TRUE
              AND document.deleted_at IS NULL
            """)
    @ResultMap("documentVersionResultMap")
    Optional<DocumentVersion> selectAccessibleByDocumentIdAndIdAndUserId(@Param("documentId") UUID documentId,
                                                                           @Param("versionId") UUID versionId,
                                                                           @Param("userId") UUID userId);

    @Insert("""
            -- document_version：文档版本表；config_snapshot 显式转换为 PostgreSQL JSONB
            INSERT INTO document_version (
                id, document_id, version_no, storage_key, file_hash, file_size, media_type, original_filename,
                status, parser_version, chunker_version, embedding_model, dimensions, config_hash, config_snapshot,
                chunk_count, created_at, updated_at
            ) VALUES (
                #{documentVersion.id,jdbcType=OTHER}, #{documentVersion.documentId,jdbcType=OTHER},
                #{documentVersion.versionNo}, #{documentVersion.storageKey}, #{documentVersion.fileHash},
                #{documentVersion.fileSize}, #{documentVersion.mediaType}, #{documentVersion.originalFilename},
                #{documentVersion.status,jdbcType=VARCHAR}, #{documentVersion.parserVersion},
                #{documentVersion.chunkerVersion}, #{documentVersion.embeddingModel}, #{documentVersion.dimensions},
                #{documentVersion.configHash}, CAST(#{documentVersion.configSnapshot,jdbcType=VARCHAR} AS JSONB),
                #{documentVersion.chunkCount}, #{documentVersion.createdAt}, #{documentVersion.updatedAt}
            )
            """)
    int insertDocumentVersion(@Param("documentVersion") DocumentVersion documentVersion);

    @Update("""
            -- document_version：分块写入成功后标记构建中并同步当前分块数量。
            UPDATE document_version version
            SET status = 'BUILDING',
                chunk_count = #{chunkCount},
                updated_at = CURRENT_TIMESTAMP
            WHERE version.id = #{versionId,jdbcType=OTHER}
              AND version.status IN ('QUEUED', 'BUILDING')
            """)
    int updateBuildingChunkCount(@Param("versionId") UUID versionId, @Param("chunkCount") int chunkCount);

    @Update("""
            -- document_version：仅完整写入全部分块的构建中版本允许变更为 READY。
            UPDATE document_version version
            SET status = 'READY',
                updated_at = CURRENT_TIMESTAMP
            WHERE version.id = #{versionId,jdbcType=OTHER}
              AND version.status = 'BUILDING'
              AND version.chunk_count = #{chunkCount}
            """)
    int markReady(@Param("versionId") UUID versionId, @Param("chunkCount") int chunkCount);

    @Update("""
            -- document_version：任务耗尽重试后同步版本失败终态，避免任务与版本状态不一致。
            UPDATE document_version version
            SET status = 'FAILED',
                updated_at = CURRENT_TIMESTAMP
            WHERE version.id = #{versionId,jdbcType=OTHER}
              AND version.status IN ('QUEUED', 'BUILDING')
            """)
    int markFailedIfQueuedOrBuilding(@Param("versionId") UUID versionId);
}
