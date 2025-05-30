package org.qubership.cloud.dbaas.integration.stability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.ExternalDatabaseRequestV3;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.DbState;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.h2.H2DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.repositories.h2.H2DatabaseRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static java.util.Collections.singletonList;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@Slf4j
class DbaasDatabaseStabilityTest {

    private static final String POSTGRESQL = "postgresql";

    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    DatabaseRegistryRepository databaseRegistryRepository;
    @Inject
    H2DatabaseRegistryRepository h2DatabaseRegistryRepository;
    @Inject
    H2DatabaseRepository h2DatabaseRepository;
    @Inject
    DatabasesRepository databasesRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setUp() {
        clean();
    }

    @AfterEach
    public void tearDown() {
        clean();
    }

    @Transactional
    public void clean() {
        databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType().forEach(dbr -> databaseRegistryDbaasRepository.delete(dbr));
    }

    @Test
    void testSaveAndDeleteDatabase() {
        Database database = createDatabase().getDatabase();
        DatabaseRegistry databaseRegistry = database.getDatabaseRegistry().get(0);
        DatabaseRegistry databaseRegistry2 = createDatabaseRegistry();
        databaseRegistry2.getClassifier().put("extra", "field");
        databaseRegistry2.setDatabase(database);
        database.getDatabaseRegistry().add(databaseRegistry2);

        QuarkusTransaction.requiringNew().run(() -> {
            databaseRegistryDbaasRepository.saveAnyTypeLogDb(databaseRegistry2);
            databaseRegistryDbaasRepository.delete(databaseRegistry);
        });
        Optional<DatabaseRegistry> founded_dbr1 = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(databaseRegistry.getClassifier(), POSTGRESQL);
        Assertions.assertTrue(founded_dbr1.isEmpty());
        Optional<DatabaseRegistry> founded_dbr2 = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(databaseRegistry2.getClassifier(), POSTGRESQL);
        Assertions.assertTrue(founded_dbr2.isPresent());
        Assertions.assertEquals(database.getId(), founded_dbr2.get().getDatabase().getId());

        databasesRepository.getEntityManager().clear();
        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.delete(databaseRegistry2));
        founded_dbr2 = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(databaseRegistry2.getClassifier(), POSTGRESQL);
        Assertions.assertTrue(founded_dbr2.isEmpty());
        Optional<Database> db = databasesRepository.findByIdOptional(database.getId());
        Assertions.assertTrue(db.isEmpty());
    }

    @Test
    void testSaveAndDeleteDatabaseById() {
        Database database = createDatabase().getDatabase();
        DatabaseRegistry databaseRegistry = database.getDatabaseRegistry().get(0);
        DatabaseRegistry databaseRegistry2 = createDatabaseRegistry();
        databaseRegistry2.getClassifier().put("extra", "field");
        databaseRegistry2.setDatabase(database);
        database.getDatabaseRegistry().add(databaseRegistry2);

        QuarkusTransaction.requiringNew().run(() -> {
            databaseRegistryDbaasRepository.saveAnyTypeLogDb(databaseRegistry2);
            databaseRegistryDbaasRepository.deleteById(databaseRegistry.getId());
        });
        Optional<DatabaseRegistry> founded_dbr1 = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(databaseRegistry.getClassifier(), POSTGRESQL);
        Assertions.assertTrue(founded_dbr1.isEmpty());
        Optional<DatabaseRegistry> founded_dbr2 = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(databaseRegistry2.getClassifier(), POSTGRESQL);
        Assertions.assertTrue(founded_dbr2.isPresent());
        assertEquals(database.getId(), founded_dbr2.get().getDatabase().getId());

        databasesRepository.getEntityManager().clear();
        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.deleteById(databaseRegistry2.getId()));
        founded_dbr2 = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(databaseRegistry2.getClassifier(), POSTGRESQL);
        Assertions.assertTrue(founded_dbr2.isEmpty());
        Optional<Database> db = databasesRepository.findByIdOptional(database.getId());
        Assertions.assertTrue(db.isEmpty());
    }

    @Test
    void testSameDatabaseInH2() {
        DatabaseRegistry database = createDatabase();
        database.getClassifier().put("sameDatabase", "sameDatabase");
        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(database));
        await().atMost(1, TimeUnit.MINUTES).pollInterval(5, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2DatabaseRegistryRepository.findByIdOptional(database.getId()).isPresent());

        Optional<DatabaseRegistry> pgDatabase = databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType());
        Optional<org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry> h2Database = h2DatabaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType());

        log.info("pgdb = {}", pgDatabase);
        log.info("h2db = {}", h2Database);
        assertEquals(pgDatabase.get().getId(), h2Database.get().getId());
        assertEquals(pgDatabase.get().getClassifier(), h2Database.get().getClassifier());
        assertEquals(pgDatabase.get().getId(), h2Database.get().getId());
        final UUID pgDatabaseId = pgDatabase.get().getId();
        h2DatabaseRegistryRepository.getEntityManager().clear();
        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.deleteById(pgDatabaseId));
        await().atMost(1, TimeUnit.MINUTES).pollInterval(5, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2DatabaseRegistryRepository.findByIdOptional(pgDatabaseId).isEmpty());
        pgDatabase = databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType());
        h2Database = h2DatabaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType());
        log.info("pgdb = {}", pgDatabase);
        log.info("h2db = {}", h2Database);

        assertTrue(pgDatabase.isEmpty());
        assertTrue(h2Database.isEmpty());
    }

    @Test
    void testDatabaseEventH2() throws JsonProcessingException {
        Database database = createDatabase().getDatabase();
        QuarkusTransaction.requiringNew().run(() -> databasesRepository.persist(database));
        log.debug("database id = {}", database.getId());

        await().atMost(1, TimeUnit.MINUTES).pollInterval(5, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2DatabaseRepository.findByIdOptional(database.getId()).isPresent());
        Database pgDatabase = databasesRepository.findByClassifierAndType(database.getDatabaseRegistry().get(0).getClassifier(), database.getDatabaseRegistry().get(0).getType());
        org.qubership.cloud.dbaas.entity.h2.Database h2Database = h2DatabaseRepository.findByClassifierAndType(database.getDatabaseRegistry().get(0).getClassifier(), database.getDatabaseRegistry().get(0).getType());
        assertEquals(objectMapper.writeValueAsString(pgDatabase), objectMapper.writeValueAsString(h2Database));
    }

    @Test
    void testSavingExternalDatabase() {
        DatabaseRegistry database = getExternalDatabaseRequestObject().toDatabaseRegistry();
        database.getClassifier().put("externalDatabase", "external");

        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(database));
        await().atMost(1, TimeUnit.MINUTES).pollInterval(5, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2DatabaseRegistryRepository.findByIdOptional(database.getId()).isPresent());

        Optional<DatabaseRegistry> pgDatabase = databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType());
        Optional<org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry> h2Database = h2DatabaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType());
        log.info("pgdb = {}", pgDatabase);
        log.info("h2db = {}", h2Database);
        assertEquals(pgDatabase.get().getDatabase().getId(), h2Database.get().getDatabase().getId());
        assertEquals(pgDatabase.get().getClassifier(), h2Database.get().getClassifier());
        h2DatabaseRegistryRepository.getEntityManager().clear();
        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.deleteById(database.getId()));
        await().atMost(1, TimeUnit.MINUTES).pollInterval(5, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2DatabaseRegistryRepository.findByIdOptional(database.getId()).isEmpty());

        pgDatabase = databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType());
        h2Database = h2DatabaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType());
        log.info("pgdb = {}", pgDatabase);
        log.info("h2db = {}", h2Database);
        assertTrue(pgDatabase.isEmpty());
        assertTrue(h2Database.isEmpty());
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
        databaseRegistry.setClassifier(getClassifier());
        databaseRegistry.setType(POSTGRESQL);
        databaseRegistry.setNamespace(namespace);
        databaseRegistry.getClassifier().put("namespace", namespace);
        return databaseRegistry;
    }

    private ExternalDatabaseRequestV3 getExternalDatabaseRequestObject() {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("microserviceName", "test");
        classifier.put("namespace", "test-namespace");
        classifier.put("scope", "service");
        Map<String, Object> cp = new HashMap<>();
        cp.put(ROLE, Role.ADMIN.toString());
        return new ExternalDatabaseRequestV3(classifier, singletonList(cp), "tarantool", "external-db");
    }

}
