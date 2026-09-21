package com.haha.supportai.data.knowledge.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "文档版本处理状态", allowableValues = { "QUEUED", "BUILDING", "READY", "FAILED", "CANCELLED" })
public enum DocumentVersionStatusEnum {
    QUEUED,
    BUILDING,
    READY,
    FAILED,
    CANCELLED
}
