package com.haha.supportai.data.conversation.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.conversation.entity.po.ChatRun;
import com.haha.supportai.data.conversation.mapper.ChatRunMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ChatRunRepositoryTest {

    @Mock
    ChatRunMapper chatRunMapper;

    @InjectMocks
    ChatRunRepository chatRunRepository;

    @Test
    void delegatesOwnedLookupAndCancellationToMapper() {
        UUID runId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime cancelledAt = OffsetDateTime.now();
        ChatRun run = new ChatRun();
        given(chatRunMapper.selectOwnedByIdAndUserId(runId, userId)).willReturn(Optional.of(run));
        given(chatRunMapper.cancel(runId, cancelledAt)).willReturn(1);

        assertThat(chatRunRepository.findOwnedById(runId, userId)).containsSame(run);
        assertThat(chatRunRepository.cancel(runId, cancelledAt)).isTrue();

        then(chatRunMapper).should().selectOwnedByIdAndUserId(runId, userId);
        then(chatRunMapper).should().cancel(runId, cancelledAt);
    }

    @Test
    void mapsRecoveryAffectedCountWithoutTreatingItAsSingleRowUpdate() {
        OffsetDateTime startupBoundary = OffsetDateTime.now();
        OffsetDateTime recoveredAt = startupBoundary.plusSeconds(1);
        given(chatRunMapper.interruptActiveBefore(startupBoundary, recoveredAt)).willReturn(2);

        assertThat(chatRunRepository.interruptActiveBefore(startupBoundary, recoveredAt)).isEqualTo(2);

        then(chatRunMapper).should().interruptActiveBefore(startupBoundary, recoveredAt);
    }
}
