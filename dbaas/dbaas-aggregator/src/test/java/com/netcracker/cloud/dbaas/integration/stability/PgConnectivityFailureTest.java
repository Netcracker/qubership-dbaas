package com.netcracker.cloud.dbaas.integration.stability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.dto.role.Role;
import com.netcracker.cloud.dbaas.entity.pg.*;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.h2.H2DatabaseRegistryRepository;
import com.netcracker.cloud.dbaas.repositories.h2.H2PhysicalDatabaseRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.PhysicalDatabasesRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.netcracker.cloud.dbaas.Constants.ROLE;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestProfile(PgConnectivityFailureTest.DirtiesContextProfile.class)
public class PgConnectivityFailureTest {

    private static final String TEST_TYPE = "postgresql";
    private static final String TEST_NAMESPACE = "test-namespace";

    @Inject
    DatabaseRegistryRepository databaseRegistryRepository;
    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    H2DatabaseRegistryRepository h2DatabaseRegistryRepository;
    @Inject
    DatabasesRepository databasesRepository;
    @Inject
    PhysicalDatabaseDbaasRepository physicalDatabaseDbaasRepository;
    @Inject
    H2PhysicalDatabaseRepository h2PhysicalDatabaseRepository;
    @Inject
    PhysicalDatabasesRepository physicalDatabasesRepository;
    @Inject
    DatabaseRegistryDbaasRepository databaseDbaasRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        clean();
    }

    @Transactional
    public void clean() {
        databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType().forEach(dbr -> databaseRegistryDbaasRepository.delete(dbr));
        physicalDatabaseDbaasRepository.findByType(TEST_TYPE).filter(Objects::nonNull).forEach(pd -> physicalDatabaseDbaasRepository.delete(pd));
        databaseDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE).forEach(databaseRegistry -> databaseRegistryRepository.deleteById(databaseRegistry.getId()));
    }

    @Test
    void testExactClassifierMatch() throws JsonProcessingException {
        DatabaseRegistry database = createDatabase();
        PhysicalDatabase physicalDatabase = createPhysicalDatabase();

        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(database));
        QuarkusTransaction.requiringNew().run(() -> physicalDatabaseDbaasRepository.save(physicalDatabase));

        DatabaseRegistry pgDatabase = databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType()).get();
        await().atMost(1, TimeUnit.MINUTES).pollInterval(1, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2DatabaseRegistryRepository.findByIdOptional(database.getId()).isPresent());
        await().atMost(1, TimeUnit.MINUTES).pollInterval(1, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2PhysicalDatabaseRepository.findByIdOptional(physicalDatabase.getId()).isPresent());

        PostgresqlContainerResource.postgresql.stop();

        boolean exceptionHappened = false;
        try {
            QuarkusTransaction.requiringNew().run(() -> databasesRepository.findByClassifierAndType(database.getClassifier(), database.getType()));
        } catch (Exception exception) {
            exceptionHappened = true;
        }
        assertTrue(exceptionHappened);

        exceptionHappened = false;
        try {
            QuarkusTransaction.requiringNew().run(() -> physicalDatabasesRepository.findByPhysicalDatabaseIdentifier(physicalDatabase.getPhysicalDatabaseIdentifier()));
        } catch (Exception exception) {
            exceptionHappened = true;
        }
        assertTrue(exceptionHappened);

        exceptionHappened = false;
        try {
            QuarkusTransaction.requiringNew().run(() -> databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType()));
        } catch (Exception exception) {
            exceptionHappened = true;
        }
        assertTrue(exceptionHappened);

        Optional<DatabaseRegistry> h2Database = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(database.getClassifier(), TEST_TYPE);
        assertTrue(h2Database.isPresent());
        Assertions.assertEquals(objectMapper.writeValueAsString(pgDatabase), objectMapper.writeValueAsString(h2Database.get()));

        Optional<DatabaseRegistry> h2DatabaseRegistry = databaseDbaasRepository.getDatabaseByClassifierAndType(database.getClassifier(), TEST_TYPE);
        assertTrue(h2DatabaseRegistry.isPresent());
        Assertions.assertEquals(objectMapper.writeValueAsString(pgDatabase), objectMapper.writeValueAsString(h2DatabaseRegistry.get()));

        PhysicalDatabase foundDb = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabase.getPhysicalDatabaseIdentifier());
        assertNotNull(foundDb);
        assertEquals(foundDb, physicalDatabase);
    }

    private DatabaseRegistry createDatabase() {
        SortedMap<String, Object> classifier = getClassifier();
        classifier.put("namespace", TEST_NAMESPACE);

        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setClassifier(classifier);
        database.setType(TEST_TYPE);
        database.setNamespace(TEST_NAMESPACE);
        database.setConnectionProperties(List.of(new HashMap<>() {{
            put("username", "user");
            put(ROLE, Role.ADMIN.toString());
        }}));

        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        DatabaseRegistry databaseRegistry = createDatabaseRegistry();
        databaseRegistry.setDatabase(database);
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType(TEST_TYPE);
        databaseRegistry.setNamespace(TEST_NAMESPACE);
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

    private static SortedMap<String, Object> getClassifier() {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("test-key", "test-val");
        classifier.put("scope", "service");
        return classifier;
    }

    private DatabaseRegistry createDatabaseRegistry() {
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setClassifier(getClassifier());
        databaseRegistry.setType(TEST_TYPE);
        databaseRegistry.setNamespace(TEST_NAMESPACE);
        databaseRegistry.getClassifier().put("namespace", TEST_NAMESPACE);
        return databaseRegistry;
    }

    private PhysicalDatabase createPhysicalDatabase() {
        PhysicalDatabase db = new PhysicalDatabase();
        db.setId(UUID.randomUUID().toString());
        db.setPhysicalDatabaseIdentifier(UUID.randomUUID().toString());
        db.setType(TEST_TYPE);
        return db;
    }

    @NoArgsConstructor
    protected static final class DirtiesContextProfile implements QuarkusTestProfile {
    }
}
