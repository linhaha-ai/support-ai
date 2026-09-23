package com.haha.supportai.assistant.agent.tool.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.assistant.agent.tool.constant.AssistantToolErrorCodeConstant;
import com.haha.supportai.assistant.agent.tool.constant.AssistantToolNameConstant;
import com.haha.supportai.assistant.agent.tool.entity.dto.AssistantToolContext;
import com.haha.supportai.assistant.agent.tool.entity.dto.SearchKnowledgeToolRequest;
import com.haha.supportai.assistant.agent.tool.entity.dto.SearchKnowledgeToolResult;
import com.haha.supportai.assistant.agent.tool.exception.AssistantToolException;
import com.haha.supportai.assistant.agent.tool.support.ToolSourceFactory;
import com.haha.supportai.data.assistant.entity.po.ToolCall;
import com.haha.supportai.data.assistant.enums.ToolCallStatusEnum;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.knowledge.entity.vo.KnowledgeRetrievalEvidenceResponse;
import com.haha.supportai.knowledge.service.retrieval.KnowledgeRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchKnowledgeToolAdapterTest {

    private static final UUID RUN_ID = UUID.fromString("26000000-0000-4000-8000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("26000000-0000-4000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("26000000-0000-4000-8000-000000000003");
    private static final UUID MESSAGE_ID = UUID.fromString("26000000-0000-4000-8000-000000000004");
    private static final UUID TOOL_CALL_ID = UUID.fromString("26000000-0000-4000-8000-000000000005");
    private static final UUID SOURCE_ID = UUID.fromString("26000000-0000-4000-8000-000000000006");

    @Mock
    KnowledgeRetrievalService knowledgeRetrievalService;

    @Mock
    ToolCallPersistenceService persistenceService;

    @Mock
    ToolSourceFactory sourceFactory;

    SearchKnowledgeToolAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SearchKnowledgeToolAdapter(knowledgeRetrievalService, persistenceService, sourceFactory);
    }

    @Test
    void returnsOnlyEvidenceAndServerGeneratedSourceHandleAfterAuditSucceeds() {
        AssistantToolContext context = context();
        ToolCall toolCall = toolCall();
        KnowledgeRetrievalEvidenceResponse evidence = evidence();
        MessageSource source = documentSource();
        when(persistenceService.start(eq(context), eq(AssistantToolNameConstant.SEARCH_KNOWLEDGE), any()))
                .thenReturn(toolCall);
        when(knowledgeRetrievalService.retrieveReadableWithinSession(USER_ID, SESSION_ID, "退款失败", 5))
                .thenReturn(List.of(evidence));
        when(sourceFactory.document(context, TOOL_CALL_ID, evidence)).thenReturn(source);

        SearchKnowledgeToolResult result = adapter.execute(context, new SearchKnowledgeToolRequest(" 退款失败 "));

        assertThat(result.getSources()).singleElement().satisfies(item -> {
            assertThat(item.getSourceHandle()).isEqualTo(SOURCE_ID);
            assertThat(item.getDocumentName()).isEqualTo("退款处理规范.md");
            assertThat(item.getContent()).isEqualTo("退款失败需要先核对渠道流水。");
        });
        verify(persistenceService).succeed(eq(context), eq(TOOL_CALL_ID), eq(result), eq(List.of(source)));
        verify(persistenceService, never()).fail(any(), any(), any());
    }

    @Test
    void recordsInvalidArgumentsAsFailedCallWithoutInvokingRetrieval() {
        AssistantToolContext context = context();
        when(persistenceService.start(eq(context), eq(AssistantToolNameConstant.SEARCH_KNOWLEDGE), any()))
                .thenReturn(toolCall());

        assertThatThrownBy(() -> adapter.execute(context, new SearchKnowledgeToolRequest(" ")))
                .isInstanceOfSatisfying(AssistantToolException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AssistantToolErrorCodeConstant.INVALID_REQUEST));

        verify(persistenceService).fail(context, TOOL_CALL_ID, AssistantToolErrorCodeConstant.INVALID_REQUEST);
        verify(knowledgeRetrievalService, never()).retrieveReadableWithinSession(any(), any(), any(), any(Integer.class));
    }

    @Test
    void recordsScopeOrRetrievalFailureWithoutReturningInventedEvidence() {
        AssistantToolContext context = context();
        when(persistenceService.start(eq(context), eq(AssistantToolNameConstant.SEARCH_KNOWLEDGE), any()))
                .thenReturn(toolCall());
        when(knowledgeRetrievalService.retrieveReadableWithinSession(USER_ID, SESSION_ID, "越权问题", 5))
                .thenThrow(new IllegalStateException("会话范围已失效"));

        assertThatThrownBy(() -> adapter.execute(context, new SearchKnowledgeToolRequest("越权问题")))
                .isInstanceOfSatisfying(AssistantToolException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AssistantToolErrorCodeConstant.TOOL_UNAVAILABLE));

        verify(persistenceService).fail(context, TOOL_CALL_ID, AssistantToolErrorCodeConstant.TOOL_UNAVAILABLE);
        verify(persistenceService, never()).succeed(any(), any(), any(), any());
    }

    private AssistantToolContext context() {
        return new AssistantToolContext(RUN_ID, SESSION_ID, USER_ID, MESSAGE_ID);
    }

    private ToolCall toolCall() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-23T10:00:00Z");
        return new ToolCall(TOOL_CALL_ID, RUN_ID, 1, null, AssistantToolNameConstant.SEARCH_KNOWLEDGE,
                "{}", null, ToolCallStatusEnum.RUNNING, null, now, null);
    }

    private KnowledgeRetrievalEvidenceResponse evidence() {
        return new KnowledgeRetrievalEvidenceResponse(UUID.randomUUID(), UUID.randomUUID(), "退款处理规范.md",
                UUID.randomUUID(), 2, UUID.randomUUID(), 0, "退款", null, 0, 16,
                "退款失败需要先核对渠道流水。", 0.9D);
    }

    private MessageSource documentSource() {
        return new MessageSource(SOURCE_ID, MESSAGE_ID, null, 0, false, MessageSourceTypeEnum.DOCUMENT,
                UUID.randomUUID(), UUID.randomUUID(), null, TOOL_CALL_ID, "{}", "{}", OffsetDateTime.now());
    }
}
