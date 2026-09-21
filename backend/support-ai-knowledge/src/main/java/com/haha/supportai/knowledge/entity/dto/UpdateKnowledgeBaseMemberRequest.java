package com.haha.supportai.knowledge.entity.dto;

import com.haha.supportai.data.knowledge.enums.KbPermissionEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(name = "UpdateKnowledgeBaseMemberRequest", description = "新增成员或覆盖成员权限请求")
public class UpdateKnowledgeBaseMemberRequest {

    @Schema(description = "成员权限", example = "READ", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private KbPermissionEnum permission; // 需要授予的知识库权限
}
