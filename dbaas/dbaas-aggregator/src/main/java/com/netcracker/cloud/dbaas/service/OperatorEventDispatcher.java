package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.entity.configProperty.RotationNotificationProperty;
import com.netcracker.cloud.dbaas.entity.pg.OperatorEvent;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.OperatorEventRepository;
import com.netcracker.cloud.dbaas.utils.RestClientExceptionUtil;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.cdi.SchedulerLock;
import net.javacrumbs.shedlock.core.LockAssert;

import java.time.Duration;
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
    private final OperatorWebhook operatorWebhook;
    private final OperatorEventStatusUpdater statusUpdater;
    private final Duration invisibilityTime;

    @Inject
    public OperatorEventDispatcher(RotationNotificationProperty rotationProperty,
                                   OperatorEventRepository operatorEventRepository,
                                   OperatorWebhook operatorWebhook,
                                   OperatorEventStatusUpdater statusUpdater) {
        this.rotationProperty = rotationProperty;
        this.operatorEventRepository = operatorEventRepository;
        this.operatorWebhook = operatorWebhook;
        this.statusUpdater = statusUpdater;
        this.invisibilityTime = rotationProperty.connectTimeout().plus(rotationProperty.readTimeout()).plus(Duration.ofSeconds(60));
    }

    @Scheduled(every = "${dbaas.operator.notification.dispatch-interval:5s}", concurrentExecution = SKIP)
    public void dispatch() {
        if (!rotationProperty.enabled() || rotationProperty.callbackUrl().isBlank()) {
            return;
        }

        List<OperatorEvent> batch = operatorEventRepository.claimPendingBatch(BATCH_SIZE, invisibilityTime);
        if (batch.isEmpty()) {
            return;
        }

        log.debug("Dispatching {} rotation events to operator", batch.size());

        for (OperatorEvent event : batch) {
            deliver(event);
        }
    }

    private void deliver(OperatorEvent event) {
        try {
            operatorWebhook.notify(event.getPayload());
            statusUpdater.markSent(event.getId());
        } catch (WebApplicationException e) {
            String msg = RestClientExceptionUtil.extractErrorMessage(e);
            if (RestClientExceptionUtil.is4xxError(e)) {
                log.error("Operator webhook rejected event {} (HTTP {}): {}",
                        event.getId(), e.getResponse().getStatus(), msg);
                statusUpdater.markFailed(event.getId(), msg);
            } else {
                statusUpdater.markRetry(event.getId(), msg);
            }
        } catch (Exception e) {
            statusUpdater.markRetry(event.getId(), RestClientExceptionUtil.extractErrorMessage(e));
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
            log.info("Cleaned up outbox: deleted {} sent rows older than {} and {} failed rows older than {}",
                    deletedSent, sentCutoff, deletedFailed, failedCutoff);
        }
    }
}
