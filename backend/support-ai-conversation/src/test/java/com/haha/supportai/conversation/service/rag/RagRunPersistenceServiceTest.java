package com.haha.supportai.conversation.service.rag;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatRunRepository;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class RagRunPersistenceServiceTest {

    private static final UUID RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID USER_MESSAGE_ID = UUID.fromString("20000000-0000-4000-8000-000000000000");
    private static final UUID MESSAGE_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final String QUESTION = "如何处理退款失败？";

    @Mock
    ChatRunRepository chatRunRepository;

    @Mock
    ChatMessageRepository chatMessageRepository;

    @Mock
    MessageSourceRepository messageSourceRepository;

    @InjectMocks
    RagRunPersistenceService persistenceService;

    @Test
    void registersAllSourcesOnlyAfterRunEntersRunningState() {
        MessageSource source = source();
        given(chatRunRepository.markRunning(any(), any(), any(), any(), anyString(), any())).willReturn(true);
        given(messageSourceRepository.insertAll(RUN_ID, List.of(source))).willReturn(1);

        persistenceService.startWithEvidence(preparedRun(), List.of(source));

        then(messageSourceRepository).should().insertAll(RUN_ID, List.of(source));
    }

    @Test
    void completesAssistantCitationsAndRunTogether() {
        given(chatMessageRepository.completeAssistant(any(), any(), any(), any())).willReturn(true);
        given(messageSourceRepository.markCited(MESSAGE_ID, List.of(1))).willReturn(1);
        given(chatRunRepository.complete(any(), any(), any(), any())).willReturn(true);

        persistenceService.complete(RUN_ID, MESSAGE_ID, "回答[1]", List.of(1), "{}");

        then(chatMessageRepository).should().completeAssistant(eq(RUN_ID), eq(MESSAGE_ID), eq("回答[1]"),
                any(OffsetDateTime.class));
        then(messageSourceRepository).should().markCited(MESSAGE_ID, List.of(1));
        then(chatRunRepository).should().complete(eq(RUN_ID), isNull(), eq("{}"), any(OffsetDateTime.class));
    }

    @Test
    void rejectsPartiallyRegisteredEvidenceSoTransactionCanRollBack() {
        MessageSource source = source();
        given(chatRunRepository.markRunning(any(), any(), any(), any(), anyString(), any())).willReturn(true);
        given(messageSourceRepository.insertAll(RUN_ID, List.of(source))).willReturn(0);

        assertThatThrownBy(() -> persistenceService.startWithEvidence(preparedRun(), List.of(source)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("问答证据在登记前已失效");
    }

    @Test
    void marksAssistantAndRunFailedWithSanitizedDetails() {
        given(chatMessageRepository.failAssistant(any(), any(), any())).willReturn(true);
        given(chatRunRepository.fail(any(), any(), any(), any())).willReturn(true);

        persistenceService.fail(RUN_ID, MESSAGE_ID, "MODEL_UNAVAILABLE", "问答模型调用失败");

        ArgumentCaptor<OffsetDateTime> failedAtCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        then(chatMessageRepository).should().failAssistant(eq(RUN_ID), eq(MESSAGE_ID), failedAtCaptor.capture());
        then(chatRunRepository).should().fail(RUN_ID, "MODEL_UNAVAILABLE", "问答模型调用失败",
                failedAtCaptor.getValue());
        assertThat(failedAtCaptor.getValue()).isNotNull();
    }

    @Test
    void persistenceBoundariesAlwaysUseIndependentTransactions() throws NoSuchMethodException {
        assertRequiresNew("startWithEvidence", PreparedChatRunResponse.class, List.class);
        assertRequiresNew("completeWithoutEvidence", PreparedChatRunResponse.class, String.class, String.class);
        assertRequiresNew("complete", UUID.class, UUID.class, String.class, List.class, String.class);
        assertRequiresNew("fail", UUID.class, UUID.class, String.class, String.class);
    }

    private void assertRequiresNew(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Transactional transactional = RagRunPersistenceService.class.getDeclaredMethod(methodName, parameterTypes)
                .getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private PreparedChatRunResponse preparedRun() {
        return new PreparedChatRunResponse(RUN_ID, SESSION_ID, USER_MESSAGE_ID, MESSAGE_ID, QUESTION,
                ChatRunStatusEnum.QUEUED, false);
    }

    private MessageSource source() {
        return new MessageSource(UUID.randomUUID(), MESSAGE_ID, null, 1, false, MessageSourceTypeEnum.DOCUMENT,
                UUID.randomUUID(), UUID.randomUUID(), "{}", "{}", OffsetDateTime.now());
    }
}
