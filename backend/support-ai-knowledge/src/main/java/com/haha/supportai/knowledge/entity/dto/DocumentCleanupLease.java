package com.haha.supportai.knowledge.entity.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class DocumentCleanupLease {

    private final UUID jobId; // 清理任务标识

    private final UUID documentId; // 待清理文档标识

    private final int attempt; // 当前领取次数

    private final String leaseOwner; // 当前清理执行者标识

    private final UUID leaseToken; // 当前租约令牌

    private final OffsetDateTime leaseUntil; // 当前租约失效时间
}
