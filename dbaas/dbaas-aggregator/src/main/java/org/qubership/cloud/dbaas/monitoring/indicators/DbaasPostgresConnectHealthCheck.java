package org.qubership.cloud.dbaas.monitoring.indicators;

import org.qubership.cloud.dbaas.monitoring.indicators.HealthCheckResponse.HealthCheckResponseBuilder;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@ApplicationScoped
@Slf4j
public class DbaasPostgresConnectHealthCheck implements HealthCheck {
    public static final String POSTGRES_HEALTH_CHECK_NAME = "dbaasPostgresConnect";

    @Inject
    DatabasesRepository databasesRepository;

    @Override
    public HealthCheckResponse check() {
        HealthCheckResponseBuilder responseBuilder = HealthCheckResponse.builder().name(POSTGRES_HEALTH_CHECK_NAME);
        try {
            log.debug("Health check dbaas postgres connection");
            databasesRepository.findById(UUID.randomUUID());
            responseBuilder.up();
        } catch (PersistenceException e) {
            log.error("Postgres connection is lost");
            responseBuilder.down().details("details", e.getMessage());
        }
        return responseBuilder.build();
    }
}
