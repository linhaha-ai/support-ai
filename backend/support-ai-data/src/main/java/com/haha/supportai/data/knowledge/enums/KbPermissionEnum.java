package com.haha.supportai.data.knowledge.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "知识库成员权限", allowableValues = { "READ", "WRITE" })
public enum KbPermissionEnum {
    READ,
    WRITE
}
