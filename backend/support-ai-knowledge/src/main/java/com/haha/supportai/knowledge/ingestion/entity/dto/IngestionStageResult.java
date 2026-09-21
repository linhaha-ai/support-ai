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

    public static IngestionStageResult advance(IngestionStageEnum nextStage, Integer completedChunks,
            Integer totalChunks) {
        return new IngestionStageResult(Objects.requireNonNull(nextStage), completedChunks, totalChunks);
    }
}
