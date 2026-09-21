package com.haha.supportai.conversation.service.run;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ChatRunRecoveryRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatRunRecoveryRunner.class);

    private final ChatRunControlPersistenceService persistenceService;
    private final OffsetDateTime startupBoundary;

    public ChatRunRecoveryRunner(ChatRunControlPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
        this.startupBoundary = OffsetDateTime.now();
    }

    @Override
    public void run(ApplicationArguments arguments) {
        int interruptedRuns = persistenceService.recoverInterrupted(startupBoundary);
        if (interruptedRuns > 0) {
            LOGGER.warn("应用启动时已将 {} 个遗留问答运行收口为 RUN_INTERRUPTED", interruptedRuns);
        }
    }
}
