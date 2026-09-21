package com.haha.supportai.data.knowledge.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "知识库状态", allowableValues = { "ACTIVE", "DISABLED" })
public enum KnowledgeBaseStatusEnum {
    ACTIVE,
    DISABLED
}
