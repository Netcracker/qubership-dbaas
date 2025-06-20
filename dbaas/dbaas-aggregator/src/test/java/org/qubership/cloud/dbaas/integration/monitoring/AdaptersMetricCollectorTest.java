package org.qubership.cloud.dbaas.integration.monitoring;

import org.qubership.cloud.dbaas.monitoring.AdaptersMetricCollector;
import org.qubership.cloud.dbaas.monitoring.model.DatabaseInfo;
import org.qubership.cloud.dbaas.monitoring.model.DatabasesInfo;
import org.qubership.cloud.dbaas.monitoring.model.DatabasesInfoSegment;
import org.qubership.cloud.dbaas.monitoring.model.DatabasesRegistrationInfo;
import org.qubership.cloud.dbaas.service.MonitoringService;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AdaptersMetricCollectorTest {

    MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @AfterEach
    public void tearDown() {
        this.meterRegistry.clear();
    }


    @BeforeEach
    public void cleanUp() {
        this.meterRegistry.clear();
    }

    @Test
    void testAllAdaptersMetricsWereRegistered() {
        MonitoringService monitoringServiceMock = Mockito.mock(MonitoringService.class);
        Mockito.when(monitoringServiceMock.getDatabasesStatus()).thenReturn(getMonitoringInfo());

        AdaptersMetricCollector metricCollector = new AdaptersMetricCollector(monitoringServiceMock, meterRegistry);
        metricCollector.getLostDatabases();

        List<Meter> meters = meterRegistry.getMeters();

        assertEquals(3, meters.stream().filter(s -> s.getId().getName().equals("dbaas.databases.adapter.total.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(3, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.adapter.total.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(1, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.adapter.deleting.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(1, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.adapter.lost.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(1, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.adapter.ghost.number")).findFirst().get().measure().iterator().next().getValue());

        Mockito.when(monitoringServiceMock.getDatabasesStatus()).thenReturn(getMonitoringInfo2());
        metricCollector.getLostDatabases();
        meters = meterRegistry.getMeters();

        assertEquals(4, meters.stream().filter(s -> s.getId().getName().equals("dbaas.databases.total.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(5, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.total.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(2, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.deleting.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(2, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.lost.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(1, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.ghost.number")).findFirst().get().measure().iterator().next().getValue());

        assertEquals(3, meters.stream().filter(s -> s.getId().getName().equals("dbaas.databases.adapter-2.total.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(3, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.adapter-2.total.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(1, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.adapter-2.deleting.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(1, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.adapter-2.lost.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(1, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.adapter-2.ghost.number")).findFirst().get().measure().iterator().next().getValue());

        assertEquals(0, meters.stream().filter(s -> s.getId().getName().equals("dbaas.databases.adapter.total.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(0, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.adapter.total.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(0, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.adapter.deleting.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(0, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.adapter.lost.number")).findFirst().get().measure().iterator().next().getValue());
        assertEquals(0, meters.stream().filter(s -> s.getId().getName().equals("dbaas.registration.databases.adapter.ghost.number")).findFirst().get().measure().iterator().next().getValue());
    }

    private DatabasesInfo getMonitoringInfo() {
        DatabaseInfo db1 = new DatabaseInfo("db-1");
        DatabaseInfo db2 = new DatabaseInfo("db-2");
        DatabaseInfo db3 = new DatabaseInfo("db-3");
        DatabaseInfo lostDb = new DatabaseInfo("lostDb");
        DatabaseInfo ghostDb = new DatabaseInfo("ghostDb");
        DatabasesRegistrationInfo registrationInfo = new DatabasesRegistrationInfo(
                asList(db1, db2, db3),
                asList(lostDb),
                asList(ghostDb));
        DatabasesInfoSegment global = new DatabasesInfoSegment("global",
                asList(db1, db2, db3), registrationInfo,
                asList(db3));
        DatabasesInfoSegment adapter = new DatabasesInfoSegment("adapter",
                asList(db1, db2, db3), registrationInfo,
                asList(db3));

        return new DatabasesInfo(global, asList(adapter));
    }

    public static <T> List<T> asList(T... a) {
        return new ArrayList<T>(List.of(a));
    }

    private DatabasesInfo getMonitoringInfo2() {
        DatabasesInfo prevResult = getMonitoringInfo();
        prevResult.getPerAdapters().clear();

        DatabaseInfo db1 = new DatabaseInfo("db-4");
        DatabaseInfo db2 = new DatabaseInfo("db-5");
        DatabaseInfo db3 = new DatabaseInfo("db-6");
        DatabaseInfo db7 = new DatabaseInfo("db-7");
        DatabaseInfo lost2 = new DatabaseInfo("lostDb-2");
        DatabaseInfo ghost2 = new DatabaseInfo("ghostDb-2");

        DatabasesRegistrationInfo registrationInfo = new DatabasesRegistrationInfo(
                asList(db1, db2, db3),
                asList(lost2),
                asList(ghost2));
        DatabasesInfoSegment adapter = new DatabasesInfoSegment("adapter-2",
                asList(db1, db2, db3), registrationInfo,
                asList(db3));
        prevResult.getPerAdapters().add(adapter);

        prevResult.getGlobal().getDeletingDatabases().add(db7);
        prevResult.getGlobal().getTotalDatabases().add(db1);

        prevResult.getGlobal().getRegistration().getLostDatabases().add(lost2);
        prevResult.getGlobal().getRegistration().getTotalDatabases().add(db1);
        prevResult.getGlobal().getRegistration().getTotalDatabases().add(db2);

        return prevResult;
    }


}