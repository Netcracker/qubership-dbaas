package org.qubership.cloud.dbaas.dao;

import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.DbState;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
public class DatabaseDbaasRepositoryImplEmbeddedTest {

    private static final String POSTGRESQL = "postgresql";

    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @Test
    void testExactClassifierMatch() {
        SortedMap<String, Object> dbClassifier = new TreeMap<>();
        dbClassifier.put("scope", "service");
        dbClassifier.put("namespace", "test-namespace");
        dbClassifier.put("microserviceName", "test-microservice-name-zero");
        dbClassifier.put("key1", "val1");
        dbClassifier.put("key2", "val2");

        DatabaseRegistry database = createDatabase(dbClassifier);
        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(database));

        Optional<DatabaseRegistry> foundDb = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(dbClassifier, POSTGRESQL);
        assertTrue(foundDb.isPresent());
        assertEquals(foundDb.get().getClassifier(), database.getClassifier());
        assertEquals(POSTGRESQL, foundDb.get().getType());

        dbClassifier.put("key3", "val3");
        foundDb = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(dbClassifier, POSTGRESQL);
        assertTrue(foundDb.isEmpty());

        dbClassifier.remove("key2");
        dbClassifier.remove("key3");
        foundDb = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(dbClassifier, POSTGRESQL);
        assertTrue(foundDb.isEmpty());
    }

    @Test
    void findDatabasesByMicroserviceNameAndNamespace() {
        List<DatabaseRegistry> databases = new ArrayList<>();

        SortedMap<String, Object> dbClassifier = new TreeMap<>();
        String microserviceName = "serviceName-test-one";
        String namespace = "test-namespace";
        dbClassifier.put("microserviceName", microserviceName);
        dbClassifier.put("namespace", namespace);
        dbClassifier.put("scope", "service");
        DatabaseRegistry database = createDatabase(dbClassifier);
        databases.add(database);

        dbClassifier = new TreeMap<>(dbClassifier);
        dbClassifier.put("tenantId", 123);
        dbClassifier.put("scope", "tenant");
        database = createDatabase(dbClassifier);
        database.setMarkedForDrop(false);
        databases.add(database);

        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.saveAll(databases));

        List<DatabaseRegistry> actualDatabases = databaseRegistryDbaasRepository.findDatabasesByMicroserviceNameAndNamespace(microserviceName, namespace);
        assertEquals(2, actualDatabases.size());
    }

    @Test
    void findDatabasesByMicroserviceNameAndNamespaceOnlyOne() {
        List<DatabaseRegistry> databases = new ArrayList<>();

        SortedMap<String, Object> dbClassifier = new TreeMap<>();
        String microserviceName = "serviceName-test-two";
        String namespace = "test-namespace";
        dbClassifier.put("microserviceName", microserviceName);
        dbClassifier.put("namespace", namespace);
        dbClassifier.put("scope", "service");
        DatabaseRegistry database = createDatabase(dbClassifier);
        databases.add(database);

        dbClassifier = new TreeMap<>(dbClassifier);
        dbClassifier.put("tenantId", 123);
        dbClassifier.put("scope", "tenant");
        database = createDatabase(dbClassifier);
        database.setMarkedForDrop(true);
        databases.add(database);

        dbClassifier = new TreeMap<>(dbClassifier);
        dbClassifier.put("microserviceName", "serviceName-test-three");
        database = createDatabase(dbClassifier);
        database.setMarkedForDrop(true);
        databases.add(database);

        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.saveAll(databases));

        List<DatabaseRegistry> actualDatabases = databaseRegistryDbaasRepository.findDatabasesByMicroserviceNameAndNamespace(microserviceName, namespace);

        assertEquals(1, actualDatabases.size());
    }

    @Test
    void getDatabaseByClassifierAndType() {
        List<DatabaseRegistry> databases = new ArrayList<>();

        SortedMap<String, Object> dbClassifier = new TreeMap<>();
        String microserviceName = "serviceName-test-four";
        String namespace = "test-namespace";
        dbClassifier.put("microserviceName", microserviceName);
        dbClassifier.put("namespace", namespace);
        dbClassifier.put("scope", "service");
        DatabaseRegistry database = createDatabase(dbClassifier);
        databases.add(database);
        QuarkusTransaction.requiringNew().run(() -> databaseRegistryDbaasRepository.saveAll(databases));

        Optional<DatabaseRegistry> actualDatabase = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(dbClassifier, POSTGRESQL);
        assertEquals(DbState.DatabaseStateStatus.CREATED, actualDatabase.get().getDatabase().getDbState().getDatabaseState());
    }

    private DatabaseRegistry createDatabase(SortedMap<String, Object> classifier) {
        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setClassifier(classifier);
        database.setType(POSTGRESQL);
        database.setNamespace("test-namespace");
        List<Map<String, Object>> connectionProperties = Collections.singletonList(new HashMap<String, Object>() {{
            put("username", "user");
            put("role", Role.ADMIN.toString());
        }});
        database.setConnectionProperties(connectionProperties);
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType(POSTGRESQL);
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setDatabase(database);
        databaseRegistry.setNamespace("test-namespace");
        database.setDatabaseRegistry(Arrays.asList(databaseRegistry));
        database.setName("exact-classifier-match-test-db");
        database.setAdapterId("mongoAdapter");
        database.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        DbResource resource = new DbResource("someKind", "someName");
        database.setResources(Arrays.asList(resource));
        return databaseRegistry;
    }

    @Test
    void findDatabasesByNamespaceAndName() {
        List<DatabaseRegistry> databases = new ArrayList<>();

        SortedMap<String, Object> dbClassifier = new TreeMap<>();
        String microserviceName = "serviceName-test-five";
        String namespace = "test-namespace";
        dbClassifier.put("microserviceName", microserviceName);
        dbClassifier.put("namespace", namespace);
        dbClassifier.put("scope", "service");
        DatabaseRegistry database = createDatabase(dbClassifier, "findDatabasesByNamespaceAndName-first-database");
        databases.add(database);

        dbClassifier = new TreeMap<>(dbClassifier);
        dbClassifier.put("tenantId", 123);
        dbClassifier.put("scope", "tenant");
        database = createDatabase(dbClassifier, "findDatabasesByNamespaceAndName-second-database");
        database.setMarkedForDrop(false);
        databases.add(database);

        List<DatabaseRegistry> savedDatabases = QuarkusTransaction.requiringNew().call(() -> databaseRegistryDbaasRepository.saveAll(databases));
        for (DatabaseRegistry db : savedDatabases) {
            List<DatabaseRegistry> foundDatabases = databaseRegistryDbaasRepository.findAnyLogDbTypeByNameAndOptionalParams(db.getName(), namespace);
            assertEquals(1, foundDatabases.size());
            assertEquals(foundDatabases.get(0).getClassifier(), db.getClassifier());
            assertEquals(foundDatabases.get(0).getNamespace(), db.getNamespace());
            assertEquals(foundDatabases.get(0).getName(), db.getName());
        }
    }

    @Test
    void findDatabasesByName() {
        List<DatabaseRegistry> databases = new ArrayList<>();

        SortedMap<String, Object> dbClassifier = new TreeMap<>();
        String microserviceName = "serviceName-test-six";
        String namespace = "test-namespace";
        dbClassifier.put("microserviceName", microserviceName);
        dbClassifier.put("namespace", namespace);
        dbClassifier.put("scope", "service");
        DatabaseRegistry database = createDatabase(dbClassifier, "findDatabasesByName-first-database");
        databases.add(database);

        dbClassifier = new TreeMap<>(dbClassifier);
        dbClassifier.put("tenantId", 123);
        dbClassifier.put("scope", "tenant");
        database = createDatabase(dbClassifier, "findDatabasesByName-second-database");
        database.setMarkedForDrop(false);
        databases.add(database);

        List<DatabaseRegistry> savedDatabases = QuarkusTransaction.requiringNew().call(() -> databaseRegistryDbaasRepository.saveAll(databases));
        for (DatabaseRegistry db : savedDatabases) {
            List<DatabaseRegistry> foundDatabases = databaseRegistryDbaasRepository.findAnyLogDbTypeByNameAndOptionalParams(db.getName(), null);
            assertEquals(1, foundDatabases.size());
            assertEquals(foundDatabases.get(0).getClassifier(), db.getClassifier());
            assertEquals(foundDatabases.get(0).getNamespace(), db.getNamespace());
            assertEquals(foundDatabases.get(0).getName(), db.getName());
        }
    }

    private DatabaseRegistry createDatabase(SortedMap<String, Object> classifier, String dbName) {
        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setClassifier(classifier);
        database.setType(POSTGRESQL);
        database.setNamespace("test-namespace");
        List<Map<String, Object>> connectionProperties = Collections.singletonList(new HashMap<String, Object>() {{
            put("username", "user");
            put("role", Role.ADMIN.toString());
        }});
        database.setConnectionProperties(connectionProperties);
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType(POSTGRESQL);
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setDatabase(database);
        databaseRegistry.setNamespace("test-namespace");
        database.setDatabaseRegistry(Arrays.asList(databaseRegistry));
        database.setName("exact-classifier-match-test-db");
        database.setName(dbName);
        database.setAdapterId("mongoAdapter");
        database.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        DbResource resource = new DbResource("someKind", "someName");
        database.setResources(Arrays.asList(resource));
        return databaseRegistry;
    }
}
