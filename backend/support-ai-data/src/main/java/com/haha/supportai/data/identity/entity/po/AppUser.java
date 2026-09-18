package com.haha.supportai.data.identity.entity.po;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.EnumTypeHandler;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "app_user", autoResultMap = true)
public class AppUser {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id; // 用户标识，由业务或初始化 SQL 提供 UUID

    @TableField("username")
    private String username; // 规范化用户名

    @TableField("password_hash")
    private String passwordHash; // BCrypt 密码摘要

    @TableField("display_name")
    private String displayName; // 展示名称

    @TableField(value = "role", typeHandler = EnumTypeHandler.class)
    private UserRoleEnum role; // 用户角色，按枚举名称存储

    @TableField("enabled")
    private boolean enabled; // 是否启用

    @TableField("created_at")
    private OffsetDateTime createdAt; // 创建时间

    @TableField("updated_at")
    private OffsetDateTime updatedAt; // 更新时间
}
