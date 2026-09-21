package com.haha.supportai.knowledge.ingestion.entity.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class IngestionTaskLease {

    private final UUID jobId; // 入库任务标识

    private final UUID versionId; // 待处理文档版本标识

    private final IngestionStageEnum stage; // 本次领取的处理阶段

    private final int attempt; // 本次领取后的尝试次数

    private final int maxAttempts; // 当前阶段允许的最大尝试次数

    private final String leaseOwner; // 当前执行实例标识

    private final UUID leaseToken; // 本次领取生成的随机租约令牌

    private final OffsetDateTime leaseUntil; // 本次租约失效时间
}
