package org.qubership.cloud.dbaas.monitoring;

import org.qubership.cloud.dbaas.monitoring.indicators.AdaptersAccessIndicator;
import org.qubership.cloud.dbaas.monitoring.indicators.HealthCheckResponse;
import org.qubership.cloud.dbaas.monitoring.indicators.HealthCheckResponse.HealthCheckResponseBuilder;
import org.qubership.cloud.dbaas.service.DbaasAdapter;
import org.qubership.cloud.dbaas.service.PhysicalDatabasesService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Supplier;

import static org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus.HEALTH_CHECK_STATUS_UP;
import static org.qubership.cloud.dbaas.monitoring.indicators.AdaptersAccessIndicator.ADAPTERS_HEALTH_CHECK_NAME;

@Slf4j
@ApplicationScoped
@IfBuildProperty(name = "dbaas.adapters.health.check.enabled", stringValue = "true", enableIfMissing = true)
public class AdapterHealthCheck {

    PhysicalDatabasesService physicalDatabasesService;
    AdaptersAccessIndicator adaptersAccessIndicator;
    MeterRegistry meterRegistry;

    public AdapterHealthCheck(PhysicalDatabasesService physicalDatabasesService,
                              AdaptersAccessIndicator adaptersAccessIndicator,
                              MeterRegistry meterRegistry) {
        this.physicalDatabasesService = physicalDatabasesService;
        this.adaptersAccessIndicator = adaptersAccessIndicator;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(every = "2m") // Every 2 Min recalculate health
    public void healthCheck() {
        final List<DbaasAdapter> adapters = physicalDatabasesService.getAllAdapters();
        HealthCheckResponseBuilder adapterHealthBuilder = HealthCheckResponse.builder().name(ADAPTERS_HEALTH_CHECK_NAME).up();
        for (DbaasAdapter adapter : adapters) {
            if (Boolean.TRUE.equals(adapter.isDisabled())) {
                log.debug("Adapter with id {} is disabled", adapter.identifier());
                continue;
            }
            AdapterHealthStatus health = adapter.getAdapterHealth();
            log.debug("check {} adapter", adapter.type());
            Supplier<Number> adapterStatusConverter = () -> convertAdapterStatus(health.getStatus());
            Gauge.builder("dbaas.adapter.health", adapterStatusConverter).tags("identifier", adapter.identifier(), "type", adapter.type()).register(meterRegistry);
            if (!HEALTH_CHECK_STATUS_UP.equals(health.getStatus())) {
                log.warn("{} has problem. Status: {}", adapter.type(), health.getStatus());
                adapterHealthBuilder.problem()
                        .details("details " + adapter.identifier(), adapter.type() + " adapter has problem.");
            }
        }
        adaptersAccessIndicator.getStatus().set(adapterHealthBuilder.build());
    }

    private Number convertAdapterStatus(String status) {
        return HEALTH_CHECK_STATUS_UP.equals(status) ? 1 : 0;
    }
}
