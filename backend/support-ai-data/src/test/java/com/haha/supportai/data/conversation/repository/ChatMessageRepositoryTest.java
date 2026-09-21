package com.haha.supportai.data.conversation.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.haha.supportai.data.conversation.mapper.ChatMessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ChatMessageRepositoryTest {

    @Mock
    ChatMessageMapper chatMessageMapper;

    @InjectMocks
    ChatMessageRepository chatMessageRepository;

    @Test
    void delegatesCancellationAndInterruptedAssistantRecoveryToMapper() {
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        OffsetDateTime recoveredAt = OffsetDateTime.now();
        given(chatMessageMapper.cancelAssistant(runId, messageId, recoveredAt)).willReturn(1);
        given(chatMessageMapper.interruptAssistants(recoveredAt)).willReturn(3);

        assertThat(chatMessageRepository.cancelAssistant(runId, messageId, recoveredAt)).isTrue();
        assertThat(chatMessageRepository.interruptAssistants(recoveredAt)).isEqualTo(3);

        then(chatMessageMapper).should().cancelAssistant(runId, messageId, recoveredAt);
        then(chatMessageMapper).should().interruptAssistants(recoveredAt);
    }
}
