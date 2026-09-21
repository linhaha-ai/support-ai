package com.haha.supportai.knowledge.ingestion.entity.dto;

import java.util.Objects;

import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class IngestionStageResult {

    private final IngestionStageEnum nextStage; // 下一处理阶段

    private final Integer completedChunks; // 当前已完成分块数量

    private final Integer totalChunks; // 当前已知分块总数

    private final boolean terminal; // 是否已在阶段处理器事务内完成最终状态提交

    public static IngestionStageResult advance(IngestionStageEnum nextStage, Integer completedChunks,
            Integer totalChunks) {
        return new IngestionStageResult(Objects.requireNonNull(nextStage), completedChunks, totalChunks, false);
    }

    public static IngestionStageResult completed(int completedChunks, int totalChunks) {
        return new IngestionStageResult(null, completedChunks, totalChunks, true);
    }
}
