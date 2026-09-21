package com.haha.supportai.conversation.entity.vo;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatStreamSourcesResponse", description = "sources 事件数据")
public class ChatStreamSourcesResponse {

    @Schema(description = "已引用且发送前仍有读取权限的来源", requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<RagSourceResponse> sources; // 已引用且发送前仍有读取权限的来源
}
