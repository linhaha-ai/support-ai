package com.haha.supportai.knowledge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class DocumentCleanupConfiguration {

    public static final String POLL_SCHEDULER_BEAN_NAME = "documentCleanupPollScheduler";

    @Bean(name = POLL_SCHEDULER_BEAN_NAME)
    ThreadPoolTaskScheduler documentCleanupPollScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("support-ai-document-cleanup-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
