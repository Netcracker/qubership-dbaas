package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves that {@code DatabaseDeclarativeConfig.physicalDatabaseId} maps to the
 * {@code database_declarative_config.physical_database_id} column and survives a real PostgreSQL write and read.
 * The service-level tests only check that the value reaches the repository through Mockito, so they would still
 * pass if the JPA field-to-column mapping were missing.
 */
@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class DatabaseDeclarativeConfigRepositoryTest {

    private static final String PHYSICAL_DATABASE_ID = "postgresql-prod-a";

    @Inject
    DatabaseDeclarativeConfigRepository repository;

    private final String namespace = "declarative-config-repo-test-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> repository.deleteByNamespace(namespace));
    }

    @Test
    void testPersist_physicalDatabaseIdRoundTripsThroughPostgreSQL() {
        DatabaseDeclarativeConfig config = buildConfig();

        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            repository.persist(config);
            repository.flush();
            return config.getId();
        });
        assertNotNull(id, "persist should assign a generated id");

        // Reload in a separate transaction so the assertion reads from the database, not the managed entity.
        DatabaseDeclarativeConfig reloaded = QuarkusTransaction.requiringNew()
                .call(() -> repository.findByIdOptional(id).orElseThrow());

        assertEquals(PHYSICAL_DATABASE_ID, reloaded.getPhysicalDatabaseId());
    }

    private DatabaseDeclarativeConfig buildConfig() {
        DatabaseDeclarativeConfig config = new DatabaseDeclarativeConfig();
        config.setLazy(false);
        config.setType("postgresql");
        config.setNamespace(namespace);
        config.setPhysicalDatabaseId(PHYSICAL_DATABASE_ID);

        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("scope", "service");
        classifier.put("microserviceName", "declarative-config-repo-test-service");
        classifier.put("namespace", namespace);
        config.setClassifier(classifier);

        return config;
    }
}
