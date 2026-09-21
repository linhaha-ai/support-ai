package com.haha.supportai.data.knowledge.repository;

import com.haha.supportai.data.knowledge.entity.po.IngestionJob;
import com.haha.supportai.data.knowledge.mapper.IngestionJobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class IngestionJobRepository {

    private final IngestionJobMapper ingestionJobMapper;

    public IngestionJob insert(IngestionJob ingestionJob) {
        ingestionJobMapper.insert(ingestionJob);
        return ingestionJob;
    }
}
