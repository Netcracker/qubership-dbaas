package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.entity.configProperty.RotationNotificationProperty;
import com.netcracker.cloud.dbaas.entity.pg.OperatorEvent;
import com.netcracker.cloud.dbaas.monitoring.OperatorEventMetrics;
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

import java.time.OffsetDateTime;
import java.util.List;

import static com.netcracker.cloud.dbaas.enums.OperatorEventStatus.FAILED;
import static com.netcracker.cloud.dbaas.enums.OperatorEventStatus.PENDING;
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
            try {
                operatorWebhook.notify(event.getPayload());
                event.setStatus(SENT);
                event.setSentAt(OffsetDateTime.now());
                operatorEventMetrics.incrementSent(event.getEventType());
            } catch (WebApplicationException e) {
                String msg = RestClientExceptionUtil.extractErrorMessage(e);
                event.setLastError(msg);
                if (RestClientExceptionUtil.is4xxError(e)) {
                    log.error("Operator webhook rejected event {} (HTTP {}): {}",
                            event.getId(), e.getResponse().getStatus(), msg);
                    event.setStatus(FAILED);
                    operatorEventMetrics.incrementFailed(event.getEventType());
                } else {
                    scheduleRetryOrFail(event, msg);
                }
            } catch (Exception e) {
                String msg = RestClientExceptionUtil.extractErrorMessage(e);
                event.setLastError(msg);
                scheduleRetryOrFail(event, msg);
            }
            event.increaseAttempt();
            event.setLastAttemptAt(OffsetDateTime.now());
            operatorEventRepository.save(event);
        }
    }


    private void scheduleRetryOrFail(OperatorEvent event, String errorMsg) {
        int nextAttempt = event.getAttempts() + 1;
        if (nextAttempt > rotationProperty.maxAttempts()) {
            log.error("Operator webhook event {} permanently failed after {} attempt(s): {}",
                    event.getId(), nextAttempt, errorMsg);
            event.setStatus(FAILED);
            operatorEventMetrics.incrementFailed(event.getEventType());
        } else {
            log.warn("Operator webhook call failed for event {}, attempt {}/{}: {}",
                    event.getId(), nextAttempt, rotationProperty.maxAttempts(), errorMsg);
            event.setStatus(PENDING);
            event.setNextAttemptAt(OffsetDateTime.now().plus(rotationProperty.retryDelay()));
            operatorEventMetrics.incrementRetried(event.getEventType());
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
