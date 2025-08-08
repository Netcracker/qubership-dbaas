package org.qubership.cloud.dbaas.monitoring;

import org.qubership.cloud.dbaas.monitoring.model.DatabasesInfo;
import org.qubership.cloud.dbaas.monitoring.model.DatabasesInfoSegment;
import org.qubership.cloud.dbaas.service.MonitoringService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Slf4j
@ApplicationScoped
public class AdaptersMetricCollector {

    private static final String DBAAS_REGISTRATION_DATABASES = "dbaas.registration.databases";
    private static final String TOTAL_NUMBER = ".total.number";
    private final MonitoringService monitoringService;
    private final MeterRegistry meterRegistry;
    private final DatabasesMetric databasesMetric;

    public AdaptersMetricCollector(MonitoringService monitoringService, MeterRegistry meterRegistry) {
        this.monitoringService = monitoringService;
        this.meterRegistry = meterRegistry;
        this.databasesMetric = new DatabasesMetric();
    }

    @NoArgsConstructor
    private static class DatabasesMetric {
        private Integer total;
        private Integer deleting;
        private Integer totalRegistered;
        private Integer lost;
        private Integer ghost;
        private Map<String, DatabasesMetric> perAdapters = new ConcurrentHashMap<>();

        static DatabasesMetric fromDatabasesInfoSegment(DatabasesInfoSegment it) {
            DatabasesMetric metric = new DatabasesMetric();
            metric.total = it.getTotalDatabases().size();
            metric.deleting = it.getDeletingDatabases().size();
            metric.totalRegistered = it.getRegistration().getTotalDatabases().size();
            metric.ghost = it.getRegistration().getGhostDatabases().size();
            metric.lost = it.getRegistration().getLostDatabases().size();
            return metric;
        }

        private Optional<DatabasesMetric> getPerAdapterOptional(String it) {
            return Optional.ofNullable(perAdapters.get(it));
        }
    }

    public void getLostDatabases() {
        update(monitoringService.getDatabasesStatus());
        setMetricData();
    }

    private void setMetricData() {
        registrationMetric("dbaas.databases.total.number", () -> databasesMetric.total);
        registrationMetric(DBAAS_REGISTRATION_DATABASES + TOTAL_NUMBER, () -> databasesMetric.totalRegistered);
        registrationMetric(DBAAS_REGISTRATION_DATABASES + ".lost.number", () -> databasesMetric.lost);
        registrationMetric(DBAAS_REGISTRATION_DATABASES + ".ghost.number", () -> databasesMetric.ghost);
        registrationMetric(DBAAS_REGISTRATION_DATABASES + ".deleting.number", () -> databasesMetric.deleting);

        databasesMetric.perAdapters.keySet().forEach(it -> {
            registrationMetric(metricName(it, "dbaas.databases", TOTAL_NUMBER), () -> databasesMetric.getPerAdapterOptional(it).map(metr -> metr.total).orElse(0));
            registrationMetric(metricName(it, DBAAS_REGISTRATION_DATABASES, TOTAL_NUMBER), () -> databasesMetric.getPerAdapterOptional(it).map(metr -> metr.totalRegistered).orElse(0));
            registrationMetric(metricName(it, DBAAS_REGISTRATION_DATABASES, ".ghost.number"), () -> databasesMetric.getPerAdapterOptional(it).map(metr -> metr.ghost).orElse(0));
            registrationMetric(metricName(it, DBAAS_REGISTRATION_DATABASES, ".lost.number"), () -> databasesMetric.getPerAdapterOptional(it).map(metr -> metr.lost).orElse(0));
            registrationMetric(metricName(it, DBAAS_REGISTRATION_DATABASES, ".deleting.number"), () -> databasesMetric.getPerAdapterOptional(it).map(metr -> metr.deleting).orElse(0));
        });
    }

    private void registrationMetric(String name, Supplier<Number> ff) {
        Gauge.builder(name, ff).register(meterRegistry);
    }

    private String metricName(String adapterType, String pref, String postfix) {
        StringBuilder metricName = new StringBuilder();
        metricName.append(pref);
        metricName = adapterType != null ? metricName.append(".").append(adapterType) : metricName;
        metricName = metricName.append(postfix);
        return metricName.toString();
    }

    public void update(DatabasesInfo databasesStatus) {
        this.databasesMetric.total = databasesStatus.getGlobal().getTotalDatabases().size();
        this.databasesMetric.deleting = databasesStatus.getGlobal().getDeletingDatabases().size();
        this.databasesMetric.totalRegistered = databasesStatus.getGlobal().getRegistration().getTotalDatabases().size();
        this.databasesMetric.ghost = databasesStatus.getGlobal().getRegistration().getGhostDatabases().size();
        this.databasesMetric.lost = databasesStatus.getGlobal().getRegistration().getLostDatabases().size();

        this.databasesMetric.perAdapters.clear();
        databasesStatus.getPerAdapters().forEach(it ->
                this.databasesMetric.perAdapters.put(it.getName(), DatabasesMetric.fromDatabasesInfoSegment(it)));
    }
}