package com.haha.supportai.knowledge.service.document;

import com.haha.supportai.knowledge.config.DocumentCleanupConfiguration;
import com.haha.supportai.knowledge.config.DocumentCleanupProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentCleanupScheduler {

    private final DocumentCleanupProperties properties;
    private final DocumentCleanupService documentCleanupService;

    @Scheduled(
            fixedDelayString = "#{@documentCleanupProperties.pollInterval.toMillis()}",
            initialDelayString = "#{@documentCleanupProperties.pollInterval.toMillis()}",
            scheduler = DocumentCleanupConfiguration.POLL_SCHEDULER_BEAN_NAME)
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            documentCleanupService.executeOnce();
        } catch (RuntimeException exception) {
            log.error("文档清理任务轮询失败：errorType={}", exception.getClass().getSimpleName());
        }
    }
}
