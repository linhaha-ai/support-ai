package com.haha.supportai.data.identity.mapper;

import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haha.supportai.data.identity.entity.po.AppUser;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.EnumTypeHandler;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {

    @Select("""
            -- app_user：锁定启用账号，串行化同一用户跨会话的运行配额判断。
            SELECT id, username, password_hash, display_name, role, enabled, created_at, updated_at
            FROM app_user
            WHERE id = #{userId,jdbcType=OTHER}
              AND enabled = TRUE
            FOR UPDATE
            """)
    @Results(id = "appUserResultMap", value = {
            @Result(column = "id", property = "id", javaType = UUID.class,
                    typeHandler = UuidTypeHandler.class, id = true),
            @Result(column = "username", property = "username"),
            @Result(column = "password_hash", property = "passwordHash"),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "role", property = "role", javaType = UserRoleEnum.class,
                    typeHandler = EnumTypeHandler.class),
            @Result(column = "enabled", property = "enabled"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    Optional<AppUser> selectEnabledByIdForUpdate(@Param("userId") UUID userId);
}
