package org.qubership.cloud.dbaas.dao;


import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseHistory;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseHistoryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseHistoryRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.UUID;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@Transactional
class DatabasesHistoryDaoEmbeddedTest {

    @Inject
    DatabaseHistoryDbaasRepository databaseHistoryDbaasRepository;
    @Inject
    DatabaseHistoryRepository historyRepository;

    @AfterEach
    public void cleanUp() {
        historyRepository.deleteAll();
    }

    @Test
    public void backupRecordTest() {
        DatabaseRegistry database = generateRandomDatabase(true, "mongodb", "test-namespace");
        Assertions.assertEquals(0, historyRepository.listAll().size());
        Integer lastVersion = databaseHistoryDbaasRepository.getLastVersionByName(database.getName());
        Assertions.assertNull(lastVersion);
        DatabaseHistory databaseHistory = new DatabaseHistory(database);
        databaseHistory.setVersion(lastVersion == null ? 0 : lastVersion + 1);
        databaseHistory.setChangeAction(DatabaseHistory.ChangeAction.UPDATE_CLASSIFIER);
        databaseHistoryDbaasRepository.save(databaseHistory);
        Assertions.assertEquals(1, historyRepository.listAll().size());
        DatabaseHistory databaseHistoryActual = historyRepository.listAll().get(0);
        Assertions.assertEquals(database.getName(), databaseHistoryActual.getName());
        Assertions.assertEquals(database.getClassifier(), databaseHistoryActual.getClassifier());
        Assertions.assertEquals(0, databaseHistoryActual.getVersion().intValue());

        lastVersion = databaseHistoryDbaasRepository.getLastVersionByName(database.getName());
        Assertions.assertEquals(0, lastVersion.intValue());
        databaseHistory = new DatabaseHistory(database);
        databaseHistory.setVersion(lastVersion == null ? 0 : lastVersion + 1);
        databaseHistory.setChangeAction(DatabaseHistory.ChangeAction.UPDATE_CLASSIFIER);
        databaseHistoryDbaasRepository.save(databaseHistory);
        Assertions.assertEquals(2, historyRepository.listAll().size());
    }

    @Test
    public void getLastVersionByNameTest() {
        DatabaseRegistry database = generateRandomDatabase(true, "mongodb", "test-namespace");
        addRecordToHistoryDbaasRepository(database, 1);
        addRecordToHistoryDbaasRepository(database, 2);
        addRecordToHistoryDbaasRepository(database, 3);
        addRecordToHistoryDbaasRepository(database, 5);
        addRecordToHistoryDbaasRepository(database, 7);
        Integer lastVersion = databaseHistoryDbaasRepository.getLastVersionByName(database.getName());
        Assertions.assertEquals(Integer.valueOf(7), lastVersion);
        Assertions.assertEquals(5, historyRepository.listAll().size());
    }

    private DatabaseRegistry generateRandomDatabase(boolean isServiceDb, String type, String namespace) {
        Database db = new Database();
        db.setId(UUID.randomUUID());
        db.setType(type);
        db.setName("name-" + UUID.randomUUID().toString());
        String generatedNamespace = "namespace-" + UUID.randomUUID().toString();
        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", generatedNamespace);
        classifier.put("microserviceName", "microserviceName-" + UUID.randomUUID().toString());
        if (isServiceDb) {
            classifier.put("isServiceDb", true);
        } else {
            classifier.put("tenantId", UUID.randomUUID().toString());
        }
        db.setClassifier(classifier);
        HashMap<String, Object> connectionProperties = new HashMap<>();
        connectionProperties.put("username", UUID.randomUUID().toString());
        connectionProperties.put("password", UUID.randomUUID().toString());
        connectionProperties.put("encryptedPassword", connectionProperties.get("password"));
        db.setConnectionProperties(Arrays.asList(connectionProperties));

        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setDatabase(db);
        databaseRegistry.setNamespace(generatedNamespace);
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType(type);
        db.setDatabaseRegistry(Arrays.asList(databaseRegistry));

        return databaseRegistry;
    }

    private void addRecordToHistoryDbaasRepository(DatabaseRegistry database, int version) {
        DatabaseHistory databaseHistory = new DatabaseHistory(database);
        databaseHistory.setVersion(version);
        databaseHistory.setChangeAction(DatabaseHistory.ChangeAction.UPDATE_CLASSIFIER);
        databaseHistoryDbaasRepository.save(databaseHistory);
    }
}
