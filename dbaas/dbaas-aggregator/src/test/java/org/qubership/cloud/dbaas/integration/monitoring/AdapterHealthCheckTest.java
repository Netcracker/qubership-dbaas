package org.qubership.cloud.dbaas.integration.monitoring;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.monitoring.AdapterHealthCheck;
import org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus;
import org.qubership.cloud.dbaas.monitoring.indicators.AdaptersAccessIndicator;
import org.qubership.cloud.dbaas.monitoring.indicators.HealthCheckResponse;
import org.qubership.cloud.dbaas.monitoring.indicators.HealthStatus;
import org.qubership.cloud.dbaas.rest.DbaasAdapterRestClientV2;
import org.qubership.cloud.dbaas.service.AdapterActionTrackerClient;
import org.qubership.cloud.dbaas.service.DbaasAdapter;
import org.qubership.cloud.dbaas.service.DbaasAdapterRESTClientV2;
import org.qubership.cloud.dbaas.service.PhysicalDatabasesService;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus.HEALTH_CHECK_STATUS_PROBLEM;
import static org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus.HEALTH_CHECK_STATUS_UP;


@Slf4j
@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class AdapterHealthCheckTest {

    private static final String DBAAS_ADAPTER_HEALTH = "dbaas.adapter.health";
    private static final String IDENTIFIER = "identifier";
    private static final String TYPE = "type";

    private static final AdapterHealthStatus HEALTH_STATUS_PROBLEM = new AdapterHealthStatus(HEALTH_CHECK_STATUS_PROBLEM);
    private static final AdapterHealthStatus HEALTH_STATUS_UP = new AdapterHealthStatus(HEALTH_CHECK_STATUS_UP);

    @Inject
    MeterRegistry meterRegistry;
    @InjectMock
    PhysicalDatabasesService physicalDatabasesService;
    @InjectSpy
    AdaptersAccessIndicator adaptersAccessIndicator;

    @BeforeEach
    public void cleanUp() {
        this.meterRegistry.clear();
    }

    @Test
    void testAllAdaptersHealthStatusesRegistered() {
        DbaasAdapterRestClientV2 adapterRestClientV2 = mock(DbaasAdapterRestClientV2.class);
        when(adapterRestClientV2.getHealth()).thenThrow(new RuntimeException());
        DbaasAdapter adapter1 = new DbaasAdapterRESTClientV2("address", "type1",
                adapterRestClientV2, "identifier1", mock(AdapterActionTrackerClient.class));
        DbaasAdapter adapter2 = new DbaasAdapterRESTClientV2("address", "type2",
                adapterRestClientV2, "identifier2", mock(AdapterActionTrackerClient.class));
        DbaasAdapter adapter3 = new DbaasAdapterRESTClientV2("address", "type3",
                adapterRestClientV2, "identifier3", mock(AdapterActionTrackerClient.class));
        when(physicalDatabasesService.getAllAdapters()).thenReturn(Arrays.asList(adapter1, adapter2, adapter3));
        when(adaptersAccessIndicator.getStatus()).thenReturn(new AtomicReference<>());
        AdapterHealthCheck adapterHealthCheck = new AdapterHealthCheck(physicalDatabasesService, adaptersAccessIndicator,  meterRegistry);
        adapterHealthCheck.healthCheck();

        List<Meter> meters = meterRegistry.getMeters();
        assertNotNull(meters);
        assertTrue(meters.stream().anyMatch(s -> s.getId().getName().equals(DBAAS_ADAPTER_HEALTH)
                && Objects.equals(s.getId().getTag(IDENTIFIER), "identifier1")
                && Objects.equals(s.getId().getTag(TYPE), "type1")));
        assertTrue(meters.stream().anyMatch(s -> s.getId().getName().equals(DBAAS_ADAPTER_HEALTH)
                && Objects.equals(s.getId().getTag(IDENTIFIER), "identifier2")
                && Objects.equals(s.getId().getTag(TYPE), "type2")));
        assertTrue(meters.stream().anyMatch(s -> s.getId().getName().equals(DBAAS_ADAPTER_HEALTH)
                && Objects.equals(s.getId().getTag(IDENTIFIER), "identifier3")
                && Objects.equals(s.getId().getTag(TYPE), "type3")));
    }

    @Test
    void checkAdapterStatusAllUp() {
        DbaasAdapter adapter1 = getMockedDbaasAdapter(HEALTH_STATUS_UP, "AllUp_1");
        DbaasAdapter adapter2 = getMockedDbaasAdapter(HEALTH_STATUS_UP, "AllUp_2");
        when(physicalDatabasesService.getAllAdapters()).thenReturn(Arrays.asList(adapter1, adapter2));

        AdapterHealthCheck adapterHealthCheck = new AdapterHealthCheck(physicalDatabasesService, adaptersAccessIndicator,  meterRegistry);
        adapterHealthCheck.healthCheck();

        HealthCheckResponse health = adaptersAccessIndicator.getStatus().get();
        Assertions.assertEquals(HealthStatus.UP, health.getStatus());
        Assertions.assertNull(health.getDetails());
    }

    @Test
    void checkAdapterStatusUpAndProblem() {
        DbaasAdapter adapter1 = getMockedDbaasAdapter(HEALTH_STATUS_UP, "UpAndProblem_UP");
        DbaasAdapter adapter2 = getMockedDbaasAdapter(HEALTH_STATUS_PROBLEM, "UpAndProblem_PROBLEM");
        when(physicalDatabasesService.getAllAdapters()).thenReturn(Arrays.asList(adapter1, adapter2));

        AdapterHealthCheck adapterHealthCheck = new AdapterHealthCheck(physicalDatabasesService, adaptersAccessIndicator,  meterRegistry);
        adapterHealthCheck.healthCheck();

        HealthCheckResponse health = adaptersAccessIndicator.getStatus().get();
        log.info("data {}", health.getDetails());
        Assertions.assertEquals(HealthStatus.PROBLEM, health.getStatus());
        Assertions.assertEquals(1, health.getDetails().size());
    }

    @Test
    void checkAdapterStatusProblemAndProblem() {
        DbaasAdapter adapter1 = getMockedDbaasAdapter(HEALTH_STATUS_PROBLEM, "ProblemAndProblem_PROBLEM_1");
        DbaasAdapter adapter2 = getMockedDbaasAdapter(HEALTH_STATUS_PROBLEM, "ProblemAndProblem_PROBLEM_2");
        when(physicalDatabasesService.getAllAdapters()).thenReturn(Arrays.asList(adapter1, adapter2));

        AdapterHealthCheck adapterHealthCheck = new AdapterHealthCheck(physicalDatabasesService, adaptersAccessIndicator,  meterRegistry);
        adapterHealthCheck.healthCheck();

        HealthCheckResponse health = adaptersAccessIndicator.getStatus().get();
        log.info("data {}", health.getDetails());
        Assertions.assertEquals(HealthStatus.PROBLEM, health.getStatus());
        Assertions.assertEquals(2, health.getDetails().size());
    }

    @NotNull
    private DbaasAdapter getMockedDbaasAdapter(AdapterHealthStatus health, String name) {
        DbaasAdapter adapter1 = mock(DbaasAdapter.class);
        when(adapter1.getAdapterHealth()).thenReturn(health);
        when(adapter1.identifier()).thenReturn(name);
        when(adapter1.type()).thenReturn("postgresql");
        when(adapter1.toString()).thenReturn(name);
        return adapter1;
    }
}
