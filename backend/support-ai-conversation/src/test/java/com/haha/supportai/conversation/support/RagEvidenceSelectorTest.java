package com.haha.supportai.conversation.support;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.conversation.config.RagProperties;
import com.haha.supportai.knowledge.entity.dto.KnowledgeRetrievalResult;
import com.haha.supportai.knowledge.entity.enums.RerankStatusEnum;
import com.haha.supportai.knowledge.entity.enums.RetrievalStrategyEnum;
import com.haha.supportai.knowledge.entity.vo.KnowledgeRetrievalEvidenceResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagEvidenceSelectorTest {

    @Test
    void usesScoreThresholdForActualFinalRankingType() {
        RagProperties properties = new RagProperties();
        properties.setMinimumVectorScore(0.5D);
        properties.setMinimumFusionScore(0.01D);
        properties.setMinimumRerankScore(0.8D);
        RagEvidenceSelector selector = new RagEvidenceSelector(properties);
        List<KnowledgeRetrievalEvidenceResponse> evidenceList = List.of(evidence(1, "第一条", 0.7D));

        assertThat(selector.select(result(RetrievalStrategyEnum.VECTOR, RerankStatusEnum.DISABLED), evidenceList)
                .evidenceList()).hasSize(1);
        assertThat(selector.select(result(RetrievalStrategyEnum.FUSION, RerankStatusEnum.DISABLED), evidenceList)
                .evidenceList()).hasSize(1);
        assertThat(selector.select(result(RetrievalStrategyEnum.FUSION, RerankStatusEnum.APPLIED), evidenceList)
                .evidenceList()).isEmpty();
    }

    @Test
    void keepsRankedPrefixAndReportsBudgetFiltering() {
        RagProperties properties = new RagProperties();
        properties.setMaxEvidenceTokens(1);
        RagEvidenceSelector selector = new RagEvidenceSelector(properties);

        var selection = selector.select(result(RetrievalStrategyEnum.VECTOR, RerankStatusEnum.DISABLED),
                List.of(evidence(1, "a", 0.9D), evidence(2, "b", 0.8D)));

        assertThat(selection.evidenceList()).extracting(KnowledgeRetrievalEvidenceResponse::getContent)
                .containsExactly("a");
        assertThat(selection.evidenceTokens()).isEqualTo(1);
        assertThat(selection.scoreFilteredCount()).isZero();
        assertThat(selection.budgetFilteredCount()).isEqualTo(1);
    }

    private KnowledgeRetrievalResult result(RetrievalStrategyEnum strategy, RerankStatusEnum rerankStatus) {
        return new KnowledgeRetrievalResult(List.of(), strategy, 0, 0, rerankStatus, 0, 0L, null);
    }

    private KnowledgeRetrievalEvidenceResponse evidence(int number, String content, double score) {
        UUID suffix = UUID.fromString("00000000-0000-4000-8000-%012d".formatted(number));
        return new KnowledgeRetrievalEvidenceResponse(suffix, suffix, "文档" + number, suffix, 1,
                suffix, number - 1, "章节", null, 0, content.length(), content, score);
    }
}
