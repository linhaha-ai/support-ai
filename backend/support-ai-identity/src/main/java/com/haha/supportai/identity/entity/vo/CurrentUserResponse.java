package com.haha.supportai.identity.entity.vo;

import java.util.UUID;

import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "CurrentUserResponse", description = "当前登录用户")
public class CurrentUserResponse {

    @Schema(description = "用户标识", example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID id; // 用户标识

    @Schema(description = "登录用户名", example = "admin", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String username; // 登录用户名

    @Schema(description = "展示名称", example = "系统管理员", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String displayName; // 展示名称

    @Schema(description = "用户角色", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UserRoleEnum role; // 用户角色

    public static CurrentUserResponse from(AuthenticatedUser user) {
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
    }
}
