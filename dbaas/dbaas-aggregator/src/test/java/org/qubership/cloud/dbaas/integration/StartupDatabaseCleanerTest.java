package org.qubership.cloud.dbaas.integration;

import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.DbState;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.service.DbaaSHelper;
import org.qubership.cloud.dbaas.service.StartupDatabaseCleaner;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@Slf4j
public class StartupDatabaseCleanerTest {

    private static final String POSTGRESQL = "postgresql";

    @Inject
    DatabaseDbaasRepository databaseDbaasRepository;
    @Inject
    DatabaseRegistryRepository databaseRegistryRepository;
    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    StartupDatabaseCleaner startupDatabaseCleaner;
    @ConfigProperty(name = "dbaas.paas.pod-name")
    String podName;
    @InjectSpy
    DbaaSHelper dbaaSHelper;

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
        databaseDbaasRepository.deleteAll(databaseDbaasRepository.findAnyLogDbTypeByNamespace("startupDatabaseCleanerTest"));
    }

    @Test
    void testDatabasesCleanupProdMode() {
        Mockito.when(dbaaSHelper.isProductionMode()).thenReturn(true);
        DatabaseRegistry currentHostnameProcessingDatabase = QuarkusTransaction.requiringNew().call(() -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(createDatabase(new DbState(DbState.DatabaseStateStatus.PROCESSING, podName))));
        DatabaseRegistry otherHostnameProcessingDatabase = QuarkusTransaction.requiringNew().call(() -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(createDatabase(new DbState(DbState.DatabaseStateStatus.PROCESSING, UUID.randomUUID().toString()))));
        DatabaseRegistry createdDatabase = QuarkusTransaction.requiringNew().call(() -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(createDatabase(new DbState(DbState.DatabaseStateStatus.CREATED, podName))));

        // Validate that databases were saved to repository before testing cleanup
        assertTrue(databaseRegistryDbaasRepository.findDatabaseRegistryById(currentHostnameProcessingDatabase.getId()).isPresent());
        assertTrue(databaseRegistryDbaasRepository.findDatabaseRegistryById(otherHostnameProcessingDatabase.getId()).isPresent());
        assertTrue(databaseRegistryDbaasRepository.findDatabaseRegistryById(createdDatabase.getId()).isPresent());

        databaseRegistryRepository.getEntityManager().clear();
        QuarkusTransaction.requiringNew().run(() -> startupDatabaseCleaner.cleanProcessingDatabases());

        Optional<DatabaseRegistry> currentHostnameProcessingDatabaseAfter = databaseRegistryDbaasRepository.findDatabaseRegistryById(currentHostnameProcessingDatabase.getId());
        Optional<DatabaseRegistry> otherHostnameProcessingDatabaseAfter = databaseRegistryDbaasRepository.findDatabaseRegistryById(otherHostnameProcessingDatabase.getId());
        Optional<DatabaseRegistry> createdDatabaseAfter = databaseRegistryDbaasRepository.findDatabaseRegistryById(createdDatabase.getId());
        assertTrue(currentHostnameProcessingDatabaseAfter.isPresent());
        assertTrue(currentHostnameProcessingDatabaseAfter.get().isMarkedForDrop(), "PROCESSING database with matching pod name should be marked as dropped");

        assertTrue(otherHostnameProcessingDatabaseAfter.isPresent());
        assertFalse(otherHostnameProcessingDatabaseAfter.get().isMarkedForDrop(), "PROCESSING database without matching pod name should not be marked as dropped");

        assertTrue(createdDatabaseAfter.isPresent());
        assertFalse(createdDatabaseAfter.get().isMarkedForDrop(), "CREATED database should not be marked as dropped");
    }

    @Test
    void testDatabasesCleanupDevMode() {
        Mockito.when(dbaaSHelper.isProductionMode()).thenReturn(false);
        DatabaseRegistry currentHostnameProcessingDatabase = QuarkusTransaction.requiringNew().call(() -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(createDatabase(new DbState(DbState.DatabaseStateStatus.PROCESSING, podName))));
        DatabaseRegistry otherHostnameProcessingDatabase = QuarkusTransaction.requiringNew().call(() -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(createDatabase(new DbState(DbState.DatabaseStateStatus.PROCESSING, UUID.randomUUID().toString()))));
        DatabaseRegistry createdDatabase = QuarkusTransaction.requiringNew().call(() -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(createDatabase(new DbState(DbState.DatabaseStateStatus.CREATED, podName))));

        // Validate that databases were saved to repository before testing cleanup
        assertTrue(databaseRegistryDbaasRepository.findDatabaseRegistryById(currentHostnameProcessingDatabase.getId()).isPresent());
        assertTrue(databaseRegistryDbaasRepository.findDatabaseRegistryById(otherHostnameProcessingDatabase.getId()).isPresent());
        assertTrue(databaseRegistryDbaasRepository.findDatabaseRegistryById(createdDatabase.getId()).isPresent());

        databaseRegistryRepository.getEntityManager().clear();
        QuarkusTransaction.requiringNew().run(() -> startupDatabaseCleaner.cleanProcessingDatabases());

        Optional<DatabaseRegistry> currentHostnameProcessingDatabaseAfter = databaseRegistryDbaasRepository.findDatabaseRegistryById(currentHostnameProcessingDatabase.getId());
        Optional<DatabaseRegistry> otherHostnameProcessingDatabaseAfter = databaseRegistryDbaasRepository.findDatabaseRegistryById(otherHostnameProcessingDatabase.getId());
        Optional<DatabaseRegistry> createdDatabaseAfter = databaseRegistryDbaasRepository.findDatabaseRegistryById(createdDatabase.getId());
        assertFalse(currentHostnameProcessingDatabaseAfter.isPresent(), "PROCESSING database with matching pod name should be deleted from repository");

        assertTrue(otherHostnameProcessingDatabaseAfter.isPresent());
        assertFalse(otherHostnameProcessingDatabaseAfter.get().isMarkedForDrop(), "PROCESSING database without matching pod name should not be marked as dropped");

        assertTrue(createdDatabaseAfter.isPresent());
        assertFalse(createdDatabaseAfter.get().isMarkedForDrop(), "CREATED database should not be marked as dropped");
    }

    private DatabaseRegistry createDatabase(DbState dbState) {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("microserviceName", UUID.randomUUID().toString());
        classifier.put("scope", "service");
        classifier.put("namespace", "startupDatabaseCleanerTest");
        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setClassifier(classifier);
        database.setType(POSTGRESQL);
        database.setNamespace("startupDatabaseCleanerTest");
        database.setConnectionProperties(Arrays.asList(new HashMap<>() {{
            put("username", "user");
            put(ROLE, Role.ADMIN.toString());
        }}));
        DbResource resource = new DbResource("someKind", "someName");
        List<DbResource> resources = new ArrayList<>();
        resources.add(resource);
        database.setResources(resources);
        database.setName("test-database-" + UUID.randomUUID());
        database.setAdapterId("pgAdapter");
        database.setDbState(dbState);
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType(POSTGRESQL);
        databaseRegistry.setNamespace("startupDatabaseCleanerTest");
        databaseRegistry.setDatabase(database);
        List<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);
        return databaseRegistry;
    }
}
