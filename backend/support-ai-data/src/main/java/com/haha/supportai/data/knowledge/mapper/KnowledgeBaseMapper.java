package com.haha.supportai.data.knowledge.mapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeBase;
import com.haha.supportai.data.knowledge.enums.KnowledgeBaseStatusEnum;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.EnumTypeHandler;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    @Select("""
            SELECT kb.id, kb.name, kb.description, kb.owner_id, kb.status, kb.revision, kb.created_at, kb.updated_at
            FROM knowledge_base kb
            INNER JOIN kb_member member ON member.kb_id = kb.id
            WHERE member.user_id = #{userId}
              AND kb.status = 'ACTIVE'
            ORDER BY kb.updated_at DESC, kb.id
            """)
    @Results(id = "knowledgeBaseResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, typeHandler = UuidTypeHandler.class, id = true),
            @Result(column = "name", property = "name"),
            @Result(column = "description", property = "description"),
            @Result(column = "owner_id", property = "ownerId", javaType = UUID.class, typeHandler = UuidTypeHandler.class),
            @Result(column = "status", property = "status", javaType = KnowledgeBaseStatusEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "revision", property = "revision"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<KnowledgeBase> selectActiveByUserId(@Param("userId") UUID userId);

    @Select("""
            SELECT kb.id, kb.name, kb.description, kb.owner_id, kb.status, kb.revision, kb.created_at, kb.updated_at
            FROM knowledge_base kb
            INNER JOIN kb_member member ON member.kb_id = kb.id
            WHERE kb.id = #{kbId}
              AND member.user_id = #{userId}
              AND kb.status = 'ACTIVE'
            """)
    @ResultMap("knowledgeBaseResultMap")
    Optional<KnowledgeBase> selectActiveByIdAndUserId(@Param("kbId") UUID kbId, @Param("userId") UUID userId);

    @Select("""
            -- knowledge_base 与 kb_member：锁定启用知识库及当前成员关系，串行化上传与撤权/改权
            SELECT kb.id, kb.name, kb.description, kb.owner_id, kb.status, kb.revision, kb.created_at, kb.updated_at
            FROM knowledge_base kb
            INNER JOIN kb_member member ON member.kb_id = kb.id
            WHERE kb.id = #{kbId}
              AND member.user_id = #{userId}
              AND kb.status = 'ACTIVE'
            FOR UPDATE OF kb, member
            """)
    @ResultMap("knowledgeBaseResultMap")
    Optional<KnowledgeBase> selectActiveByIdAndUserIdForUpdate(@Param("kbId") UUID kbId,
                                                               @Param("userId") UUID userId);

    @Update("""
            UPDATE knowledge_base
            SET name = #{name},
                description = #{description},
                status = #{status},
                revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND revision = #{revision}
            """)
    int updateMetadataByIdAndRevision(@Param("id") UUID id, @Param("revision") long revision,
                                      @Param("name") String name, @Param("description") String description,
                                      @Param("status") KnowledgeBaseStatusEnum status);
}
