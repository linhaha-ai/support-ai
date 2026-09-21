package com.haha.supportai.knowledge.ingestion.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableScheduling
public class IngestionWorkerConfiguration {

    public static final String TASK_EXECUTOR_BEAN_NAME = "ingestionTaskExecutor";
    public static final String POLL_SCHEDULER_BEAN_NAME = "ingestionPollScheduler";
    public static final String HEARTBEAT_SCHEDULER_BEAN_NAME = "ingestionHeartbeatScheduler";

    @Bean(name = TASK_EXECUTOR_BEAN_NAME)
    ThreadPoolTaskExecutor ingestionTaskExecutor(IngestionWorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getPoolSize());
        executor.setMaxPoolSize(properties.getPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("support-ai-ingestion-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean(name = POLL_SCHEDULER_BEAN_NAME)
    ThreadPoolTaskScheduler ingestionPollScheduler() {
        return scheduler(1, "support-ai-ingestion-poll-");
    }

    @Bean(name = HEARTBEAT_SCHEDULER_BEAN_NAME)
    ThreadPoolTaskScheduler ingestionHeartbeatScheduler(IngestionWorkerProperties properties) {
        return scheduler(properties.getPoolSize(), "support-ai-ingestion-heartbeat-");
    }

    private ThreadPoolTaskScheduler scheduler(int poolSize, String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
