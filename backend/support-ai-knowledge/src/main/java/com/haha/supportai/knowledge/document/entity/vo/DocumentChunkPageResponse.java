package com.haha.supportai.knowledge.document.entity.vo;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(name = "DocumentChunkPageResponse", description = "文档分块游标分页结果")
public class DocumentChunkPageResponse {

    @Schema(description = "当前页分块列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<DocumentChunkResponse> items; // 当前页分块列表

    @Schema(description = "下一页不透明游标；没有下一页时为空", nullable = true)
    private final String nextCursor; // 下一页不透明游标
}
