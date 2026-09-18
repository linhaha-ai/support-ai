package com.haha.supportai.ai.model;

import java.util.Collections;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DashScopeEmbeddingContractTest {

    @Test
    void actualSdkSendsDistinctPurposesAndExplicitDimension() {
        var restClient = RestClient.builder();
        var server = MockRestServiceServer.bindTo(restClient).build();
        String response = "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":["
                + String.join(",", Collections.nCopies(1024, "0.1")) + "]}]},\"usage\":{\"total_tokens\":1}}";
        for (String purpose : new String[] { "document", "query" }) {
            server.expect(jsonPath("$.model").value("text-embedding-v4"))
                    .andExpect(jsonPath("$.parameters.text_type").value(purpose))
                    .andExpect(jsonPath("$.parameters.dimension").value(1024))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        }
        var api = DashScopeApi.builder().apiKey("test-key-not-a-credential")
                .baseUrl("https://unit.invalid").restClientBuilder(restClient).build();
        var delegate = DashScopeEmbeddingModel.builder().dashScopeApi(api)
                .defaultOptions(DashScopeEmbeddingOptions.builder().model("text-embedding-v4").build()).build();
        var model = new ValidatedEmbeddingModel(delegate, true);
        model.embed(new Document("退货规则"));
        model.embed("如何退货");
        server.verify();
    }
}
