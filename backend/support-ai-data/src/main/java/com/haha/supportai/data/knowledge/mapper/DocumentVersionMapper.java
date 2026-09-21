package com.haha.supportai.data.knowledge.mapper;

import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DocumentVersionMapper extends BaseMapper<DocumentVersion> {

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
            -- document_version：任务耗尽重试后同步版本失败终态，避免任务与版本状态不一致。
            UPDATE document_version version
            SET status = 'FAILED',
                updated_at = CURRENT_TIMESTAMP
            WHERE version.id = #{versionId,jdbcType=OTHER}
              AND version.status IN ('QUEUED', 'BUILDING')
            """)
    int markFailedIfQueuedOrBuilding(@Param("versionId") UUID versionId);
}
