package com.haha.supportai.ai.rerank;

import java.util.List;

import com.haha.supportai.ai.entity.dto.RerankModelResult;

public interface RerankModelAdapter {

    RerankModelResult rerank(String query, List<String> documents, int topN);
}
