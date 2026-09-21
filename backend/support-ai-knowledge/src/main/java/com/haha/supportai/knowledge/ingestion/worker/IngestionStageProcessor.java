package com.haha.supportai.knowledge.ingestion.worker;

import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionStageResult;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;

public interface IngestionStageProcessor {

    IngestionStageEnum stage();

    IngestionStageResult process(IngestionTaskLease lease);
}
