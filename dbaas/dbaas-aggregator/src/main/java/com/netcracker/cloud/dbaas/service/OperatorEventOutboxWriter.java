package com.netcracker.cloud.dbaas.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.netcracker.cloud.dbaas.entity.pg.OperatorEvent;
import com.netcracker.cloud.dbaas.enums.OperatorEventStatus;
import com.netcracker.cloud.dbaas.enums.OperatorEventType;
import com.netcracker.cloud.dbaas.entity.dto.RotationEventPayload;
import com.netcracker.cloud.dbaas.entity.configProperty.RotationNotificationProperty;
import com.netcracker.cloud.dbaas.monitoring.OperatorEventMetrics;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.OperatorEventRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.util.SortedMap;
import java.util.UUID;

import static jakarta.transaction.Transactional.TxType.MANDATORY;

@Slf4j
@ApplicationScoped
public class OperatorEventOutboxWriter {
    private final RotationNotificationProperty rotationProperty;
    private final OperatorEventRepository operatorEventRepository;
    private final OperatorEventMetrics operatorEventMetrics;
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .build();

    @Inject
    public OperatorEventOutboxWriter(RotationNotificationProperty rotationProperty,
                                     OperatorEventRepository operatorEventRepository,
                                     OperatorEventMetrics operatorEventMetrics
                                     )
    {
        this.rotationProperty = rotationProperty;
        this.operatorEventRepository = operatorEventRepository;
        this.operatorEventMetrics = operatorEventMetrics;
    }

    /**
     * Enqueues a rotation notification event into the outbox.
     * Must be called inside an active transaction — if persistence fails the caller's transaction rolls back.
     */
    @Transactional(MANDATORY)
    public void enqueue(OperatorEventType eventType, SortedMap<String, Object> classifier, String type, String role) {
        if (!rotationProperty.enabled()) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime previousRotatedAt = lookupPreviousRotatedAt(classifier, type, role);

        RotationEventPayload payload = new RotationEventPayload(
                UUID.randomUUID(),
                now,
                previousRotatedAt,
                classifier,
                type,
                role
        );

        OperatorEvent event = new OperatorEvent();
        event.setId(UUID.randomUUID());
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setStatus(OperatorEventStatus.PENDING);
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);

        operatorEventRepository.persist(event);
        operatorEventMetrics.incrementEnqueued(eventType);
        log.debug("Enqueued rotation event id={} type={} classifier={}", event.getId(), eventType, classifier);
    }

    private OffsetDateTime lookupPreviousRotatedAt(SortedMap<String, Object> classifier, String type, String role) {
        try {
            String classifierJson = objectMapper.writeValueAsString(classifier);
            return operatorEventRepository.findPreviousOccurredAt(classifierJson, type, role);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize classifier for previousRotatedAt lookup, using null", e);
            return null;
        }
    }
}
