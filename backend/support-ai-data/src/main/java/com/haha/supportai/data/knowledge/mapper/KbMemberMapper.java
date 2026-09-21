package com.haha.supportai.data.knowledge.mapper;

import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.knowledge.entity.po.KbMember;
import com.haha.supportai.data.knowledge.enums.KbPermissionEnum;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
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
}
