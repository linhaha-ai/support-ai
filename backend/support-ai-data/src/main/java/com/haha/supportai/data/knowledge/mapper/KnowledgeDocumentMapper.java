package com.haha.supportai.data.knowledge.mapper;

import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    @Update("""
            -- document：文档逻辑表；pending_version_id 指向本次已入队的待处理版本
            UPDATE document
            SET pending_version_id = #{versionId,jdbcType=OTHER},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{documentId,jdbcType=OTHER}
            """)
    int updatePendingVersion(@Param("documentId") UUID documentId, @Param("versionId") UUID versionId);

    @Update("""
            -- document：仅未删除且待处理指针仍指向目标版本时发布，原活动版本保持原样直至本次切换成功。
            UPDATE document
            SET active_version_id = #{versionId,jdbcType=OTHER},
                pending_version_id = NULL,
                revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{documentId,jdbcType=OTHER}
              AND pending_version_id = #{versionId,jdbcType=OTHER}
              AND deleted_at IS NULL
            """)
    int publishPendingVersion(@Param("documentId") UUID documentId, @Param("versionId") UUID versionId);
}
