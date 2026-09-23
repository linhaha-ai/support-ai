package com.haha.supportai.conversation.support;

import java.util.ArrayList;
import java.util.List;

import com.haha.supportai.conversation.config.RagProperties;
import com.haha.supportai.knowledge.entity.dto.KnowledgeRetrievalResult;
import com.haha.supportai.knowledge.entity.enums.RerankStatusEnum;
import com.haha.supportai.knowledge.entity.enums.RetrievalStrategyEnum;
import com.haha.supportai.knowledge.entity.vo.KnowledgeRetrievalEvidenceResponse;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RagEvidenceSelector {

    private static final Encoding TOKEN_ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    private final RagProperties properties;

    public EvidenceSelection select(KnowledgeRetrievalResult retrieval,
            List<KnowledgeRetrievalEvidenceResponse> evidenceList) {
        double minimumScore = minimumScore(retrieval);
        List<KnowledgeRetrievalEvidenceResponse> eligible = evidenceList.stream()
                .filter(evidence -> evidence.getScore() >= minimumScore)
                .toList();
        int scoreFilteredCount = evidenceList.size() - eligible.size();
        int evidenceTokens = 0;
        List<KnowledgeRetrievalEvidenceResponse> selected = new ArrayList<>(eligible.size());
        for (KnowledgeRetrievalEvidenceResponse evidence : eligible) {
            int itemTokens = TOKEN_ENCODING.countTokensOrdinary(evidence.getContent());
            if ((long) evidenceTokens + itemTokens > properties.getMaxEvidenceTokens()) {
                break;
            }
            selected.add(evidence);
            evidenceTokens += itemTokens;
        }
        return new EvidenceSelection(List.copyOf(selected), evidenceTokens, scoreFilteredCount,
                eligible.size() - selected.size());
    }

    private double minimumScore(KnowledgeRetrievalResult retrieval) {
        if (retrieval.getRerankStatus() == RerankStatusEnum.APPLIED) {
            return properties.getMinimumRerankScore();
        }
        RetrievalStrategyEnum strategy = retrieval.getStrategy();
        if (strategy == RetrievalStrategyEnum.VECTOR) {
            return properties.getMinimumVectorScore();
        }
        if (strategy == RetrievalStrategyEnum.FUSION) {
            return properties.getMinimumFusionScore();
        }
        throw new IllegalStateException("无法确定证据分数类型");
    }

    public record EvidenceSelection(List<KnowledgeRetrievalEvidenceResponse> evidenceList,
                                    int evidenceTokens,
                                    int scoreFilteredCount,
                                    int budgetFilteredCount) {
    }
}
