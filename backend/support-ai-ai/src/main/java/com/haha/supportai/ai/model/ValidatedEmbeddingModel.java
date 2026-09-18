package com.haha.supportai.ai.model;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** 将向量校验和查询/文档用途固定在模型边界，避免存储层遗漏。 */
public final class ValidatedEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 1024;

    private final EmbeddingModel delegate;
    private final boolean dashscope;

    public ValidatedEmbeddingModel(EmbeddingModel delegate, boolean dashscope) {
        this.delegate = delegate;
        this.dashscope = dashscope;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return invoke(request.getInstructions(), request.getOptions(), "document");
    }

    @Override
    public float[] embed(String text) {
        return invoke(List.of(text), null, "query").getResult().getOutput();
    }

    @Override
    public float[] embed(Document document) {
        return invoke(List.of(document.getText()), null, "document").getResult().getOutput();
    }

    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        List<float[]> vectors = new ArrayList<>(documents.size());
        for (List<Document> batch : batchingStrategy.batch(documents)) {
            var response = invoke(batch.stream().map(Document::getText).toList(), options, "document");
            response.getResults().stream().map(Embedding::getOutput).forEach(vectors::add);
        }
        return vectors;
    }

    @Override
    public int dimensions() {
        // 维度探测不得在普通启动时发出模型请求。
        return DIMENSIONS;
    }

    private EmbeddingResponse invoke(List<String> texts, EmbeddingOptions options, String purpose) {
        if (texts == null || texts.isEmpty() || texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new IllegalArgumentException("Embedding input must contain non-blank text");
        }
        EmbeddingOptions effective = options;
        if (dashscope) {
            effective = DashScopeEmbeddingOptions.builder()
                    .model(options == null ? null : options.getModel())
                    .dimensions(DIMENSIONS).textType(purpose).build();
        }
        EmbeddingResponse response = delegate.call(new EmbeddingRequest(texts, effective));
        if (response == null || response.getResults() == null || response.getResults().size() != texts.size()) {
            throw new IllegalStateException("Embedding response count does not match input count");
        }
        for (int index = 0; index < response.getResults().size(); index++) {
            Embedding embedding = response.getResults().get(index);
            if (embedding == null || embedding.getIndex() != index) {
                throw new IllegalStateException("Embedding response indices are invalid");
            }
            float[] vector = embedding.getOutput();
            if (vector == null || vector.length != DIMENSIONS) {
                throw new IllegalStateException("Embedding response must contain 1024 dimensions");
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new IllegalStateException("Embedding response contains a non-finite value");
                }
            }
        }
        return response;
    }
}
