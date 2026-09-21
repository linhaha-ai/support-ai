package com.haha.supportai.knowledge.entity.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.data.knowledge.entity.po.KbMember;
import com.haha.supportai.data.knowledge.enums.KbPermissionEnum;
import com.haha.supportai.identity.entity.vo.UserSummaryResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "KnowledgeBaseMemberResponse", description = "知识库成员及授权信息")
public class KnowledgeBaseMemberResponse {

    @Schema(description = "成员用户标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID userId; // 成员用户标识

    @Schema(description = "登录用户名", example = "support1", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String username; // 登录用户名

    @Schema(description = "用户展示名称", example = "技术支持一号", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String displayName; // 用户展示名称

    @Schema(description = "用户角色", example = "USER", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UserRoleEnum role; // 用户角色

    @Schema(description = "账号是否启用", requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean enabled; // 账号是否启用

    @Schema(description = "知识库成员权限", example = "READ", requiredMode = Schema.RequiredMode.REQUIRED)
    private final KbPermissionEnum permission; // 成员权限

    @Schema(description = "最近一次授权操作人标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID grantedBy; // 最近一次授权操作人标识

    @Schema(description = "首次授权时间", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    private final OffsetDateTime createdAt; // 首次授权时间

    public static KnowledgeBaseMemberResponse from(KbMember member, UserSummaryResponse user) {
        return new KnowledgeBaseMemberResponse(member.getUserId(), user.getUsername(), user.getDisplayName(),
                user.getRole(), user.isEnabled(), member.getPermission(), member.getGrantedBy(), member.getCreatedAt());
    }
}
