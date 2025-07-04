package org.qubership.cloud.dbaas.integration.config;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import org.apache.commons.lang3.ArrayUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.TestcontainersConfiguration;

import java.util.Map;

public class PostgresqlContainerResource implements QuarkusTestResourceLifecycleManager {

    public static final String IMAGE_ENV_KEY = "POSTGRESQL_IMAGE";

    public static final String DBAAS_PASSWORD = "dbaas";

    public static final String DBAAS_USERNAME = "dbaas";

    public static final String DBAAS_DB = "dbaas";

    public static PostgreSQLContainer postgresql;

    @Override
    public Map<String, String> start() {
        postgresql = new PostgreSQLContainer<>(DockerImageName.parse(
                System.getenv().getOrDefault(IMAGE_ENV_KEY, "postgres:14.5")))
                .withUsername(DBAAS_USERNAME)
                .withPassword(DBAAS_PASSWORD)
                .withDatabaseName(DBAAS_DB);
        String[] commandParts = postgresql.getCommandParts();
        commandParts = ArrayUtils.addAll(commandParts, "-c", "max_prepared_transactions=15", "-c", "max_connections=15");
        postgresql.setCommandParts(commandParts);
        postgresql.start();
        return Map.of("postgresql.host", postgresql.getHost(),
                "postgresql.port", postgresql.getFirstMappedPort().toString());
    }

    @Override
    public void stop() {
        postgresql.stop();
    }
}
