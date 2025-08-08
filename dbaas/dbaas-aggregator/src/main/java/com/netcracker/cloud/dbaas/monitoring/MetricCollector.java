package org.qubership.cloud.dbaas.monitoring;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
@AllArgsConstructor
@IfBuildProperty(name = "dbaas.metrics.enabled", stringValue = "true", enableIfMissing = true)
public class MetricCollector {

    private final DatabaseMetricCollector databaseMetricCollector;
    private final AdaptersMetricCollector adaptersMetricCollector;

    @Scheduled(every = "10m")
    public void runScheduledCollecting() {
        log.debug("Metric collect");
        databaseMetricCollector.getDatabasesInfo();
        adaptersMetricCollector.getLostDatabases();
    }
}
