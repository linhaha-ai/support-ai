package com.haha.supportai.conversation.service.run;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatRunRecoveryRunnerTest {

    @Test
    void startupRecoveryDelegatesUsingCapturedStartupBoundary() throws Exception {
        ChatRunControlPersistenceService persistenceService = Mockito.mock(ChatRunControlPersistenceService.class);
        when(persistenceService.recoverInterrupted(any())).thenReturn(1);
        ChatRunRecoveryRunner runner = new ChatRunRecoveryRunner(persistenceService);

        runner.run(new DefaultApplicationArguments());

        verify(persistenceService).recoverInterrupted(any());
    }
}
