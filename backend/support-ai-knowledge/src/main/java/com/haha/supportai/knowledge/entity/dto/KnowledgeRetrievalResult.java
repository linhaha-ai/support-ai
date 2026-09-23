package com.haha.supportai.knowledge.entity.dto;

import java.util.List;

import com.haha.supportai.knowledge.entity.enums.RerankStatusEnum;
import com.haha.supportai.knowledge.entity.enums.RetrievalStrategyEnum;
import com.haha.supportai.knowledge.entity.vo.KnowledgeRetrievalEvidenceResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class KnowledgeRetrievalResult {

    private final List<KnowledgeRetrievalEvidenceResponse> matches; // 最终返回的授权证据

    private final RetrievalStrategyEnum strategy; // 实际使用的基础检索策略

    private final int vectorCandidateCount; // 向量路由实际返回的候选数量

    private final int keywordCandidateCount; // 关键词路由实际返回的候选数量

    private final RerankStatusEnum rerankStatus; // 重排序执行或回退状态

    private final int rerankCandidateCount; // 重排序阶段接收的候选数量，关闭或无候选时为 0

    private final long rerankDurationMs; // 重排序尝试耗时毫秒

    private final Integer rerankTotalTokens; // 重排序模型返回的 Token 总量，未知时为空
}
