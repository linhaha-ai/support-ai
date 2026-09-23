package com.haha.supportai.conversation.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class ChatStreamConfiguration {

    public static final String TASK_EXECUTOR_BEAN_NAME = "chatStreamTaskExecutor";
    public static final String TIMEOUT_SCHEDULER_BEAN_NAME = "chatStreamTimeoutScheduler";

    @Bean(name = TASK_EXECUTOR_BEAN_NAME)
    ThreadPoolTaskExecutor chatStreamTaskExecutor(ChatStreamProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getPoolSize());
        executor.setMaxPoolSize(properties.getPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("support-ai-chat-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean(name = TIMEOUT_SCHEDULER_BEAN_NAME)
    ThreadPoolTaskScheduler chatStreamTimeoutScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("support-ai-chat-timeout-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
