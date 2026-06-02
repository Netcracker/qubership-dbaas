package com.netcracker.cloud.dbaas.monitoring;

import com.netcracker.cloud.dbaas.enums.OperatorEventStatus;
import com.netcracker.cloud.dbaas.enums.OperatorEventType;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.OperatorEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.EnumMap;
import java.util.Map;

@ApplicationScoped
public class OperatorEventMetrics {

    private final Map<OperatorEventType, Counter> enqueuedCounters = new EnumMap<>(OperatorEventType.class);
    private final Map<OperatorEventType, Counter> sentCounters = new EnumMap<>(OperatorEventType.class);
    private final Map<OperatorEventType, Counter> failedCounters = new EnumMap<>(OperatorEventType.class);
    private final Map<OperatorEventType, Counter> retriedCounters = new EnumMap<>(OperatorEventType.class);

    public OperatorEventMetrics(MeterRegistry registry, OperatorEventRepository repository) {
        for (OperatorEventType type : OperatorEventType.values()) {
            String tag = type.name().toLowerCase();
            enqueuedCounters.put(type, Counter.builder("operator_outbox_enqueued_total")
                    .tag("event_type", tag).register(registry));
            sentCounters.put(type, Counter.builder("operator_outbox_sent_total")
                    .tag("event_type", tag).register(registry));
            failedCounters.put(type, Counter.builder("operator_outbox_failed_total")
                    .tag("event_type", tag).register(registry));
            retriedCounters.put(type, Counter.builder("operator_outbox_retried_total")
                    .tag("event_type", tag).register(registry));
        }

        Gauge.builder("operator_outbox_pending_count",
                repository, r -> r.countByStatus(OperatorEventStatus.PENDING))
                .register(registry);

        Gauge.builder("operator_outbox_failed_count",
                repository, r -> r.countByStatus(OperatorEventStatus.FAILED))
                .register(registry);
    }

    public void incrementEnqueued(OperatorEventType type) {
        enqueuedCounters.get(type).increment();
    }

    public void incrementSent(OperatorEventType type) {
        sentCounters.get(type).increment();
    }

    public void incrementFailed(OperatorEventType type) {
        failedCounters.get(type).increment();
    }

    public void incrementRetried(OperatorEventType type) {
        retriedCounters.get(type).increment();
    }
}
