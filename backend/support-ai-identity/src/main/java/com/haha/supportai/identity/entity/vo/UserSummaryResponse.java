package com.haha.supportai.identity.entity.vo;

import java.util.UUID;

import com.haha.supportai.data.identity.entity.po.AppUser;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "UserSummaryResponse", description = "管理员可选择的用户摘要")
public class UserSummaryResponse {

    @Schema(description = "用户标识", example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID id; // 用户标识

    @Schema(description = "登录用户名", example = "support-a", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String username; // 登录用户名

    @Schema(description = "展示名称", example = "支持专员 A", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String displayName; // 展示名称

    @Schema(description = "用户角色", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UserRoleEnum role; // 用户角色

    @Schema(description = "是否启用", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean enabled; // 是否启用

    public static UserSummaryResponse from(AppUser user) {
        return new UserSummaryResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                user.isEnabled());
    }
}
