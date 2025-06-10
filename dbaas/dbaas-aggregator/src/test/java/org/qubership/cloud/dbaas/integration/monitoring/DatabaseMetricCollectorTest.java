package org.qubership.cloud.dbaas.integration.monitoring;

import org.qubership.cloud.dbaas.monitoring.DatabaseMetricCollector;
import org.qubership.cloud.dbaas.monitoring.model.DatabaseMonitoringEntryStatus;
import org.qubership.cloud.dbaas.service.MonitoringService;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;

import static org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus.*;
import static org.qubership.cloud.dbaas.monitoring.DatabaseMetricCollector.METRIC_NAME;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Slf4j
class DatabaseMetricCollectorTest {

    private MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private DatabaseMetricCollector metricCollector;
    private MonitoringService monitoringService;

    @BeforeEach
    public void setUp() {
        monitoringService = mock(MonitoringService.class);
        metricCollector = new DatabaseMetricCollector(monitoringService, meterRegistry);
        cleanUp();
    }

    @AfterEach
    public void cleanUp() {
        meterRegistry.clear();
    }

    @Test
    void getDatabasesInfo() {
        Mockito.doReturn(getMonitoringInfo(HEALTH_CHECK_STATUS_UP)).when(monitoringService).getDatabaseMonitoringEntryStatus();
        metricCollector.getDatabasesInfo();

        List<Meter> meters = meterRegistry.getMeters();
        long metersCount = meters.stream().filter(meter -> meter.getId().getName().equals(METRIC_NAME)).count();
        assertNotNull(meters);
        assertTrue(meters.stream().anyMatch(s -> s.getId().getName().equals(METRIC_NAME)
                && s.getId().getTag("namespace").equals("namespace1")
                && s.getId().getTag("databaseName").equals("databaseName1")
                && s.getId().getTag("externallyManageable").equals("true")
                && s.getId().getTag("host").equals("host1")
                && s.getId().getTag("databaseType").equals("type1")
                && s.getId().getTag("microservice").equals("ms1")
                && s.getId().getTag("status").equals(HEALTH_CHECK_STATUS_UP)));
        assertTrue(meters.stream().anyMatch(s -> s.getId().getName().equals(METRIC_NAME)
                && s.getId().getTag("namespace").equals("namespace2")
                && s.getId().getTag("databaseName").equals("databaseName2")
                && s.getId().getTag("externallyManageable").equals("false")
                && s.getId().getTag("host").equals("host2")
                && s.getId().getTag("databaseType").equals("type2")
                && s.getId().getTag("microservice").equals("ms2")
                && s.getId().getTag("status").equals(HEALTH_CHECK_STATUS_UP)));
        assertTrue(meters.stream().anyMatch(s -> s.getId().getName().equals(METRIC_NAME)
                && s.getId().getTag("namespace").equals("namespace3")
                && s.getId().getTag("databaseName").equals("databaseName3")
                && s.getId().getTag("externallyManageable").equals("false")
                && s.getId().getTag("host").equals("host3")
                && s.getId().getTag("databaseType").equals("type3")
                && s.getId().getTag("microservice").equals("ms3")
                && s.getId().getTag("status").equals(HEALTH_CHECK_STATUS_UNKNOWN)));

        Mockito.doReturn(getMonitoringInfo(HEALTH_CHECK_STATUS_PROBLEM)).when(monitoringService).getDatabaseMonitoringEntryStatus();
        metricCollector.getDatabasesInfo();
        meters = meterRegistry.getMeters();
        assertNotNull(meters);
        assertEquals(metersCount, meters.stream().filter(meter -> meter.getId().getName().equals(METRIC_NAME)).count()); // number of records is not changed
        assertTrue(meters.stream().anyMatch(s -> s.getId().getName().equals(METRIC_NAME)
                && s.getId().getTag("namespace").equals("namespace1")
                && s.getId().getTag("databaseName").equals("databaseName1")
                && s.getId().getTag("externallyManageable").equals("true")
                && s.getId().getTag("host").equals("host1")
                && s.getId().getTag("databaseType").equals("type1")
                && s.getId().getTag("microservice").equals("ms1")
                && s.getId().getTag("status").equals(HEALTH_CHECK_STATUS_PROBLEM)));

    }

    private List<DatabaseMonitoringEntryStatus> getMonitoringInfo(String status) {
        DatabaseMonitoringEntryStatus db1 = DatabaseMonitoringEntryStatus.builder().namespace("namespace1")
                .databaseType("type1").databaseName("databaseName1").host("host1").microservice("ms1")
                .status(status).externallyManageable("true").build();
        DatabaseMonitoringEntryStatus db2 = DatabaseMonitoringEntryStatus.builder().namespace("namespace2")
                .databaseType("type2").databaseName("databaseName2").host("host2")
                .microservice("ms2").status(HEALTH_CHECK_STATUS_UP).externallyManageable("false").build();
        DatabaseMonitoringEntryStatus db3 = DatabaseMonitoringEntryStatus.builder().namespace("namespace3")
                .databaseType("type3").databaseName("databaseName3").host("host3")
                .microservice("ms3").status(HEALTH_CHECK_STATUS_UNKNOWN).externallyManageable("false").build();

        return Arrays.asList(db1, db2, db3);
    }
}