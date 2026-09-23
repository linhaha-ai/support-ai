package com.haha.supportai.conversation.entity.vo;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "ChatStreamSourcesResponse", description = "sources 事件数据")
public class ChatStreamSourcesResponse {

    @Schema(description = "发送前按当前权限复核的已引用来源；已删除资料保留不可用标记", requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<RagSourceResponse> sources; // 发送前复核后的引用来源状态
}
