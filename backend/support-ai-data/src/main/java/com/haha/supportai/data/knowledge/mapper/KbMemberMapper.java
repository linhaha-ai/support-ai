package com.haha.supportai.data.knowledge.mapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.knowledge.entity.po.KbMember;
import com.haha.supportai.data.knowledge.enums.KbPermissionEnum;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.EnumTypeHandler;

@Mapper
public interface KbMemberMapper extends BaseMapper<KbMember> {

    @Select("""
            SELECT kb_id, user_id, permission, granted_by, created_at
            FROM kb_member
            WHERE kb_id = #{kbId}
              AND user_id = #{userId}
            """)
    @Results(id = "kbMemberResultMap", value = {
            @Result(column = "kb_id", property = "kbId", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "user_id", property = "userId", javaType = UUID.class, typeHandler = UuidTypeHandler.class,
                    id = true),
            @Result(column = "permission", property = "permission", javaType = KbPermissionEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "granted_by", property = "grantedBy", javaType = UUID.class, typeHandler = UuidTypeHandler.class),
            @Result(column = "created_at", property = "createdAt")
    })
    Optional<KbMember> selectByKbIdAndUserId(@Param("kbId") UUID kbId, @Param("userId") UUID userId);

    @Select("""
            -- kb_member：知识库成员关系表；kb_id：知识库标识
            SELECT kb_id, user_id, permission, granted_by, created_at
            FROM kb_member
            WHERE kb_id = #{kbId,jdbcType=OTHER}
            ORDER BY created_at, user_id
            """)
    @ResultMap("kbMemberResultMap")
    List<KbMember> selectByKbId(@Param("kbId") UUID kbId);

    @Insert("""
            -- kb_member：知识库成员关系表；created_at 仅记录首次授权时间
            INSERT INTO kb_member (kb_id, user_id, permission, granted_by, created_at)
            VALUES (#{kbMember.kbId,jdbcType=OTHER}, #{kbMember.userId,jdbcType=OTHER},
                    #{kbMember.permission,jdbcType=VARCHAR}, #{kbMember.grantedBy,jdbcType=OTHER},
                    #{kbMember.createdAt})
            -- kb_id、user_id 是联合主键；冲突时仅覆盖权限和最新操作人
            ON CONFLICT (kb_id, user_id) DO UPDATE
            SET permission = EXCLUDED.permission,
                granted_by = EXCLUDED.granted_by
            """)
    int upsert(@Param("kbMember") KbMember kbMember);

    @Delete("""
            -- kb_member：知识库成员关系表；kb_id、user_id 共同定位待撤销的成员关系
            DELETE FROM kb_member
            WHERE kb_id = #{kbId,jdbcType=OTHER}
              AND user_id = #{userId,jdbcType=OTHER}
            """)
    int deleteByKbIdAndUserId(@Param("kbId") UUID kbId, @Param("userId") UUID userId);
}
