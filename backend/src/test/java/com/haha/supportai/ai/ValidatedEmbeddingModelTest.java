package com.haha.supportai.ai;

import java.util.List;

import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ValidatedEmbeddingModelTest {

    private final EmbeddingModel delegate = mock(EmbeddingModel.class);
    private final ValidatedEmbeddingModel model = new ValidatedEmbeddingModel(delegate, true);

    @Test
    void setsDocumentAndQueryPurposeWithoutDuplicatingEmbedding() {
        when(delegate.call(any())).thenReturn(response(new float[1024]));
        model.embed(List.of(new Document("退货规则")), EmbeddingOptions.builder().build(), List::of);
        model.embed("如何退货");
        var requests = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(delegate, times(2)).call(requests.capture());
        assertThat(requests.getAllValues()).extracting(request ->
                ((DashScopeEmbeddingOptions) request.getOptions()).getTextType()).containsExactly("document", "query");
        assertThat(requests.getAllValues()).allSatisfy(request ->
                assertThat(request.getOptions().getDimensions()).isEqualTo(1024));
    }

    @Test
    void dimensionsDoesNotCallProvider() {
        assertThat(model.dimensions()).isEqualTo(1024);
        verifyNoInteractions(delegate);
    }

    @Test
    void rejectsCountMismatch() {
        when(delegate.call(any())).thenReturn(new EmbeddingResponse(List.of()));
        assertThatThrownBy(() -> model.embed("测试")).hasMessageContaining("count");
    }

    @Test
    void rejectsWrongDimension() {
        when(delegate.call(any())).thenReturn(response(new float[768]));
        assertThatThrownBy(() -> model.embed("测试")).hasMessageContaining("1024");
    }

    @ParameterizedTest
    @ValueSource(floats = { Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY })
    void rejectsNonFiniteValues(float value) {
        float[] vector = new float[1024];
        vector[7] = value;
        when(delegate.call(any())).thenReturn(response(vector));
        assertThatThrownBy(() -> model.embed("测试")).hasMessageContaining("non-finite");
    }

    @Test
    void rejectsUnexpectedIndices() {
        when(delegate.call(any())).thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[1024], 1))));
        assertThatThrownBy(() -> model.embed("测试")).hasMessageContaining("indices");
    }

    @Test
    void rejectsBlankInputBeforeCallingProvider() {
        assertThatThrownBy(() -> model.embed(" ")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(delegate);
    }

    private static EmbeddingResponse response(float[] vector) {
        return new EmbeddingResponse(List.of(new Embedding(vector, 0)));
    }
}
