package com.netcracker.it.dbaas.test.backup;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.it.dbaas.entity.DatabaseV3;
import com.netcracker.it.dbaas.entity.backup.v3.Status;
import com.netcracker.it.dbaas.entity.backup.v1.BackupStatus;
import com.netcracker.it.dbaas.entity.backup.v1.ExternalDatabaseStrategy;
import com.netcracker.it.dbaas.entity.backup.v1.RestoreStatus;
import com.netcracker.it.dbaas.helpers.BackupHelperV1;
import com.netcracker.it.dbaas.helpers.BackupHelperV3;
import com.netcracker.it.dbaas.helpers.ClassifierBuilder;
import com.netcracker.it.dbaas.test.AbstractIT;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;

import static com.netcracker.it.dbaas.helpers.BackupHelperV1.DEFAULT_BLOB_PATH;
import static com.netcracker.it.dbaas.helpers.BackupHelperV1.DEFAULT_STORAGE;
import static com.netcracker.it.dbaas.helpers.BackupHelperV3.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
@Tag("backup")
class BackupV3IT extends AbstractIT {

    private static BackupHelperV3 backupHelperV3;
    private static BackupHelperV1 backupHelperV1;

    @Override
    protected void closePortForwardAfterTest() {
    }

    @BeforeAll
    static void initHelper() {
        backupHelperV3 = new BackupHelperV3(dbaasServiceUrl, helperV3);
        backupHelperV1 = new BackupHelperV1(dbaasServiceUrl, helperV3);
        deleteTestData();
    }

    @AfterAll
    static void cleanUp() {
        deleteTestData();

        helperV3.closePortForwardConnections();
    }

    private static void deleteTestData() {
        log.info("Deleting test data");

        helperV3.deleteAllLogicalDatabasesAndNamespaceBackupsInTestNamespaces();

        log.info("Deleted test data");
    }

    @Nested
    @EnableExtension
    @Execution(ExecutionMode.CONCURRENT)
    class Database {

        @Nested
        @EnableExtension
        class Postgresql {

            private final String databaseType = POSTGRES_TYPE;

            @Test
            void testCollectAndRestoreBackupToTargetNamespace() throws IOException, InterruptedException {
                BackupV3IT.this.testCollectAndRestoreBackupToTargetNamespace(databaseType);
            }

            @Test
            void testCollectAndRestoreBackupWithDeltaCheckConnectionsAndData() throws IOException, InterruptedException {
                BackupV3IT.this.testCollectAndRestoreBackupWithDeltaCheckConnectionsAndData(databaseType);
            }

            @Test
            void testDeleteDatabaseBackup() throws IOException, InterruptedException {
                BackupV3IT.this.testDeleteDatabaseBackup(databaseType);
            }

            @Test
            void testRestoreNamespaceBackupAfterDeletionAllLogicalDatabases() throws IOException, InterruptedException {
                var namespace = helperV3.generateTestNamespace();

                log.info("Run backup with postgres databases");

                var namespaceBackup = backupHelperV3.testCollectBackup_TwoDbs(true, namespace, POSTGRES_TYPE, POSTGRES_TYPE);
                var backupedLogicalDatabases = namespaceBackup.getDatabasesCreatedForTest();

                log.info("Drop created databases");

                helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), namespace);

                log.info("Run backup {} restoration", namespaceBackup.getId());

                var namespaceRestoreResult = backupHelperV3.restoreBackup(helperV3.getBackupDaemonAuthorization(), namespaceBackup, true);

                assertEquals(Status.SUCCESS, namespaceRestoreResult.getStatus(), namespaceRestoreResult.getFailReasons() == null ? "require success status" :
                        String.join("\n", namespaceRestoreResult.getFailReasons())
                );

                log.info("Check databases restored successfully");

                backupHelperV3.checkConnections(false, backupedLogicalDatabases, null, BackupHelperV3.BACKUPED_DATA, false);
            }

