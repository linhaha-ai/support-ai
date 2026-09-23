package com.haha.supportai.conversation.entity.dto;

import java.util.UUID;

import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ChatRunCancellationResult {

    private final UUID runId; // 已校验归属的运行标识

    private final ChatRunStatusEnum status; // 取消竞争结束后的实际运行状态

    private final boolean newlyCancelled; // 本次请求是否赢得终态竞争并完成取消
}
