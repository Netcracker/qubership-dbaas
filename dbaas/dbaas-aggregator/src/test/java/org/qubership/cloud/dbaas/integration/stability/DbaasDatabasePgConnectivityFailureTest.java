package org.qubership.cloud.dbaas.integration.stability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.DbState;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.h2.H2DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@Slf4j
@TestProfile(DbaasDatabasePgConnectivityFailureTest.DirtiesContextProfile.class)
class DbaasDatabasePgConnectivityFailureTest {

    private static final String POSTGRESQL = "postgresql";

    @Inject
    DatabaseRegistryRepository databaseRegistryRepository;
    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    H2DatabaseRegistryRepository h2DatabaseRegistryRepository;
    @Inject
    DatabasesRepository databasesRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setUp() {
        clean();
    }

    @Transactional
    public void clean() {
        databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType().forEach(dbr -> databaseRegistryDbaasRepository.delete(dbr));
    }

    @Test
    void testExactClassifierMatch() throws JsonProcessingException {
        DatabaseRegistry database = createDatabase();
        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(database));
        DatabaseRegistry pgDatabase = databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType()).get();
        await().atMost(1, TimeUnit.MINUTES).pollInterval(5, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2DatabaseRegistryRepository.findByIdOptional(database.getId()).isPresent());
        PostgresqlContainerResource.postgresql.stop();
        boolean exceptionHappened = false;
        try {
            QuarkusTransaction.requiringNew().run(() -> databasesRepository.findByClassifierAndType(database.getClassifier(), database.getType()));
        } catch (Exception exception) {
            exceptionHappened = true;
        }
        assertTrue(exceptionHappened);

        Optional<DatabaseRegistry> h2Database = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(database.getClassifier(), POSTGRESQL);
        assertTrue(h2Database.isPresent());
        Assertions.assertEquals(objectMapper.writeValueAsString(pgDatabase), objectMapper.writeValueAsString(h2Database.get()));
    }

    private DatabaseRegistry createDatabase() {
        return createDatabase("test-namespace");
    }

    private DatabaseRegistry createDatabase(String namespace) {
        SortedMap<String, Object> classifier = getClassifier();
        classifier.put("namespace", namespace);

        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setClassifier(classifier);
        database.setType(POSTGRESQL);
        database.setNamespace(namespace);
        database.setConnectionProperties(Arrays.asList(new HashMap<String, Object>() {{
            put("username", "user");
            put(ROLE, Role.ADMIN.toString());
        }}));


        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        DatabaseRegistry databaseRegistry = createDatabaseRegistry();
        databaseRegistry.setDatabase(database);
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType(POSTGRESQL);
        databaseRegistry.setNamespace(namespace);
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);

        DbResource resource = new DbResource("someKind", "someName");
        resource.setId(UUID.randomUUID());
        List<DbResource> resources = new ArrayList<>();
        resources.add(resource);
        database.setResources(resources);
        database.setName("exact-classifier-match-test-db");
        database.setAdapterId("mongoAdapter");
        database.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        return databaseRegistry;
    }

    @NotNull
    private static SortedMap<String, Object> getClassifier() {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("test-key", "test-val");
        classifier.put("scope", "service");
        return classifier;
    }

    private DatabaseRegistry createDatabaseRegistry() {
        return createDatabaseRegistry("test-namespace");
    }

    private DatabaseRegistry createDatabaseRegistry(String namespace) {
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setClassifier(getClassifier());
        databaseRegistry.setType(POSTGRESQL);
        databaseRegistry.setNamespace(namespace);
        databaseRegistry.getClassifier().put("namespace", namespace);
        return databaseRegistry;
    }

    @NoArgsConstructor
    protected static final class DirtiesContextProfile implements QuarkusTestProfile {
    }
}
