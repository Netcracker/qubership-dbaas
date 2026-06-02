package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.entity.configProperty.RotationNotificationProperty;
import com.netcracker.cloud.dbaas.entity.pg.OperatorEvent;
import com.netcracker.cloud.dbaas.enums.OperatorEventStatus;
import com.netcracker.cloud.dbaas.monitoring.OperatorEventMetrics;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.OperatorEventRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.cdi.SchedulerLock;
import net.javacrumbs.shedlock.core.LockAssert;

import java.time.OffsetDateTime;
import java.util.List;

import static com.netcracker.cloud.dbaas.enums.OperatorEventStatus.FAILED;
import static com.netcracker.cloud.dbaas.enums.OperatorEventStatus.SENT;
import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

@Slf4j
@ApplicationScoped
public class OperatorEventDispatcher {
    private static final int BATCH_SIZE = 50;

    private final RotationNotificationProperty rotationProperty;
    private final OperatorEventRepository operatorEventRepository;
    private final OperatorEventMetrics operatorEventMetrics;
    private final OperatorWebhook operatorWebhook;

    @Inject
    public OperatorEventDispatcher(RotationNotificationProperty rotationProperty,
                                   OperatorEventRepository operatorEventRepository,
                                   OperatorEventMetrics operatorEventMetrics,
                                   OperatorWebhook operatorWebhook
    ) {
        this.rotationProperty = rotationProperty;
        this.operatorEventRepository = operatorEventRepository;
        this.operatorEventMetrics = operatorEventMetrics;
        this.operatorWebhook = operatorWebhook;
    }

    @Scheduled(every = "${dbaas.operator.notification.dispatch-interval:5s}", concurrentExecution = SKIP)
    @Transactional
    public void dispatch() {
        if (!rotationProperty.enabled() || rotationProperty.callbackUrl().isBlank()) {
            return;
        }

        List<OperatorEvent> batch = operatorEventRepository.claimPendingBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        log.debug("Dispatching {} rotation events to operator", batch.size());

        for (OperatorEvent event : batch) {
            operatorWebhook.notify(event.getPayload());
            event.setStatus(SENT);
            event.setSentAt(OffsetDateTime.now());
            operatorEventMetrics.incrementSent(event.getEventType());
            operatorEventRepository.persist(event);
        }
    }


    @Scheduled(every = "1h", concurrentExecution = SKIP)
    @SchedulerLock(name = "operatorEventCleanup")
    @Transactional
    public void cleanup() {
        LockAssert.assertLocked();

        OffsetDateTime sentCutoff = OffsetDateTime.now().minus(rotationProperty.sentTtl());
        long deletedSent = operatorEventRepository.deleteByStatusAndSentAtBefore(SENT, sentCutoff);

        OffsetDateTime failedCutoff = OffsetDateTime.now().minus(rotationProperty.failedTtl());
        long deletedFailed = operatorEventRepository.deleteByStatusAndCreatedAtBefore(FAILED, failedCutoff);

        if (deletedSent > 0 || deletedFailed > 0) {
            log.info("Cleaned up outbox: deleted {} sent rows older than {}, {} failed rows older than {}",
                    deletedSent, sentCutoff, deletedFailed, failedCutoff);
        }
    }

    private void handleFailure(OperatorEvent event, String errorMessage) {
        event.setAttempts(event.getAttempts() + 1);
        event.setLastError(errorMessage);
        event.setLastAttemptAt(OffsetDateTime.now());

        if (event.getAttempts() >= rotationProperty.maxAttempts()) {
            event.setStatus(OperatorEventStatus.FAILED);
            operatorEventMetrics.incrementFailed(event.getEventType());
            log.warn("Rotation event id={} permanently failed after {} attempts: {}",
                    event.getId(), event.getAttempts(), errorMessage);
        } else {
            event.setNextAttemptAt(OffsetDateTime.now().plus(rotationProperty.retryDelay()));
            operatorEventMetrics.incrementRetried(event.getEventType());
            log.debug("Rotation event id={} attempt={} failed, retry at {}: {}",
                    event.getId(), event.getAttempts(), event.getNextAttemptAt(), errorMessage);
        }
    }
}
