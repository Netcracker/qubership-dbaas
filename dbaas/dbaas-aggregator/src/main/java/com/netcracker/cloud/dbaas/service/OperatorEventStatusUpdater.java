package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.entity.configProperty.RotationNotificationProperty;
import com.netcracker.cloud.dbaas.entity.pg.OperatorEvent;
import com.netcracker.cloud.dbaas.monitoring.OperatorEventMetrics;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.OperatorEventRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.netcracker.cloud.dbaas.enums.OperatorEventStatus.*;
import static jakarta.transaction.Transactional.TxType.REQUIRES_NEW;

@Slf4j
@ApplicationScoped
public class OperatorEventStatusUpdater {

    private final OperatorEventRepository operatorEventRepository;
    private final OperatorEventMetrics operatorEventMetrics;
    private final RotationNotificationProperty rotationProperty;

    @Inject
    public OperatorEventStatusUpdater(OperatorEventRepository operatorEventRepository,
                                      OperatorEventMetrics operatorEventMetrics,
                                      RotationNotificationProperty rotationProperty) {
        this.operatorEventRepository = operatorEventRepository;
        this.operatorEventMetrics = operatorEventMetrics;
        this.rotationProperty = rotationProperty;
    }

    @Transactional(REQUIRES_NEW)
    public void markSent(UUID id) {
        OperatorEvent event = operatorEventRepository.findById(id);
        if (event == null) {
            log.warn("markSent: event {} not found, skipping", id);
            return;
        }
        event.setStatus(SENT);
        event.setSentAt(OffsetDateTime.now());
        stamp(event);
        operatorEventRepository.save(event);
        operatorEventMetrics.incrementSent(event.getEventType());
    }

    @Transactional(REQUIRES_NEW)
    public void markFailed(UUID id, String msg) {
        OperatorEvent event = operatorEventRepository.findById(id);
        if (event == null) {
            log.warn("markFailed: event {} not found, skipping", id);
            return;
        }
        event.setStatus(FAILED);
        event.setLastError(msg);
        stamp(event);
        operatorEventRepository.save(event);
        operatorEventMetrics.incrementFailed(event.getEventType());
    }

    /**
     * Transitions to PENDING with a real retry delay, or to FAILED permanently if attempts are exhausted.
     * Overwrites the invisibility window set by claimPendingBatch with the configured retryDelay.
     */
    @Transactional(REQUIRES_NEW)
    public void markRetry(UUID id, String msg) {
        OperatorEvent event = operatorEventRepository.findById(id);
        if (event == null) {
            log.warn("markRetry: event {} not found, skipping", id);
            return;
        }
        event.setLastError(msg);
        if (event.getAttempts() >= rotationProperty.maxAttempts()) {
            log.error("Operator webhook event {} permanently failed after {} attempt(s): {}",
                    id, event.getAttempts(), msg);
            event.setStatus(FAILED);
            stamp(event);
            operatorEventRepository.save(event);
            operatorEventMetrics.incrementFailed(event.getEventType());
        } else {
            log.warn("Operator webhook call failed for event {}, attempt {}/{}: {}",
                    id, event.getAttempts(), rotationProperty.maxAttempts(), msg);
            event.setStatus(PENDING);
            event.setNextAttemptAt(OffsetDateTime.now().plus(rotationProperty.retryDelay()));
            stamp(event);
            operatorEventRepository.save(event);
            operatorEventMetrics.incrementRetried(event.getEventType());
        }
    }

    private void stamp(OperatorEvent event) {
        event.increaseAttempt();
        event.setLastAttemptAt(OffsetDateTime.now());
    }
}
