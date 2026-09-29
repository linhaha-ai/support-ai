package com.haha.supportai.knowledge.ingestion.worker;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.haha.supportai.data.knowledge.enums.IngestionStageEnum;
import com.haha.supportai.knowledge.ingestion.config.IngestionWorkerProperties;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionStageResult;
import com.haha.supportai.knowledge.ingestion.entity.dto.IngestionTaskLease;
import com.haha.supportai.knowledge.ingestion.service.IngestionTaskCoordinator;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionWorkerTest {

    @Test
    void restoresTaskTraceIdWhileExecutingClaimedStage() {
        IngestionTaskCoordinator coordinator = mock(IngestionTaskCoordinator.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        IngestionStageProcessor processor = mock(IngestionStageProcessor.class);
        IngestionWorkerProperties properties = new IngestionWorkerProperties();
        properties.setHeartbeatInterval(Duration.ofSeconds(15));
        IngestionTaskLease lease = new IngestionTaskLease(UUID.randomUUID(), UUID.randomUUID(),
                IngestionStageEnum.PUBLISH, 1, 3, "worker-f30", UUID.randomUUID(),
                OffsetDateTime.now().plusMinutes(1), "trace-f30-job");
        AtomicReference<String> observedTraceId = new AtomicReference<>();
        when(processor.stage()).thenReturn(IngestionStageEnum.PUBLISH);
        when(coordinator.claim(IngestionStageEnum.PUBLISH)).thenReturn(Optional.of(lease));
        doReturn(heartbeat).when(scheduler).scheduleAtFixedRate(any(Runnable.class), any(Instant.class),
                eq(Duration.ofSeconds(15)));
        when(processor.process(lease)).thenAnswer(invocation -> {
            observedTraceId.set(MDC.get("traceId"));
            return IngestionStageResult.completed(1, 1);
        });

        new IngestionWorker(coordinator, properties, scheduler).executeOnce(processor);

        assertThat(observedTraceId.get()).isEqualTo("trace-f30-job");
        assertThat(MDC.get("traceId")).isNull();
        verify(heartbeat).cancel(false);
        verify(coordinator, never()).advance(any(), any());
    }
}
