package com.haha.supportai.knowledge.ingestion.worker;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.knowledge.ingestion.config.IngestionWorkerConfiguration;
import com.haha.supportai.knowledge.ingestion.config.IngestionWorkerProperties;
import com.haha.supportai.knowledge.ingestion.service.IngestionTaskCoordinator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IngestionWorkerScheduler {

    private final IngestionWorkerProperties properties;
    private final List<IngestionStageProcessor> processors;
    private final IngestionWorker worker;
    private final IngestionTaskCoordinator coordinator;
    private final ThreadPoolTaskExecutor taskExecutor;

    public IngestionWorkerScheduler(IngestionWorkerProperties properties, List<IngestionStageProcessor> processors,
            IngestionWorker worker, IngestionTaskCoordinator coordinator,
            @Qualifier(IngestionWorkerConfiguration.TASK_EXECUTOR_BEAN_NAME)
            ThreadPoolTaskExecutor taskExecutor) {
        this.properties = properties;
        this.processors = List.copyOf(processors);
        this.worker = worker;
        this.coordinator = coordinator;
        this.taskExecutor = taskExecutor;
        requireUniqueStages(this.processors);
    }

    @Scheduled(
            fixedDelayString = "#{@ingestionWorkerProperties.pollInterval.toMillis()}",
            initialDelayString = "#{@ingestionWorkerProperties.pollInterval.toMillis()}",
            scheduler = IngestionWorkerConfiguration.POLL_SCHEDULER_BEAN_NAME)
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            coordinator.recoverExpiredExhaustedTasks();
        } catch (RuntimeException exception) {
            log.error("过期入库任务恢复失败：errorType={}", exception.getClass().getSimpleName());
            return;
        }
        for (IngestionStageProcessor processor : processors) {
            submit(processor);
        }
    }

    private void submit(IngestionStageProcessor processor) {
        try {
            taskExecutor.execute(() -> worker.executeOnce(processor));
        } catch (TaskRejectedException exception) {
            log.debug("入库任务执行队列已满，本轮跳过阶段：stage={}", processor.stage());
        }
    }

    private void requireUniqueStages(List<IngestionStageProcessor> stageProcessors) {
        Set<IngestionStageEnum> stages = new HashSet<>();
        for (IngestionStageProcessor processor : stageProcessors) {
            if (!stages.add(processor.stage())) {
                throw new IllegalStateException("同一入库阶段只能注册一个处理器");
            }
        }
    }
}
