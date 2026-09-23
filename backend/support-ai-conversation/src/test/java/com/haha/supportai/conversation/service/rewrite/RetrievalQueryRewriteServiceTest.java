package com.haha.supportai.conversation.service.rewrite;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.conversation.config.RagProperties;
import com.haha.supportai.conversation.entity.dto.RagHistoryTurn;
import com.haha.supportai.conversation.entity.dto.RetrievalQueryDiagnostics;
import com.haha.supportai.conversation.enums.RetrievalQueryRewriteStatusEnum;
import com.haha.supportai.conversation.service.rag.ChatModelGateway;
import com.haha.supportai.conversation.support.RetrievalQueryPromptFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalQueryRewriteServiceTest {

    private ChatModelGateway gateway;
    private RagProperties properties;
    private RetrievalQueryRewriteService service;

    @BeforeEach
    void setUp() {
        gateway = mock(ChatModelGateway.class);
        properties = new RagProperties();
        service = new RetrievalQueryRewriteService(gateway, new RetrievalQueryPromptFactory(), new ObjectMapper(),
                properties);
    }

    @Test
    void returnsNoHistoryWithoutCallingModel() {
        RetrievalQueryDiagnostics result = service.rewrite("这个错误怎么解决", List.of());

        assertThat(result.getStatus()).isEqualTo(RetrievalQueryRewriteStatusEnum.NO_HISTORY);
        assertThat(result.getRetrievalQuestion()).isEqualTo("这个错误怎么解决");
        verify(gateway, never()).generate(anyString(), anyString());
    }

    @Test
    void skipsSelfContainedErrorCodeApiAndExceptionQuestions() {
        List<String> questions = List.of("ERR-1001 怎么解决", "E1001 怎么处理", "HTTP 504 怎么排查",
                "/api/users/{id} 还有哪些参数", "/v1/orders/{id} 上面接口还有哪些参数",
                "GET /internal/orders/{id} 这个接口怎么调用", "TimeoutException 怎么处理",
                "数据库连接失败怎么办");

        for (String question : questions) {
            RetrievalQueryDiagnostics result = service.rewrite(question,
                    List.of(new RagHistoryTurn("之前的问题", "之前的回答")));
            assertThat(result.getStatus()).isEqualTo(RetrievalQueryRewriteStatusEnum.SELF_CONTAINED);
        }
        verify(gateway, never()).generate(anyString(), anyString());
    }

    @Test
    void rewritesShortFollowUpWithoutExplicitReference() {
        when(gateway.generate(anyString(), anyString())).thenReturn("{\"query\":\"ERR-1001 的处理步骤\"}");

        RetrievalQueryDiagnostics result = service.rewrite("怎么办",
                List.of(new RagHistoryTurn("ERR-1001 是什么", "表示连接超时")));

        assertThat(result.getStatus()).isEqualTo(RetrievalQueryRewriteStatusEnum.REWRITTEN);
        assertThat(result.getRetrievalQuestion()).isEqualTo("ERR-1001 的处理步骤");
    }

    @Test
    void parsesStrictSingleQueryJson() {
        when(gateway.generate(anyString(), anyString())).thenReturn("{\"query\":\"ERR-1001 的处理步骤和配置位置\"}");

        RetrievalQueryDiagnostics result = service.rewrite("这个错误怎么解决",
                List.of(new RagHistoryTurn("ERR-1001 是什么", "表示连接超时")));

        assertThat(result.getStatus()).isEqualTo(RetrievalQueryRewriteStatusEnum.REWRITTEN);
        assertThat(result.getRetrievalQuestion()).isEqualTo("ERR-1001 的处理步骤和配置位置");
    }

    @Test
    void fallsBackForInvalidJsonBlankOrOverlongResult() {
        List<String> responses = List.of("不是 JSON", "{\"query\":\"\"}", "{\"query\":\"x\",\"extra\":1}",
                "{\"query\":\"第一行\\n第二行\"}", "{\"query\":\"完整问题\"} trailing");
        for (String response : responses) {
            when(gateway.generate(anyString(), anyString())).thenReturn(response);
            assertThat(service.rewrite("这个错误怎么解决", List.of(new RagHistoryTurn("问题", "回答")))
                    .getStatus()).isEqualTo(RetrievalQueryRewriteStatusEnum.FALLBACK);
        }

        when(gateway.generate(anyString(), anyString())).thenReturn("{\"query\":\"" + "x".repeat(501) + "\"}");
        assertThat(service.rewrite("这个错误怎么解决", List.of(new RagHistoryTurn("问题", "回答")))
                .getStatus()).isEqualTo(RetrievalQueryRewriteStatusEnum.FALLBACK);
    }

    @Test
    void fallsBackWhenModelFailsAndCanBeDisabled() {
        when(gateway.generate(anyString(), anyString())).thenThrow(new IllegalStateException("provider error"));
        assertThat(service.rewrite("这个错误怎么解决", List.of(new RagHistoryTurn("问题", "回答")))
                .getStatus()).isEqualTo(RetrievalQueryRewriteStatusEnum.FALLBACK);

        properties.setQueryRewriteEnabled(false);
        assertThat(service.rewrite("这个错误怎么解决", List.of(new RagHistoryTurn("问题", "回答")))
                .getStatus()).isEqualTo(RetrievalQueryRewriteStatusEnum.NOT_ATTEMPTED);
    }
}
