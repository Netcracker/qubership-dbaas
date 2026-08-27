package com.netcracker.cloud.dbaas.integration.stability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.h2.H2DatabaseRegistryRepository;
import com.netcracker.cloud.dbaas.repositories.h2.H2DatabaseRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import com.netcracker.cloud.dbaas.utils.DatabaseBuilder;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

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
    void setUp() {
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Transactional
    public void clean() {
        databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType().forEach(dbr -> databaseRegistryDbaasRepository.delete(dbr));
    }

    @Test
    void testSaveAndDeleteDatabase() {
        Database database = new DatabaseBuilder().registry().registry().build();
        DatabaseRegistry databaseRegistry = database.getDatabaseRegistry().get(0);
        DatabaseRegistry databaseRegistry2 = database.getDatabaseRegistry().get(1);

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
        Database database = new DatabaseBuilder().registry().registry().build();
        DatabaseRegistry databaseRegistry = database.getDatabaseRegistry().get(0);
        DatabaseRegistry databaseRegistry2 = database.getDatabaseRegistry().get(1);

        QuarkusTransaction.requiringNew().run(() -> {
            databaseRegistryDbaasRepository.saveAnyTypeLogDb(databaseRegistry2);
            databaseRegistryDbaasRepository.delete(databaseRegistry);
        });
        Optional<DatabaseRegistry> founded_dbr1 = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(databaseRegistry.getClassifier(), POSTGRESQL);
        Assertions.assertTrue(founded_dbr1.isEmpty());
        Optional<DatabaseRegistry> founded_dbr2 = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(databaseRegistry2.getClassifier(), POSTGRESQL);
        Assertions.assertTrue(founded_dbr2.isPresent());
        assertEquals(database.getId(), founded_dbr2.get().getDatabase().getId());

        databasesRepository.getEntityManager().clear();
        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.delete(databaseRegistry2));
        founded_dbr2 = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(databaseRegistry2.getClassifier(), POSTGRESQL);
        Assertions.assertTrue(founded_dbr2.isEmpty());
        Optional<Database> db = databasesRepository.findByIdOptional(database.getId());
        Assertions.assertTrue(db.isEmpty());
    }

    @Test
    void testSameDatabaseInH2() {
        DatabaseRegistry database = new DatabaseBuilder().registry().build().getDatabaseRegistry().getFirst();
        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(database));
        await().atMost(1, TimeUnit.MINUTES).pollInterval(1, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2DatabaseRegistryRepository.findByIdOptional(database.getId()).isPresent());

        Optional<DatabaseRegistry> pgDatabase = databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType());
        Optional<com.netcracker.cloud.dbaas.entity.h2.DatabaseRegistry> h2Database = h2DatabaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType());

        log.info("pgdb = {}", pgDatabase);
        log.info("h2db = {}", h2Database);
        DatabaseRegistry databaseRegistry = pgDatabase.get();
        assertEquals(databaseRegistry.getId(), h2Database.get().getId());
        assertEquals(databaseRegistry.getClassifier(), h2Database.get().getClassifier());
        assertEquals(databaseRegistry.getId(), h2Database.get().getId());
        h2DatabaseRegistryRepository.getEntityManager().clear();
        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.delete(databaseRegistry));
        await().atMost(1, TimeUnit.MINUTES).pollInterval(1, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2DatabaseRegistryRepository.findByIdOptional(databaseRegistry.getId()).isEmpty());
        pgDatabase = databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType());
        h2Database = h2DatabaseRegistryRepository.findDatabaseRegistryByClassifierAndType(database.getClassifier(), database.getType());
        log.info("pgdb = {}", pgDatabase);
        log.info("h2db = {}", h2Database);

        assertTrue(pgDatabase.isEmpty());
        assertTrue(h2Database.isEmpty());
    }

    @Test
    void testDatabaseEventH2() throws JsonProcessingException {
        Database database = new DatabaseBuilder().registry().build();
        QuarkusTransaction.requiringNew().run(() -> databasesRepository.persist(database));
        log.debug("database id = {}", database.getId());

        await().atMost(1, TimeUnit.MINUTES).pollInterval(1, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2DatabaseRepository.findByIdOptional(database.getId()).isPresent());
        Database pgDatabase = databasesRepository.findById(database.getId());
        com.netcracker.cloud.dbaas.entity.h2.Database h2Database = h2DatabaseRepository.findById(database.getId());
        assertNotNull(pgDatabase);
        assertEquals(objectMapper.writeValueAsString(pgDatabase), objectMapper.writeValueAsString(h2Database));
    }

    @Test
    void testSavingExternalDatabase() {
        DatabaseRegistry databaseRegistry = new DatabaseBuilder().external(true).registry().build().getDatabaseRegistry().getFirst();

        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(databaseRegistry));
        await().atMost(1, TimeUnit.MINUTES).pollInterval(1, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2DatabaseRegistryRepository.findByIdOptional(databaseRegistry.getId()).isPresent());

        Optional<DatabaseRegistry> pgDatabase = databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(databaseRegistry.getClassifier(), databaseRegistry.getType());
        Optional<com.netcracker.cloud.dbaas.entity.h2.DatabaseRegistry> h2Database = h2DatabaseRegistryRepository.findDatabaseRegistryByClassifierAndType(databaseRegistry.getClassifier(), databaseRegistry.getType());
        log.info("pgdb = {}", pgDatabase);
        log.info("h2db = {}", h2Database);
        assertEquals(pgDatabase.get().getDatabase().getId(), h2Database.get().getDatabase().getId());
        assertEquals(pgDatabase.get().getClassifier(), h2Database.get().getClassifier());
        h2DatabaseRegistryRepository.getEntityManager().clear();
        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.delete(databaseRegistry));
        await().atMost(1, TimeUnit.MINUTES).pollInterval(1, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2DatabaseRegistryRepository.findByIdOptional(databaseRegistry.getId()).isEmpty());

        pgDatabase = databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(databaseRegistry.getClassifier(), databaseRegistry.getType());
        h2Database = h2DatabaseRegistryRepository.findDatabaseRegistryByClassifierAndType(databaseRegistry.getClassifier(), databaseRegistry.getType());
        log.info("pgdb = {}", pgDatabase);
        log.info("h2db = {}", h2Database);
        assertTrue(pgDatabase.isEmpty());
        assertTrue(h2Database.isEmpty());
    }
}
