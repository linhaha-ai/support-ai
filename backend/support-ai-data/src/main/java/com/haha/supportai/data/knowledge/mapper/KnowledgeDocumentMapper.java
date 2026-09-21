package com.haha.supportai.data.knowledge.mapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeDocument;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    @Select("""
            -- document、knowledge_base、kb_member、app_user：仅返回当前启用成员可读取的未删除文档。
            SELECT document.id, document.kb_id, document.name, document.active_version_id,
                   document.pending_version_id, document.next_version_no, document.created_by,
                   document.deleted_at, document.revision, document.created_at, document.updated_at
            FROM document
            INNER JOIN knowledge_base kb ON kb.id = document.kb_id
            INNER JOIN kb_member member ON member.kb_id = kb.id
            INNER JOIN app_user account ON account.id = member.user_id
            WHERE document.kb_id = #{knowledgeBaseId,jdbcType=OTHER}
              AND member.user_id = #{userId,jdbcType=OTHER}
              AND kb.status = 'ACTIVE'
              AND account.enabled = TRUE
              AND document.deleted_at IS NULL
            ORDER BY document.updated_at DESC, document.id DESC
            """)
    @Results(id = "knowledgeDocumentResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "kb_id", property = "kbId", javaType = UUID.class, typeHandler = UuidTypeHandler.class),
            @Result(column = "name", property = "name"),
            @Result(column = "active_version_id", property = "activeVersionId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "pending_version_id", property = "pendingVersionId", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "next_version_no", property = "nextVersionNo"),
            @Result(column = "created_by", property = "createdBy", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class),
            @Result(column = "deleted_at", property = "deletedAt"),
            @Result(column = "revision", property = "revision"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<KnowledgeDocument> selectAccessibleByKnowledgeBaseId(@Param("knowledgeBaseId") UUID knowledgeBaseId,
                                                               @Param("userId") UUID userId);

    @Select("""
            -- document、knowledge_base、kb_member、app_user：按文档标识复核当前用户的实时读取权限。
            SELECT document.id, document.kb_id, document.name, document.active_version_id,
                   document.pending_version_id, document.next_version_no, document.created_by,
                   document.deleted_at, document.revision, document.created_at, document.updated_at
            FROM document
            INNER JOIN knowledge_base kb ON kb.id = document.kb_id
            INNER JOIN kb_member member ON member.kb_id = kb.id
            INNER JOIN app_user account ON account.id = member.user_id
            WHERE document.id = #{documentId,jdbcType=OTHER}
              AND member.user_id = #{userId,jdbcType=OTHER}
              AND kb.status = 'ACTIVE'
              AND account.enabled = TRUE
              AND document.deleted_at IS NULL
            """)
    @ResultMap("knowledgeDocumentResultMap")
    Optional<KnowledgeDocument> selectAccessibleByIdAndUserId(@Param("documentId") UUID documentId,
                                                               @Param("userId") UUID userId);

    @Select("""
            -- document、knowledge_base、kb_member、app_user：锁定当前成员可见的文档，供版本更新事务串行分配版本号。
            SELECT document.id, document.kb_id, document.name, document.active_version_id,
                   document.pending_version_id, document.next_version_no, document.created_by,
                   document.deleted_at, document.revision, document.created_at, document.updated_at
            FROM document
            INNER JOIN knowledge_base kb ON kb.id = document.kb_id
            INNER JOIN kb_member member ON member.kb_id = kb.id
            INNER JOIN app_user account ON account.id = member.user_id
            WHERE document.id = #{documentId,jdbcType=OTHER}
              AND member.user_id = #{userId,jdbcType=OTHER}
              AND kb.status = 'ACTIVE'
              AND account.enabled = TRUE
              AND document.deleted_at IS NULL
            FOR UPDATE OF document
            """)
    @ResultMap("knowledgeDocumentResultMap")
    Optional<KnowledgeDocument> selectAccessibleByIdAndUserIdForUpdate(@Param("documentId") UUID documentId,
                                                                        @Param("userId") UUID userId);

    @Update("""
            -- document：文档逻辑表；pending_version_id 指向本次已入队的待处理版本
            UPDATE document
            SET pending_version_id = #{versionId,jdbcType=OTHER},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{documentId,jdbcType=OTHER}
            """)
    int updatePendingVersion(@Param("documentId") UUID documentId, @Param("versionId") UUID versionId);

    @Update("""
            -- document：仅修订号未变化且不存在待处理版本时登记新版，同时原子递增版本号和修订号。
            UPDATE document
            SET pending_version_id = #{versionId,jdbcType=OTHER},
                next_version_no = next_version_no + 1,
                revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{documentId,jdbcType=OTHER}
              AND revision = #{expectedRevision}
              AND next_version_no = #{versionNo}
              AND pending_version_id IS NULL
              AND deleted_at IS NULL
            """)
    int reservePendingVersion(@Param("documentId") UUID documentId,
                              @Param("expectedRevision") long expectedRevision,
                              @Param("versionId") UUID versionId,
                              @Param("versionNo") int versionNo);

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
