package com.haha.supportai.knowledge.service.document;

import com.haha.supportai.knowledge.config.DocumentCleanupProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DocumentCleanupSchedulerTest {

    @Mock
    DocumentCleanupService documentCleanupService;

    @Test
    void disabledSchedulerDoesNotPollDatabaseBackedService() {
        DocumentCleanupProperties properties = new DocumentCleanupProperties();
        properties.setEnabled(false);

        new DocumentCleanupScheduler(properties, documentCleanupService).poll();

        verify(documentCleanupService, never()).executeOnce();
    }
}
