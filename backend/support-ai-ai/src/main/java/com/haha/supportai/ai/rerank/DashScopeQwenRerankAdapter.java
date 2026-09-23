package com.haha.supportai.ai.rerank;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.haha.supportai.ai.config.RerankProperties;
import com.haha.supportai.ai.entity.dto.RerankDocumentScore;
import com.haha.supportai.ai.entity.dto.RerankModelResult;
import com.haha.supportai.ai.exception.RerankInputLimitException;
import com.haha.supportai.ai.exception.RerankInvalidResponseException;
import com.haha.supportai.ai.exception.RerankUnavailableException;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
public class DashScopeQwenRerankAdapter implements RerankModelAdapter {

    private static final Encoding TOKEN_ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);
    private static final String API_KEY_PROPERTY = "spring.ai.dashscope.api-key";

    private final WebClient.Builder webClientBuilder;
    private final Environment environment;
    private final RerankProperties properties;

    @Override
    public RerankModelResult rerank(String query, List<String> documents, int topN) {
        validateInput(query, documents, topN);
        String apiKey = environment.getProperty(API_KEY_PROPERTY);
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(properties.getEndpoint())) {
            throw new RerankUnavailableException(new IllegalStateException("重排序连接配置不完整"));
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.getModel());
        request.put("documents", documents);
        request.put("query", query);
        request.put("top_n", topN);
        request.put("instruct", properties.getInstruction());
        JsonNode response;
        try {
            response = webClientBuilder.clone()
                    .build()
                    .post()
                    .uri(properties.getEndpoint())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(properties.getTimeout());
        } catch (DecodingException exception) {
            throw new RerankInvalidResponseException();
        } catch (RuntimeException exception) {
            throw new RerankUnavailableException(exception);
        }
        return parseResponse(response, documents.size(), topN);
    }

    private void validateInput(String query, List<String> documents, int topN) {
        if (!StringUtils.hasText(query) || documents == null || documents.isEmpty()
                || documents.size() > properties.getMaxCandidates() || topN < 1 || topN > documents.size()) {
            throw new RerankInputLimitException();
        }
        int queryTokens = countTokens(query);
        if (queryTokens > properties.getMaxEstimatedItemTokens()) {
            throw new RerankInputLimitException();
        }
        int instructionTokens = countTokens(properties.getInstruction());
        if (instructionTokens > properties.getMaxEstimatedItemTokens()) {
            throw new RerankInputLimitException();
        }
        long estimatedRequestTokens = (long) (queryTokens + instructionTokens) * documents.size();
        for (String document : documents) {
            if (!StringUtils.hasText(document)) {
                throw new RerankInputLimitException();
            }
            int documentTokens = countTokens(document);
            if (documentTokens > properties.getMaxEstimatedItemTokens()) {
                throw new RerankInputLimitException();
            }
            estimatedRequestTokens += documentTokens;
            if (estimatedRequestTokens > properties.getMaxEstimatedRequestTokens()) {
                throw new RerankInputLimitException();
            }
        }
    }

    private RerankModelResult parseResponse(JsonNode response, int documentCount, int topN) {
        if (response == null || !properties.getModel().equals(response.path("model").asText())) {
            throw new RerankInvalidResponseException();
        }
        JsonNode results = response.path("results");
        if (!results.isArray() || results.size() != topN) {
            throw new RerankInvalidResponseException();
        }
        boolean[] seen = new boolean[documentCount];
        List<RerankDocumentScore> rankings = new ArrayList<>(topN);
        double previousScore = Double.POSITIVE_INFINITY;
        for (JsonNode result : results) {
            JsonNode indexNode = result.get("index");
            JsonNode scoreNode = result.get("relevance_score");
            if (indexNode == null || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt()
                    || scoreNode == null || !scoreNode.isNumber()) {
                throw new RerankInvalidResponseException();
            }
            int index = indexNode.intValue();
            double score = scoreNode.doubleValue();
            if (index < 0 || index >= documentCount || seen[index] || !Double.isFinite(score)
                    || score < 0D || score > 1D || score > previousScore) {
                throw new RerankInvalidResponseException();
            }
            seen[index] = true;
            previousScore = score;
            rankings.add(new RerankDocumentScore(index, score));
        }
        Integer totalTokens = parseTotalTokens(response.path("usage").get("total_tokens"));
        return new RerankModelResult(List.copyOf(rankings), totalTokens);
    }

    private Integer parseTotalTokens(JsonNode totalTokensNode) {
        if (totalTokensNode == null || totalTokensNode.isNull()) {
            return null;
        }
        if (!totalTokensNode.isIntegralNumber() || !totalTokensNode.canConvertToInt()
                || totalTokensNode.intValue() < 0) {
            throw new RerankInvalidResponseException();
        }
        return totalTokensNode.intValue();
    }

    private int countTokens(String text) {
        return TOKEN_ENCODING.countTokensOrdinary(text);
    }
}
