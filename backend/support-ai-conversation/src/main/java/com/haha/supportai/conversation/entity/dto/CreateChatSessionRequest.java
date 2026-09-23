package com.haha.supportai.conversation.entity.dto;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(name = "CreateChatSessionRequest", description = "创建固定知识范围的问答会话请求")
public class CreateChatSessionRequest {

    @NotEmpty
    @Schema(description = "当前用户有读取权限的知识库标识集合，不能为空且不能重复", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<@NotNull UUID> kbIds; // 固定知识库范围

    @NotNull
    @Schema(description = "会话模式，支持 RAG 或受控智能助手", example = "ASSISTANT",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private ChatSessionModeEnum mode; // 会话模式
}
