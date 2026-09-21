package com.haha.supportai.data.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
