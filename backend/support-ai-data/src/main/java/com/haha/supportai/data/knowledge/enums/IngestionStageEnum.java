package com.haha.supportai.data.knowledge.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "文档入库处理阶段", allowableValues = { "PARSE", "CHUNK", "EMBED", "PUBLISH" })
public enum IngestionStageEnum {
    PARSE,
    CHUNK,
    EMBED,
    PUBLISH
}
