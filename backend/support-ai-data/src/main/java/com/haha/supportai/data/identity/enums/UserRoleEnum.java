package com.haha.supportai.data.identity.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "用户角色", allowableValues = { "ADMIN", "USER" })
public enum UserRoleEnum {
    ADMIN,
    USER
}
