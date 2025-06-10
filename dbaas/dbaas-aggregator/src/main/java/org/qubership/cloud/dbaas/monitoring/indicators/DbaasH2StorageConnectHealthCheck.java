package org.qubership.cloud.dbaas.monitoring.indicators;

import org.qubership.cloud.dbaas.monitoring.indicators.HealthCheckResponse.HealthCheckResponseBuilder;
import org.qubership.cloud.dbaas.repositories.h2.H2DbaasUserRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.PersistenceException;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Singleton
@Slf4j
public class DbaasH2StorageConnectHealthCheck implements ProbeCheck {
    public static final String H2_STORAGE_HEALTH_CHECK_NAME = "dbaasH2StorageConnect";

    @Inject
    H2DbaasUserRepository h2DbaasUserRepository;

    @Override
    public HealthCheckResponse check() {
        HealthCheckResponseBuilder responseBuilder = HealthCheckResponse.builder().name(H2_STORAGE_HEALTH_CHECK_NAME);
        try {
            log.debug("Health check dbaas storage");
            h2DbaasUserRepository.findById(UUID.randomUUID());
            responseBuilder.up();
        } catch (PersistenceException e) {
            log.error("Dbaas storage is not available");
            responseBuilder.down().details("details", e.getMessage());
        }
        return responseBuilder.build();
    }
}
