package org.qubership.cloud.dbaas.monitoring;

import org.qubership.cloud.dbaas.monitoring.model.DatabaseMonitoringEntryStatus;
import org.qubership.cloud.dbaas.service.MonitoringService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@ApplicationScoped
@AllArgsConstructor
public class DatabaseMetricCollector {

    public static final String METRIC_NAME = "dbaas.entry.status";

    private final MonitoringService monitoringService;
    private final MeterRegistry meterRegistry;

    public void     getDatabasesInfo() {
        List<DatabaseMonitoringEntryStatus> databasesInfo = monitoringService.getDatabaseMonitoringEntryStatus();
        // each time we have to clean the metric registry fom old records because if we have a problem with adapter then
        // we get a status 'PROBLEM' and it appears as one more record in the metric registry.
        // PSUPCLFRM-6671. But we cannot rewrite it and exclude status from tag because of contract.
        meterRegistry.getMeters().stream().filter(meter -> METRIC_NAME.equals(meter.getId().getName())).forEach(meterRegistry::remove);
        databasesInfo.forEach(d -> {
                    List<Tag> databasesInfoTags = new ArrayList<>();
                    databasesInfoTags.add(Tag.of("databaseName", d.getDatabaseName()));
                    databasesInfoTags.add(Tag.of("namespace", d.getNamespace()));
                    databasesInfoTags.add(Tag.of("microservice", d.getMicroservice()));
                    databasesInfoTags.add(Tag.of("databaseType", d.getDatabaseType()));
                    databasesInfoTags.add(Tag.of("externallyManageable", d.getExternallyManageable()));
                    databasesInfoTags.add(Tag.of("status", d.getStatus()));
                    databasesInfoTags.add(Tag.of("host", d.getHost()));
                    Gauge.builder(METRIC_NAME, () -> 1).tags(databasesInfoTags).register(meterRegistry);
                }
        );
    }
}