            @Test
            void testRestoreNamespaceBackupWhenBackupedLogicalDatabasesAreDeletedAndOtherTwoLogicalDatabasesWithTheSameClassifiersExist() throws IOException {
                var namespace = helperV3.generateTestNamespace();

                // create initial 2 databases
                var backupedLogicalDatabase1 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), BackupHelperV3.DBAAS_AUTO_TEST_1, 201, POSTGRES_TYPE, null, namespace, false);
                var backupedLogicalDatabase2 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), DBAAS_AUTO_TEST_2, 201, POSTGRES_TYPE, null, namespace, false);

                // collect backup
                var namespaceBackup = backupHelperV3.collectBackup(helperV3.getBackupDaemonAuthorization(), namespace, false);
                assertThat(namespaceBackup.getDatabases(), hasSize(2));

                // delete database to mimic clean install
                helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), namespace);
                assertThat(helperV3.getDatabasesByNamespace(namespace), hasSize(0));

                // create new 2 databases
                var additionalNotBackupedLogicalDatabase1 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), BackupHelperV3.DBAAS_AUTO_TEST_1, 201, POSTGRES_TYPE, null, namespace, false);
                var additionalNotBackupedLogicalDatabase2 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), DBAAS_AUTO_TEST_2, 201, POSTGRES_TYPE, null, namespace, false);

                assertEquals(backupedLogicalDatabase1.getClassifier(), additionalNotBackupedLogicalDatabase1.getClassifier());
                assertEquals(backupedLogicalDatabase2.getClassifier(), additionalNotBackupedLogicalDatabase2.getClassifier());

                assertNotEquals(backupedLogicalDatabase1.getName(), additionalNotBackupedLogicalDatabase1.getName());
                assertNotEquals(backupedLogicalDatabase2.getName(), additionalNotBackupedLogicalDatabase2.getName());

                //start restore
                backupHelperV3.restoreBackup(helperV3.getBackupDaemonAuthorization(), namespaceBackup);

                var restoredLogicalDatabase1 = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), backupedLogicalDatabase1.getClassifier(), namespace, POSTGRES_TYPE, 200);
                var restoredLogicalDatabase2 = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), backupedLogicalDatabase2.getClassifier(), namespace, POSTGRES_TYPE, 200);

                assertEquals(backupedLogicalDatabase1.getName(), restoredLogicalDatabase1.getName());
                assertEquals(backupedLogicalDatabase2.getName(), restoredLogicalDatabase2.getName());
            }

            @Test
            void testBackupRestoreV1_BackupDBsAndRestoreToEmptyNamespace() throws IOException {
                var sourceNamespace = helperV3.generateTestNamespace();
                var targetNamespace = helperV3.generateTestNamespace();

                var backupedLogicalDatabaseInSource1 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), DBAAS_AUTO_TEST_1, 201, POSTGRES_TYPE, null, sourceNamespace, false);
                var backupedLogicalDatabaseInSource2 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), DBAAS_AUTO_TEST_2, 201, POSTGRES_TYPE, null, sourceNamespace, false);

                backupHelperV3.checkConnections(false, List.of(backupedLogicalDatabaseInSource1, backupedLogicalDatabaseInSource2), BACKUPED_DATA, BACKUPED_DATA, false);

                var backupRequest = backupHelperV1.getBackupRequest(UUID.randomUUID().toString(), DEFAULT_STORAGE, DEFAULT_BLOB_PATH, List.of(sourceNamespace), ExternalDatabaseStrategy.FAIL, false);
                var backupResponse = backupHelperV1.executeBackup(backupRequest, false);
                assertEquals(BackupStatus.COMPLETED, backupResponse.getStatus());

                var restoreRequest = backupHelperV1.getRestoreRequest(UUID.randomUUID().toString(), DEFAULT_STORAGE, DEFAULT_BLOB_PATH, List.of(sourceNamespace), Map.of(sourceNamespace, targetNamespace), Map.of(), ExternalDatabaseStrategy.FAIL);
                var restoreResponse = backupHelperV1.executeRestore(backupRequest.getBackupName(), restoreRequest, false);
                assertEquals(RestoreStatus.COMPLETED, restoreResponse.getStatus());

                var restoredLogicalDatabaseInTarget1 = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                        new ClassifierBuilder().test(DBAAS_AUTO_TEST_1).ns(targetNamespace).build(),
                        targetNamespace, POSTGRES_TYPE, 200
                );

                var restoredLogicalDatabaseInTarget2 = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                        new ClassifierBuilder().test(DBAAS_AUTO_TEST_1).ns(targetNamespace).build(),
                        targetNamespace, POSTGRES_TYPE, 200
                );

                assertNotEquals(backupedLogicalDatabaseInSource1.getName(), restoredLogicalDatabaseInTarget1.getName());
                assertNotEquals(backupedLogicalDatabaseInSource2.getName(), restoredLogicalDatabaseInTarget2.getName());
                assertNotEquals(backupedLogicalDatabaseInSource1.getConnectionProperties(), restoredLogicalDatabaseInTarget1.getConnectionProperties());
                assertNotEquals(backupedLogicalDatabaseInSource2.getConnectionProperties(), restoredLogicalDatabaseInTarget2.getConnectionProperties());

                assertEquals(backupedLogicalDatabaseInSource1.getType(), restoredLogicalDatabaseInTarget1.getType());
                assertEquals(backupedLogicalDatabaseInSource2.getType(), restoredLogicalDatabaseInTarget2.getType());

                assertEquals(targetNamespace, restoredLogicalDatabaseInTarget1.getClassifier().get("namespace"));
                assertEquals(targetNamespace, restoredLogicalDatabaseInTarget2.getClassifier().get("namespace"));
                assertEquals(targetNamespace, restoredLogicalDatabaseInTarget1.getNamespace());
                assertEquals(targetNamespace, restoredLogicalDatabaseInTarget2.getNamespace());

                var restoredLogicalDatabasesInTarget = Arrays.asList(restoredLogicalDatabaseInTarget1, restoredLogicalDatabaseInTarget2);
                backupHelperV3.checkConnections(false, restoredLogicalDatabasesInTarget, null, BACKUPED_DATA, false);
            }
        }

        @Nested
        @EnableExtension
        class Cassandra {

            private final String databaseType = CASSANDRA_TYPE;

            @Test
            void testCollectAndRestoreBackupToTargetNamespace() throws IOException, InterruptedException {
                BackupV3IT.this.testCollectAndRestoreBackupToTargetNamespace(databaseType);
            }

            @Test
            void testCollectAndRestoreBackupWithDeltaCheckConnectionsAndData() throws IOException, InterruptedException {
                BackupV3IT.this.testCollectAndRestoreBackupWithDeltaCheckConnectionsAndData(databaseType);
            }

            @Test
            void testDeleteDatabaseBackup() throws IOException, InterruptedException {
                BackupV3IT.this.testDeleteDatabaseBackup(databaseType);
            }
        }

        @Nested
        @EnableExtension
        class Mongodb {

            private final String databaseType = MONGODB_TYPE;

            @Test
            void testCollectAndRestoreBackupToTargetNamespace() throws IOException, InterruptedException {
                BackupV3IT.this.testCollectAndRestoreBackupToTargetNamespace(databaseType);
            }

            @Test
            void testCollectAndRestoreBackupWithDeltaCheckConnectionsAndData() throws IOException, InterruptedException {
                BackupV3IT.this.testCollectAndRestoreBackupWithDeltaCheckConnectionsAndData(databaseType);
            }

            @Test
            void testDeleteDatabaseBackup() throws IOException, InterruptedException {
                BackupV3IT.this.testDeleteDatabaseBackup(databaseType);
            }

            @Test
            void testCheckDataOfMongodbLogicalDatabaseWithDisabledBackupsAfterRestoreNamespaceBackup() throws IOException {
                assumeTrue(helperV3.hasAdapterOfType(MONGODB_TYPE), "No mongo adapter. Skip test.");

                var namespace = helperV3.generateTestNamespace();

                // logicalDatabaseWithEnabledBackups
                helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), BackupHelperV3.DBAAS_AUTO_TEST_1, 201, MONGODB_TYPE, null, namespace, false);

                var logicalDatabaseWithDisabledBackups1 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), DBAAS_AUTO_TEST_2, 201, MONGODB_TYPE, null, namespace, true);

                var namespaceBackup = backupHelperV3.collectBackup(helperV3.getBackupDaemonAuthorization(), namespace, false);
                assertThat(namespaceBackup.getDatabases(), hasSize(2));

                var logicalDatabasesWithDisabledBackups = namespaceBackup.getDatabases().stream()
                        .filter(DatabaseV3::getBackupDisabled)
                        .toList();

                assertThat(logicalDatabasesWithDisabledBackups, hasSize(1));
                assertEquals(logicalDatabasesWithDisabledBackups.getFirst().getName(), logicalDatabaseWithDisabledBackups1.getName());

                var logicalDatabaseWithDisabledBackups2 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), BackupHelperV3.DBAAS_AUTO_TEST_3, 201, MONGODB_TYPE, null, namespace, true);

                var foundLogicalDatabaseByClassifier = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ns(namespace).test(BackupHelperV3.DBAAS_AUTO_TEST_3).build(), namespace, MONGODB_TYPE, 200);
                assertEquals(logicalDatabaseWithDisabledBackups2.getName(), foundLogicalDatabaseByClassifier.getName());

                var collectionName = "test_collection";
                var documentKey = "text";
                var documentValue = "this message was added before restore";

                try (var mongoClient = helperV3.connectMongo(logicalDatabaseWithDisabledBackups1, false)) {

                    mongoClient.getDatabase(logicalDatabaseWithDisabledBackups1.getName()).getCollection(collectionName)
                            .insertOne(new Document(documentKey, documentValue));
                }

                backupHelperV3.restoreBackup(helperV3.getBackupDaemonAuthorization(), namespaceBackup);

                // expect logicalDatabaseWithDisabledBackups2 is deleted after restore backup
                helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                        new ClassifierBuilder().ns(namespace).test(BackupHelperV3.DBAAS_AUTO_TEST_3).build(), namespace, MONGODB_TYPE, 404
                );

                var restoredLogicalDatabaseWithDisabledBackups1 = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                        new ClassifierBuilder().ns(namespace).test(DBAAS_AUTO_TEST_2).build(), namespace, MONGODB_TYPE, 200
                );

                try (var mongoClient = helperV3.connectMongo(logicalDatabaseWithDisabledBackups1, false)) {

                    long documentsAmount = mongoClient.getDatabase(restoredLogicalDatabaseWithDisabledBackups1.getName())
                            .getCollection(collectionName)
                            .countDocuments();

                    assertEquals(1L, documentsAmount);

                    var actualDocumentValue = (String) mongoClient.getDatabase(restoredLogicalDatabaseWithDisabledBackups1.getName())
                            .getCollection(collectionName)
                            .find()
                            .first()
                            .get(documentKey);

                    assertEquals(documentValue, actualDocumentValue);
                }
            }
        }

        @Nested
        @EnableExtension
        class Opensearch {

            private final String databaseType = OPENSEARCH_TYPE;

            @Test
            void testCollectAndRestoreBackupToTargetNamespace() throws IOException, InterruptedException {
                BackupV3IT.this.testCollectAndRestoreBackupToTargetNamespace(databaseType);
            }

            @Test
            void testCollectAndRestoreBackupWithDeltaCheckConnectionsAndData() throws IOException, InterruptedException {
                BackupV3IT.this.testCollectAndRestoreBackupWithDeltaCheckConnectionsAndData(databaseType);
            }

            @Test
            void testDeleteDatabaseBackup() throws IOException, InterruptedException {
                BackupV3IT.this.testDeleteDatabaseBackup(databaseType);
            }
        }

        @Nested
        @EnableExtension
        class Clickhouse {

            private final String databaseType = CLICKHOUSE_TYPE;

            @Test
            void testCollectAndRestoreBackupToTargetNamespace() throws IOException, InterruptedException {
                BackupV3IT.this.testCollectAndRestoreBackupToTargetNamespace(databaseType);
            }

            @Test
            void testCollectAndRestoreBackupWithDeltaCheckConnectionsAndData() throws IOException, InterruptedException {
                BackupV3IT.this.testCollectAndRestoreBackupWithDeltaCheckConnectionsAndData(databaseType);
            }

            @Test
            void testDeleteDatabaseBackup() throws IOException, InterruptedException {
                BackupV3IT.this.testDeleteDatabaseBackup(databaseType);
            }
        }

        @Nested
        @EnableExtension
        class Arangodb {

            private final String databaseType = ARANGODB_TYPE;

            @Test
            void testCollectAndRestoreBackupToTargetNamespace() throws IOException, InterruptedException {
                BackupV3IT.this.testCollectAndRestoreBackupToTargetNamespace(databaseType);
            }

            @Test
            void testCollectAndRestoreBackupWithDeltaCheckConnectionsAndData() throws IOException, InterruptedException {
                BackupV3IT.this.testCollectAndRestoreBackupWithDeltaCheckConnectionsAndData(databaseType);
            }

            @Test
            void testDeleteDatabaseBackup() throws IOException, InterruptedException {
                BackupV3IT.this.testDeleteDatabaseBackup(databaseType);
            }
        }
    }

    private void testCollectAndRestoreBackupToTargetNamespace(String databaseType) throws IOException, InterruptedException {

        assumeTrue(helperV3.hasAdapterOfType(databaseType), MessageFormat.format("No {0} adapter. Skip test.", databaseType));

        backupHelperV3.testCollectNamespaceBackupWithTwoLogicalDatabasesAndRestoreItToTargetNamespace(databaseType);
    }

    private void testCollectAndRestoreBackupWithDeltaCheckConnectionsAndData(String databaseType) throws IOException, InterruptedException {

        assumeTrue(helperV3.hasAdapterOfType(databaseType), MessageFormat.format("No {0} adapter. Skip test.", databaseType));

        backupHelperV3.testCollectNamespaceBackupWithTwoLogicalDatabasesAndRestoreItToTheSameNamespaceWithDelta(databaseType);
    }

    private void testDeleteDatabaseBackup(String databaseType) throws IOException, InterruptedException {

        assumeTrue(helperV3.hasAdapterOfType(databaseType), MessageFormat.format("No {0} adapter. Skip test.", databaseType));

        var sourceNamespace = helperV3.generateTestNamespace();
        var targetNamespace = helperV3.generateTestNamespace();

        var namespaceBackup = backupHelperV3.testCollectBackup_TwoDbs(true, sourceNamespace, databaseType);
        assertNotNull(namespaceBackup);
        assertTrue(namespaceBackup.canBeDeleted(), "Backup " + namespaceBackup + " should be deletable");

        var backupedLogicalDatabasesInSource = namespaceBackup.getDatabasesCreatedForTest();
        assertNotNull(backupedLogicalDatabasesInSource);
        backupHelperV3.checkConnections(false, backupedLogicalDatabasesInSource, "afterBackupData", "afterBackupData", false);

        var additionalLogicalDatabasesInTarget = backupHelperV3.createLogicalDatabases(Map.of(DBAAS_AUTO_TEST_1 + "target", databaseType), targetNamespace);
        backupHelperV3.checkConnections(false, additionalLogicalDatabasesInTarget, "targetDataForClean", "targetDataForClean", false);

        var namespaceBackupDeletion = backupHelperV3.deleteBackup(helperV3.getBackupDaemonAuthorization(), namespaceBackup);
        assertNotNull(namespaceBackupDeletion);
        assertNotNull(namespaceBackupDeletion.getDeleteResults());
        assertEquals(Status.SUCCESS, namespaceBackupDeletion.getStatus());
        assertThat(namespaceBackupDeletion.getFailReasons(), is(empty()));

        backupHelperV3.checkConnections(false, additionalLogicalDatabasesInTarget, null, null, false);

        backupHelperV3.assertBackupNotExist(helperV3.getBackupDaemonAuthorization(), namespaceBackup);
    }
}
