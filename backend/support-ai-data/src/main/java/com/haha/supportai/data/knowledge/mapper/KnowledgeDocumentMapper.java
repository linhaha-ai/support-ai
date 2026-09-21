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
}
