package com.haha.supportai.identity.entity.dto;

import java.util.UUID;

import com.haha.supportai.data.identity.entity.po.AppUser;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AuthenticatedUser {

    private final UUID id; // 用户标识
    private final String username; // 登录用户名
    private final String displayName; // 展示名称
    private final UserRoleEnum role; // 用户角色

    public static AuthenticatedUser from(AppUser user) {
        return new AuthenticatedUser(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
    }

    @Override
    public String toString() {
        return "AuthenticatedUser{id=" + id + ", username='" + username + "', role=" + role + "}";
    }
}
