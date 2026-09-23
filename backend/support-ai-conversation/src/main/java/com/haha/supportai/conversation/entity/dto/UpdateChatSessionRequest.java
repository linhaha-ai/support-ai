package com.haha.supportai.conversation.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(name = "UpdateChatSessionRequest", description = "修改会话标题请求")
public class UpdateChatSessionRequest {

    @NotBlank
    @Size(max = 200)
    @Schema(description = "去除首尾空白后的会话标题，最长 200 个字符", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title; // 会话展示标题
}
