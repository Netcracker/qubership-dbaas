package com.netcracker.cloud.dbaas.integration.config;

import com.netcracker.cloud.dbaas.JdbcUtils;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

public class PostgresqlContainerResource implements QuarkusTestResourceLifecycleManager {
    private static final String IMAGE_ENV_KEY = "POSTGRESQL_IMAGE";
    private static final String POSTGRES_IMAGE = "postgres:18";
    private static PostgreSQLContainer<?> postgresql;

    @Override
    public Map<String, String> start() {
        if (postgresql == null || !postgresql.isRunning()) {
            postgresql = createContainer();
            postgresql.start();
        }
        String jdbcUrl = JdbcUtils.buildConnectionURL(postgresql.getHost(),
                postgresql.getFirstMappedPort().toString(),
                JdbcUtils.DEFAULT_DATABASE_NAME,
                false);
        cleanPostgresql(jdbcUrl);
        return Map.of("quarkus.datasource.jdbc.url", jdbcUrl);
    }

    @Override
    public void stop() {
        // Intentionally left empty: the container outlives every Quarkus instance of the run. Because reuse
        // is enabled, Testcontainers leaves the container running after the build so the next run reuses it.
    }

    public static void forceStop() {
        postgresql.stop();
    }

    private static PostgreSQLContainer<?> createContainer() {
        System.setProperty("testcontainers.reuse.enable", "true");
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(DockerImageName.parse(
                System.getenv().getOrDefault(IMAGE_ENV_KEY, POSTGRES_IMAGE)))
                .withReuse(true)
                .withUsername(JdbcUtils.DEFAULT_USERNAME)
                .withPassword(JdbcUtils.DEFAULT_PASSWORD)
                .withDatabaseName(JdbcUtils.DEFAULT_DATABASE_NAME);
        String[] commandParts = container.getCommandParts();
        commandParts = ArrayUtils.addAll(commandParts, "-c", "max_prepared_transactions=15", "-c", "max_connections=15");
        container.setCommandParts(commandParts);
        return container;
    }

    private static void cleanPostgresql(String jdbcUrl) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, JdbcUtils.DEFAULT_USERNAME, JdbcUtils.DEFAULT_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
            statement.execute("GRANT ALL ON SCHEMA public TO " + JdbcUtils.DEFAULT_USERNAME);
            statement.execute("GRANT ALL ON SCHEMA public TO public");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clean shared PostgreSQL database", e);
        }
    }
}
