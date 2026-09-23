package com.haha.supportai.assistant.agent.tool.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.assistant.agent.tool.constant.AssistantToolErrorCodeConstant;
import com.haha.supportai.assistant.agent.tool.constant.AssistantToolNameConstant;
import com.haha.supportai.assistant.agent.tool.entity.dto.AssistantToolContext;
import com.haha.supportai.assistant.agent.tool.exception.AssistantToolException;
import com.haha.supportai.data.assistant.entity.po.ToolCall;
import com.haha.supportai.data.assistant.repository.ToolCallRepository;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolCallPersistenceServiceTest {

    private static final UUID RUN_ID = UUID.fromString("26200000-0000-4000-8000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("26200000-0000-4000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("26200000-0000-4000-8000-000000000003");
    private static final UUID MESSAGE_ID = UUID.fromString("26200000-0000-4000-8000-000000000004");
    private static final UUID TOOL_CALL_ID = UUID.fromString("26200000-0000-4000-8000-000000000005");

    @Mock
    ToolCallRepository toolCallRepository;

    ToolCallPersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        persistenceService = new ToolCallPersistenceService(toolCallRepository, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void startsOnlyWhitelistedToolWithServerContextAndObjectArguments() {
        when(toolCallRepository.lockStartContext(RUN_ID, SESSION_ID, USER_ID, MESSAGE_ID)).thenReturn(true);
        when(toolCallRepository.insertStarted(any(), eq(SESSION_ID), eq(USER_ID), eq(MESSAGE_ID))).thenReturn(true);

        ToolCall result = persistenceService.start(context(), AssistantToolNameConstant.SEARCH_KNOWLEDGE,
                Map.of("query", "退款失败"));

        ArgumentCaptor<ToolCall> captor = ArgumentCaptor.forClass(ToolCall.class);
        verify(toolCallRepository).lockStartContext(RUN_ID, SESSION_ID, USER_ID, MESSAGE_ID);
        verify(toolCallRepository).insertStarted(captor.capture(), eq(SESSION_ID), eq(USER_ID), eq(MESSAGE_ID));
        assertThat(result).isSameAs(captor.getValue());
        assertThat(result.getId()).isNotNull();
        assertThat(result.getRunId()).isEqualTo(RUN_ID);
        assertThat(result.getToolName()).isEqualTo(AssistantToolNameConstant.SEARCH_KNOWLEDGE);
        assertThat(result.getArguments()).contains("\"query\":\"退款失败\"").doesNotContain("userId");
    }

    @Test
    void rejectsForgedToolNameBeforeWritingAuditData() {
        assertThatThrownBy(() -> persistenceService.start(context(), "executeSql", Map.of("sql", "select 1")))
                .isInstanceOfSatisfying(AssistantToolException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AssistantToolErrorCodeConstant.TOOL_CONTEXT_INVALID));

        verifyNoInteractions(toolCallRepository);
    }

    @Test
    void completesResultAndSourcesTogetherAfterAllocatingServerCitationNumbers() {
        MessageSource source = source(TOOL_CALL_ID);
        when(toolCallRepository.lockExecutionContext(RUN_ID, SESSION_ID, USER_ID, MESSAGE_ID, TOOL_CALL_ID))
                .thenReturn(true);
        when(toolCallRepository.nextCitationNo(MESSAGE_ID)).thenReturn(3);
        when(toolCallRepository.completeSuccess(eq(RUN_ID), eq(TOOL_CALL_ID), any(), any())).thenReturn(true);
        when(toolCallRepository.insertSources(RUN_ID, SESSION_ID, USER_ID, MESSAGE_ID, List.of(source)))
                .thenReturn(1);

        persistenceService.succeed(context(), TOOL_CALL_ID, Map.of("sources", List.of("handle")), List.of(source));

        assertThat(source.getCitationNo()).isEqualTo(3);
        verify(toolCallRepository).completeSuccess(eq(RUN_ID), eq(TOOL_CALL_ID),
                eq("{\"sources\":[\"handle\"]}"), any(OffsetDateTime.class));
        verify(toolCallRepository).insertSources(RUN_ID, SESSION_ID, USER_ID, MESSAGE_ID, List.of(source));
    }

    @Test
    void rejectsSourceHandleMappedToAnotherToolCall() {
        MessageSource source = source(UUID.randomUUID());

        assertThatThrownBy(() -> persistenceService.succeed(context(), TOOL_CALL_ID, Map.of(), List.of(source)))
                .isInstanceOfSatisfying(AssistantToolException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AssistantToolErrorCodeConstant.TOOL_AUDIT_FAILED));

        verify(toolCallRepository, never()).completeSuccess(any(), any(), any(), any());
    }

    @Test
    void recordsFailedCallsWithStableErrorCode() {
        when(toolCallRepository.lockExecutionContext(RUN_ID, SESSION_ID, USER_ID, MESSAGE_ID, TOOL_CALL_ID))
                .thenReturn(true);
        when(toolCallRepository.completeFailure(eq(RUN_ID), eq(TOOL_CALL_ID),
                eq(AssistantToolErrorCodeConstant.RESOURCE_NOT_FOUND), any())).thenReturn(true);

        persistenceService.fail(context(), TOOL_CALL_ID, AssistantToolErrorCodeConstant.RESOURCE_NOT_FOUND);

        verify(toolCallRepository).completeFailure(eq(RUN_ID), eq(TOOL_CALL_ID),
                eq(AssistantToolErrorCodeConstant.RESOURCE_NOT_FOUND), any(OffsetDateTime.class));
    }

    @Test
    void persistenceTransitionsAlwaysUseIndependentTransactions() throws NoSuchMethodException {
        assertRequiresNew("start", AssistantToolContext.class, String.class, Object.class);
        assertRequiresNew("succeed", AssistantToolContext.class, UUID.class, Object.class, List.class);
        assertRequiresNew("fail", AssistantToolContext.class, UUID.class, String.class);
    }

    private void assertRequiresNew(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Transactional transactional = ToolCallPersistenceService.class.getDeclaredMethod(methodName, parameterTypes)
                .getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private AssistantToolContext context() {
        return new AssistantToolContext(RUN_ID, SESSION_ID, USER_ID, MESSAGE_ID);
    }

    private MessageSource source(UUID toolCallId) {
        return new MessageSource(UUID.randomUUID(), MESSAGE_ID, null, 0, false, MessageSourceTypeEnum.DOCUMENT,
                UUID.randomUUID(), UUID.randomUUID(), null, toolCallId, "{}", "{}", OffsetDateTime.now());
    }
}
