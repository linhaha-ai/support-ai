package com.haha.supportai.knowledge.ingestion.worker;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.haha.supportai.common.support.TraceContext;
import com.haha.supportai.knowledge.ingestion.config.IngestionWorkerConfiguration;
import com.haha.supportai.knowledge.ingestion.config.IngestionWorkerProperties;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionStageResult;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import com.haha.supportai.knowledge.ingestion.exception.IngestionLeaseLostException;
import com.haha.supportai.knowledge.ingestion.exception.IngestionProcessingException;
import com.haha.supportai.knowledge.ingestion.service.IngestionTaskCoordinator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IngestionWorker {

    private final IngestionTaskCoordinator coordinator;
    private final IngestionWorkerProperties properties;
    private final TaskScheduler heartbeatScheduler;

    public IngestionWorker(IngestionTaskCoordinator coordinator, IngestionWorkerProperties properties,
            @Qualifier(IngestionWorkerConfiguration.HEARTBEAT_SCHEDULER_BEAN_NAME)
            TaskScheduler heartbeatScheduler) {
        this.coordinator = coordinator;
        this.properties = properties;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    public void executeOnce(IngestionStageProcessor processor) {
        Optional<IngestionTaskLease> claimed;
        try {
            claimed = coordinator.claim(processor.stage());
        } catch (RuntimeException exception) {
            log.error("入库任务领取失败：stage={}, errorType={}", processor.stage(),
                    exception.getClass().getSimpleName());
            return;
        }
        if (claimed.isEmpty()) {
            return;
        }
        IngestionTaskLease lease = claimed.get();
        TraceContext.run(lease.getTraceId(), () -> executeClaimed(processor, lease));
    }

    private void executeClaimed(IngestionStageProcessor processor, IngestionTaskLease lease) {
        AtomicBoolean leaseValid = new AtomicBoolean(true);
        ScheduledFuture<?> heartbeat;
        try {
            heartbeat = startHeartbeat(lease, leaseValid);
        } catch (RuntimeException exception) {
            recordFailure(lease, exception);
            return;
        }
        try {
            IngestionStageResult result;
            try {
                result = processor.process(lease);
            } finally {
                heartbeat.cancel(false);
            }
            if (result.isTerminal()) {
                return;
            }
            if (!leaseValid.get()) {
                throw new IngestionLeaseLostException();
            }
            coordinator.advance(lease, result);
        } catch (IngestionLeaseLostException exception) {
            log.warn("入库任务租约失效，忽略旧执行结果：jobId={}, stage={}", lease.getJobId(), lease.getStage());
        } catch (RuntimeException exception) {
            recordFailure(lease, exception);
        }
    }

    private ScheduledFuture<?> startHeartbeat(IngestionTaskLease lease, AtomicBoolean leaseValid) {
        Instant firstHeartbeat = Instant.now().plus(properties.getHeartbeatInterval());
        return heartbeatScheduler.scheduleAtFixedRate(
                () -> TraceContext.run(lease.getTraceId(), () -> renewLease(lease, leaseValid)),
                firstHeartbeat, properties.getHeartbeatInterval());
    }

    private void renewLease(IngestionTaskLease lease, AtomicBoolean leaseValid) {
        if (!leaseValid.get()) {
            return;
        }
        try {
            coordinator.renewLease(lease);
        } catch (IngestionLeaseLostException exception) {
            leaseValid.set(false);
            log.warn("入库任务心跳续租失败：jobId={}, stage={}", lease.getJobId(), lease.getStage());
        } catch (RuntimeException exception) {
            leaseValid.set(false);
            log.error("入库任务心跳异常：jobId={}, stage={}, errorType={}", lease.getJobId(), lease.getStage(),
                    exception.getClass().getSimpleName());
        }
    }

    private void recordFailure(IngestionTaskLease lease, RuntimeException processingException) {
        try {
            if (processingException instanceof IngestionProcessingException ingestionProcessingException) {
                coordinator.recordFailure(lease, ingestionProcessingException.getErrorCode(),
                        ingestionProcessingException.getSafeDetail(),
                        ingestionProcessingException.isAutomaticRetryAllowed());
            } else {
                coordinator.recordFailure(lease);
            }
            log.warn("入库任务处理失败：jobId={}, stage={}, errorType={}", lease.getJobId(), lease.getStage(),
                    processingException.getClass().getSimpleName());
        } catch (IngestionLeaseLostException leaseLostException) {
            log.warn("入库任务失败结果因租约失效被拒绝：jobId={}, stage={}", lease.getJobId(), lease.getStage());
        } catch (RuntimeException persistenceException) {
            log.error("入库任务失败状态写入异常：jobId={}, stage={}, errorType={}", lease.getJobId(), lease.getStage(),
                    persistenceException.getClass().getSimpleName());
        }
    }
}
