package com.haha.supportai.conversation.entity.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(name = "SubmitMessageCommand", description = "提交一条需要流式回答的用户消息")
public class SubmitMessageCommand {

    @NotNull
    @Schema(description = "客户端生成的幂等请求标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID clientRequestId; // 客户端幂等请求标识

    @NotBlank
    @Size(max = 2000)
    @Schema(description = "用户问题，去除首尾空白后最多 2000 个字符", maxLength = 2000,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String content; // 用户消息正文

    public SubmitMessageCommand(UUID clientRequestId, String content) {
        this.clientRequestId = clientRequestId;
        this.content = content;
    }
}
