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
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@Slf4j
class DbaasDatabaseRegistryStabilityTest {

    private static final String POSTGRESQL = "postgresql";

    @Inject
    DatabaseRegistryDbaasRepository databaseDbaasRepository;
    @Inject
    H2DatabaseRegistryRepository h2DatabaseRegistryRepository;
    @Inject
    DatabasesRepository databasesRepository;
    @Inject
    DatabaseRegistryRepository databaseRegistryRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setUp() {
        clean();
    }

    @AfterEach
    public void tearDown() {
        clean();
    }

    public void clean() {
        databaseDbaasRepository.findAnyLogDbRegistryTypeByNamespace("test-namespace").forEach(databaseRegistry -> databaseRegistryRepository.deleteById(databaseRegistry.getId()));
    }

    @Test
    void testDatabaseEventH2() throws JsonProcessingException {
        DatabaseRegistry databaseRegistry = createDatabase();
        Database database = databaseRegistry.getDatabase();
        databasesRepository.persist(database);

        DatabaseRegistry databaseRegistryToSave = createDatabaseRegistry();
        databaseRegistryToSave.getClassifier().put("test", "val");
        databaseRegistryToSave.setDatabase(database);

        await().atMost(1, TimeUnit.MINUTES).pollInterval(5, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2DatabaseRegistryRepository.findByIdOptional(databaseRegistry.getId()).isPresent());
        Optional<DatabaseRegistry> pgDatabase = databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(databaseRegistry.getClassifier(), databaseRegistry.getType());
        Optional<org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry> h2Database = h2DatabaseRegistryRepository.findDatabaseRegistryByClassifierAndType(databaseRegistry.getClassifier(), databaseRegistry.getType());
        Assertions.assertEquals(objectMapper.writeValueAsString(pgDatabase.get()), objectMapper.writeValueAsString(h2Database.get()));
        Optional<org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry> h2UnsavedDatabase = h2DatabaseRegistryRepository.findDatabaseRegistryByClassifierAndType(databaseRegistryToSave.getClassifier(), databaseRegistryToSave.getType());
        Assertions.assertTrue(h2UnsavedDatabase.isEmpty());

        databaseRegistryRepository.persist(databaseRegistryToSave);
        await().atMost(1, TimeUnit.MINUTES).pollInterval(5, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2DatabaseRegistryRepository.findByIdOptional(databaseRegistryToSave.getId()).isPresent());

        Optional<DatabaseRegistry> pgSavedDatabase = databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(databaseRegistryToSave.getClassifier(), databaseRegistryToSave.getType());
        Optional<org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry> h2SavedDatabase = h2DatabaseRegistryRepository.findDatabaseRegistryByClassifierAndType(databaseRegistryToSave.getClassifier(), databaseRegistryToSave.getType());
        Assertions.assertTrue(h2SavedDatabase.isPresent());
        Assertions.assertEquals(objectMapper.writeValueAsString(pgSavedDatabase.get()), objectMapper.writeValueAsString(h2SavedDatabase.get()));
    }

    private DatabaseRegistry createDatabase() {
        SortedMap<String, Object> classifier = getClassifier();
        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setClassifier(classifier);
        database.setType(POSTGRESQL);
        database.setNamespace("test-namespace");
        database.setConnectionProperties(Arrays.asList(new HashMap<String, Object>() {{
            put("username", "user");
            put(ROLE, Role.ADMIN.toString());
        }}));


        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        DatabaseRegistry databaseRegistry = createDatabaseRegistry();
        databaseRegistry.setDatabase(database);
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType(POSTGRESQL);
        databaseRegistry.setNamespace("test-namespace");
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);

        DbResource resource = new DbResource("someKind", "someName");
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
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setClassifier(getClassifier());
        databaseRegistry.setType(POSTGRESQL);
        databaseRegistry.setNamespace("test-namespace");
        return databaseRegistry;
    }
}
