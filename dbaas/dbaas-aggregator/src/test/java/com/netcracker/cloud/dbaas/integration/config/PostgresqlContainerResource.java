package com.netcracker.cloud.dbaas.integration.config;

import com.netcracker.cloud.dbaas.JdbcUtils;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.apache.commons.lang3.ArrayUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

public class PostgresqlContainerResource implements QuarkusTestResourceLifecycleManager {

    public static final String IMAGE_ENV_KEY = "POSTGRESQL_IMAGE";
    public static PostgreSQLContainer postgresql;

    @Override
    public Map<String, String> start() {
        postgresql = new PostgreSQLContainer<>(DockerImageName.parse(
                System.getenv().getOrDefault(IMAGE_ENV_KEY, "postgres:14.5")))
                .withUsername(JdbcUtils.DEFAULT_USERNAME)
                .withPassword(JdbcUtils.DEFAULT_PASSWORD)
                .withDatabaseName(JdbcUtils.DEFAULT_DATABASE_NAME);
        String[] commandParts = postgresql.getCommandParts();
        commandParts = ArrayUtils.addAll(commandParts, "-c", "max_prepared_transactions=15", "-c", "max_connections=15");
        postgresql.setCommandParts(commandParts);
        postgresql.start();
        String url = JdbcUtils.buildConnectionURL(postgresql.getHost(),
                postgresql.getFirstMappedPort().toString(),
                JdbcUtils.DEFAULT_DATABASE_NAME,
                false);
        return Map.of("quarkus.datasource.jdbc.url", url);
    }

    @Override
    public void stop() {
        postgresql.stop();
    }
}
