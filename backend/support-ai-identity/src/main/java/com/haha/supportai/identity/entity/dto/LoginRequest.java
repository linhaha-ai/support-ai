package com.haha.supportai.identity.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(name = "LoginRequest", description = "登录凭据")
public class LoginRequest {

    @Schema(description = "登录用户名", example = "admin", maxLength = 64,
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 64)
    private String username; // 登录用户名

    @Schema(description = "登录密码，仅用于本次认证", format = "password", maxLength = 200,
            accessMode = Schema.AccessMode.WRITE_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 200)
    private String password; // 登录密码，仅用于本次认证

    @Override
    public String toString() {
        return "LoginRequest{username='" + username + "', password='[PROTECTED]'}";
    }
}
