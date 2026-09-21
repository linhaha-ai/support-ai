package com.haha.supportai.data.knowledge.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "文档入库任务状态", allowableValues = {
        "QUEUED", "RUNNING", "RETRY_WAIT", "SUCCEEDED", "FAILED", "CANCELLED"
})
public enum IngestionJobStatusEnum {
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
