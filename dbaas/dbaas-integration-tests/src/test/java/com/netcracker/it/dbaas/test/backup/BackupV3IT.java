package com.netcracker.it.dbaas.test.backup;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.it.dbaas.entity.DatabaseResponse;
import com.netcracker.it.dbaas.entity.DatabaseV3;
import com.netcracker.it.dbaas.entity.LinkDatabasesRequest;
import com.netcracker.it.dbaas.entity.backup.v1.*;
import com.netcracker.it.dbaas.entity.backup.v3.Status;
import com.netcracker.it.dbaas.entity.config.DatabaseDeclaration;
import com.netcracker.it.dbaas.helpers.*;
import com.netcracker.it.dbaas.test.AbstractIT;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;

import static com.netcracker.it.dbaas.helpers.BackupHelperV1.BACKUP_METADATA;
import static com.netcracker.it.dbaas.helpers.BackupHelperV1.DIGEST;
import static com.netcracker.it.dbaas.helpers.BackupHelperV3.*;
import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.EXTERNALLY_MANAGEABLE_V3;
import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.calculateDigest;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
@Tag("backup")
class BackupV3IT extends AbstractIT {
    private static BackupHelperV3 backupHelperV3;
    private static BackupHelperV1 backupHelperV1;
    private static MigrationHelper migrationHelper;
    private static DeclarativeConfigHelper declarativeHelper;
    private static BGHelper bgHelper;

    @Override
    protected void closePortForwardAfterTest() {
    }

    @BeforeAll
    static void initHelper() throws IOException {
        backupHelperV3 = new BackupHelperV3(helperV3);
        backupHelperV1 = new BackupHelperV1(helperV3);
        migrationHelper = new MigrationHelper(helperV3);
        bgHelper = new BGHelper(helperV3);
        declarativeHelper = new DeclarativeConfigHelper(helperV3);
        deleteTestData();
    }

    @AfterAll
    static void cleanUp() throws IOException {
        deleteTestData();

        helperV3.closePortForwardConnections();
    }

    private static void deleteTestData() throws IOException {
        log.info("Deleting test data");

        helperV3.deleteAllLogicalDatabasesAndNamespaceBackupsInTestNamespaces();
        bgHelper.destroyAllDomains();
        backupHelperV1.deleteBackupRestore();

        log.info("Deleted test data");
    }

    @Nested
    @EnableExtension
    @Execution(ExecutionMode.CONCURRENT)
    class Database {

        @Nested
        @EnableExtension
        class Postgresql extends AbstractBackup {
            @Override
            protected String databaseType() {
                return POSTGRES_TYPE;
            }

            @Tag("postgresql")
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

            @Tag("postgresql")
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

            @Tag("postgresql")
            @Tag("cassandra")
            @Test
            void testBackupRestoreV1_testExternalDbBackupRestore() throws IOException {
                assumeTrue(helperV3.hasAdapterOfType(databaseType), MessageFormat.format("No {0} adapter. Skip test.", databaseType));
                assumeTrue(helperV3.hasAdapterOfType(CASSANDRA_TYPE), MessageFormat.format("No {0} adapter. Skip test.", CASSANDRA_TYPE));

                String sourceNamespace1 = helperV3.generateTestNamespace();
                String sourceNamespace2 = helperV3.generateTestNamespace();
                String targetNamespace = helperV3.generateTestNamespace();

                var excludedInternalDb1 = helperV3.createDatabase(new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(sourceNamespace1).build(), databaseType, 201);
                var excludedInternalDb2 = helperV3.createDatabase(new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(sourceNamespace1).build(), CASSANDRA_TYPE, 201);
                var backupedExternalDb1 = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, new ClassifierBuilder().ms(DBAAS_AUTO_TEST_2).ns(sourceNamespace1).build(), databaseType, 201);
                var backupedExternalDb2 = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, new ClassifierBuilder().ms(DBAAS_AUTO_TEST_3).ns(sourceNamespace2).build(), databaseType, 201);

                var backupRequest = new BackupRequestBuilder()
                        .filterCriteria(fc -> fc
                                .include(f -> f.ns(sourceNamespace1).dbType(databaseType))
                                .include(f -> f.ns(sourceNamespace2).ms(DBAAS_AUTO_TEST_3))
                                .exclude(f -> f.ns(sourceNamespace1).ms(DBAAS_AUTO_TEST_1))
                        ).externalDatabaseStrategy(ExternalDatabaseStrategy.INCLUDE).build();
                var backupResponse = backupHelperV1.runBackupAndWait(backupRequest, false);
                assertEquals(BackupStatus.COMPLETED, backupResponse.getStatus());
                assertEquals(0, backupResponse.getLogicalBackups().size());
                assertEquals(2, backupResponse.getExternalDatabases().size());

                var restoreRequest = new RestoreRequestBuilder()
                        .mapping(m -> m
                                .ns(sourceNamespace1, targetNamespace)
                                .ns(sourceNamespace2, targetNamespace)
                        ).externalDbStrategy(ExternalDatabaseStrategy.INCLUDE).build();
                var restoreResponse = backupHelperV1.runRestoreAndWait(backupRequest.getBackupName(), restoreRequest, false);
                assertEquals(RestoreStatus.COMPLETED, restoreResponse.getStatus());

                var restoredExternalDb1 = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, new ClassifierBuilder().ms(DBAAS_AUTO_TEST_2).ns(targetNamespace).build(), databaseType, 200);
                var restoredExternalDb2 = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, new ClassifierBuilder().ms(DBAAS_AUTO_TEST_3).ns(targetNamespace).build(), databaseType, 200);

                assertNotEquals(backupedExternalDb1.getId(), restoredExternalDb1.getId());
                assertNotEquals(backupedExternalDb2.getId(), restoredExternalDb2.getId());

                assertEquals(backupedExternalDb1.getName(), restoredExternalDb1.getName());
                assertEquals(backupedExternalDb2.getName(), restoredExternalDb2.getName());

                assertEquals(List.of(), restoredExternalDb1.getConnectionProperties());
                assertEquals(List.of(), restoredExternalDb2.getConnectionProperties());
            }

            @Tag("postgresql")
            @Tag("cassandra")
            @Test
            void testBackupRestoreV1_externalInternalDbBackupRestore() throws IOException {
                assumeTrue(helperV3.hasAdapterOfType(databaseType), MessageFormat.format("No {0} adapter. Skip test.", databaseType));
                assumeTrue(helperV3.hasAdapterOfType(CASSANDRA_TYPE), MessageFormat.format("No {0} adapter. Skip test.", CASSANDRA_TYPE));

                String sourceNamespace1 = helperV3.generateTestNamespace();
                String sourceNamespace2 = helperV3.generateTestNamespace();
                String targetNamespace = helperV3.generateTestNamespace();

                String sourceTenantId = helperV3.generateTestNamespace();
                String targetTenantId = helperV3.generateTestNamespace();

                var backupedInternalDb1 = helperV3.createDatabase(new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(sourceNamespace1).tenantId(sourceTenantId).tenant().build(), databaseType, 201);
                var excludedInternalDb2 = helperV3.createDatabase(new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(sourceNamespace1).build(), CASSANDRA_TYPE, 201);
                var backupedExternalDb1 = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, new ClassifierBuilder().ms(DBAAS_AUTO_TEST_2).ns(sourceNamespace1).build(), databaseType, 201);
                var backupedExternalDb2 = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, new ClassifierBuilder().ms(DBAAS_AUTO_TEST_3).ns(sourceNamespace2).build(), databaseType, 201);

                backupHelperV3.checkConnections(false, List.of(backupedInternalDb1, excludedInternalDb2), BACKUPED_DATA, BACKUPED_DATA, false);

                var backupRequest = new BackupRequestBuilder()
                        .filterCriteria(fc -> fc
                                .include(f -> f.ns(sourceNamespace1).dbType(databaseType))
                                .include(f -> f.ns(sourceNamespace2).ms(DBAAS_AUTO_TEST_3))
                        ).externalDatabaseStrategy(ExternalDatabaseStrategy.INCLUDE).build();
                var backupResponse = backupHelperV1.runBackupAndWait(backupRequest, false);
                assertEquals(BackupStatus.COMPLETED, backupResponse.getStatus());
                assertEquals(1, backupResponse.getLogicalBackups().getFirst().getBackupDatabases().size());
                assertEquals(2, backupResponse.getExternalDatabases().size());

                String changedData = "changedData";
                backupHelperV3.checkConnections(false, List.of(backupedInternalDb1), changedData, changedData, false);

                var restoreRequest = new RestoreRequestBuilder()
                        .mapping(m -> m
                                .ns(sourceNamespace1, targetNamespace)
                                .ns(sourceNamespace2, targetNamespace)
                                .tenant(sourceTenantId, targetTenantId)
                        ).externalDbStrategy(ExternalDatabaseStrategy.INCLUDE).build();
                var restoreResponse = backupHelperV1.runRestoreAndWait(backupRequest.getBackupName(), restoreRequest, false);
                assertEquals(RestoreStatus.COMPLETED, restoreResponse.getStatus());

                var restoredInternalDb = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                        new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(targetNamespace).tenantId(targetTenantId).tenant().build(), targetNamespace, databaseType, 200
                );

                assertNotEquals(backupedInternalDb1.getName(), restoredInternalDb.getName());
                assertNotEquals(backupedInternalDb1.getConnectionProperties(), restoredInternalDb.getConnectionProperties());

                var restoredExternalDb1 = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, new ClassifierBuilder().ms(DBAAS_AUTO_TEST_2).ns(targetNamespace).build(), databaseType, 200);
                var restoredExternalDb2 = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, new ClassifierBuilder().ms(DBAAS_AUTO_TEST_3).ns(targetNamespace).build(), databaseType, 200);

                assertNotEquals(backupedExternalDb1.getId(), restoredExternalDb1.getId());
                assertNotEquals(backupedExternalDb2.getId(), restoredExternalDb2.getId());

                assertEquals(backupedExternalDb1.getName(), restoredExternalDb1.getName());
                assertEquals(backupedExternalDb2.getName(), restoredExternalDb2.getName());

                assertEquals(List.of(), restoredExternalDb1.getConnectionProperties());
                assertEquals(List.of(), restoredExternalDb2.getConnectionProperties());

                backupHelperV3.checkConnections(false, List.of(backupedInternalDb1), null, changedData, false);
                backupHelperV3.checkConnections(false, List.of(restoredInternalDb), null, BACKUPED_DATA, false);
            }

            @Tag("postgresql")
            @Test
            void testBackupRestoreV1_testParallelBackupRestore() throws IOException {
                assumeTrue(helperV3.hasAdapterOfType(databaseType), MessageFormat.format("No {0} adapter. Skip test.", databaseType));

                var namespace = helperV3.generateTestNamespace();

                var backupedDb = helperV3.createDatabase(new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(namespace).build(), databaseType, 201);
                backupHelperV3.checkConnections(false, List.of(backupedDb), BACKUPED_DATA, BACKUPED_DATA, false);

                var backupRequest1 = new BackupRequestBuilder().filterCriteria(fc -> fc.include(f -> f.ns(namespace))).build();
                var backupRequest2 = new BackupRequestBuilder().filterCriteria(fc -> fc.include(f -> f.ns(namespace))).build();

                var backupResponse1 = backupHelperV1.startBackup(backupRequest1, false, 202);
                var backupResponse2 = backupHelperV1.startBackup(backupRequest2, false, 202);

                assertEquals(BackupStatus.IN_PROGRESS, backupResponse1.getStatus());
                assertEquals(BackupStatus.IN_PROGRESS, backupResponse2.getStatus());

                backupResponse1 = backupHelperV1.waitBackupFinish(backupRequest1.getBackupName());
                assertEquals(BackupStatus.COMPLETED, backupResponse1.getStatus());

                backupHelperV1.startRestoreParallel(backupRequest1.getBackupName(), new RestoreRequestBuilder().build(), false, 202);
                backupHelperV1.startRestore(backupRequest1.getBackupName(), new RestoreRequestBuilder().build(), false, 409);
            }

            @Tag("postgresql")
            @Test
            void testBackupRestoreV1_testMigratedExternalToInternalDatabasesBackupRestore() throws IOException {
                assumeTrue(helperV3.hasAdapterOfType(POSTGRES_TYPE),
                        MessageFormat.format("No {0} adapter. Skip test.", POSTGRES_TYPE));

                String sourceNamespace = helperV3.generateTestNamespace();
                String targetNamespace = helperV3.generateTestNamespace();

                DatabaseResponse migratedWithUserCreation = migrationHelper.prepareMigratedExternalToInternalPostgresDatabase(
                        sourceNamespace,
                        DBAAS_AUTO_TEST_1,
                        true
                );

                DatabaseResponse migratedWithoutUserCreation = migrationHelper.prepareMigratedExternalToInternalPostgresDatabase(
                        sourceNamespace,
                        DBAAS_AUTO_TEST_2,
                        false
                );

                List<DatabaseResponse> migratedDbs = List.of(
                        migratedWithUserCreation,
                        migratedWithoutUserCreation
                );

                backupHelperV3.checkConnections(
                        false,
                        migratedDbs,
                        BACKUPED_DATA,
                        BACKUPED_DATA,
                        false
                );

                var backupRequest = new BackupRequestBuilder()
                        .filterCriteria(fc -> fc.include(f -> f
                                .ns(sourceNamespace)
                                .dbType(POSTGRES_TYPE)))
                        .build();

                var backupResponse = backupHelperV1.runBackupAndWait(backupRequest, false);

                assertEquals(BackupStatus.COMPLETED, backupResponse.getStatus(),
                        "Backup should be completed for migrated external-to-internal PostgreSQL databases");

                int backedUpDatabasesCount = backupResponse.getLogicalBackups().stream()
                        .mapToInt(logicalBackup -> logicalBackup.getBackupDatabases().size())
                        .sum();

                assertEquals(2, backedUpDatabasesCount,
                        "Backup should include both migrated external-to-internal PostgreSQL databases");

                String changedData = "changedData";

                backupHelperV3.checkConnections(
                        false,
                        migratedDbs,
                        changedData,
                        changedData,
                        false
                );

                var restoreRequest = new RestoreRequestBuilder()
                        .mapping(m -> m.ns(sourceNamespace, targetNamespace))
                        .build();

                var restoreResponse = backupHelperV1.runRestoreAndWait(
                        backupRequest.getBackupName(),
                        restoreRequest,
                        false
                );

                assertEquals(RestoreStatus.COMPLETED, restoreResponse.getStatus(),
                        "Restore should be completed for migrated external-to-internal PostgreSQL databases");

                DatabaseResponse restoredWithUserCreation = helperV3.getDatabaseByClassifierAsPOJO(
                        helperV3.getClusterDbaAuthorization(),
                        new ClassifierBuilder()
                                .ms(DBAAS_AUTO_TEST_1)
                                .ns(targetNamespace)
                                .build(),
                        targetNamespace,
                        POSTGRES_TYPE,
                        HttpStatus.OK.value()
                );

                DatabaseResponse restoredWithoutUserCreation = helperV3.getDatabaseByClassifierAsPOJO(
                        helperV3.getClusterDbaAuthorization(),
                        new ClassifierBuilder()
                                .ms(DBAAS_AUTO_TEST_2)
                                .ns(targetNamespace)
                                .build(),
                        targetNamespace,
                        POSTGRES_TYPE,
                        HttpStatus.OK.value()
                );

                assertNotEquals(migratedWithUserCreation.getName(), restoredWithUserCreation.getName(),
                        "Restored with-user-creation database should have a different physical database name");

                assertNotEquals(migratedWithoutUserCreation.getName(), restoredWithoutUserCreation.getName(),
                        "Restored without user creation database should have a different physical database name");

                assertNotEquals(migratedWithUserCreation.getConnectionProperties(),
                        restoredWithUserCreation.getConnectionProperties(),
                        "Restored with user creation database should have different connection properties");

                assertNotEquals(migratedWithoutUserCreation.getConnectionProperties(),
                        restoredWithoutUserCreation.getConnectionProperties(),
                        "Restored without user creation database should have different connection properties");

                backupHelperV3.checkConnections(
                        false,
                        migratedDbs,
                        null,
                        changedData,
                        false
                );

                backupHelperV3.checkConnections(
                        false,
                        List.of(restoredWithUserCreation, restoredWithoutUserCreation),
                        null,
                        BACKUPED_DATA,
                        false
                );
            }
        }

        @Tag("cassandra")
        @Nested
        @EnableExtension
        class Cassandra extends AbstractBackup {
            @Override
            protected String databaseType() {
                return CASSANDRA_TYPE;
            }
        }

        @Tag("mongodb")
        @Nested
        @EnableExtension
        class Mongodb extends AbstractBackup {
            @Override
            protected String databaseType() {
                return MONGODB_TYPE;
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

        @Tag("opensearch")
        @Nested
        @EnableExtension
        class Opensearch extends AbstractBackup {
            @Override
            protected String databaseType() {
                return OPENSEARCH_TYPE;
            }
        }

        @Tag("clickhouse")
        @Nested
        @EnableExtension
        class Clickhouse extends AbstractBackup {
            @Override
            protected String databaseType() {
                return CLICKHOUSE_TYPE;
            }
        }

        @Tag("arangodb")
        @Nested
        @EnableExtension
        class Arangodb extends AbstractBackup {
            @Override
            protected String databaseType() {
                return ARANGODB_TYPE;
            }
        }
    }

    abstract static class AbstractBackup {
        protected abstract String databaseType();

        protected String databaseType = databaseType();

        @Test
        void testCollectAndRestoreBackupToTargetNamespace() throws IOException, InterruptedException {
            assumeTrue(helperV3.hasAdapterOfType(databaseType), MessageFormat.format("No {0} adapter. Skip test.", databaseType));

            backupHelperV3.testCollectNamespaceBackupWithTwoLogicalDatabasesAndRestoreItToTargetNamespace(databaseType);
        }

        @Test
        void testCollectAndRestoreBackupWithDeltaCheckConnectionsAndData() throws IOException, InterruptedException {
            assumeTrue(helperV3.hasAdapterOfType(databaseType), MessageFormat.format("No {0} adapter. Skip test.", databaseType));

            backupHelperV3.testCollectNamespaceBackupWithTwoLogicalDatabasesAndRestoreItToTheSameNamespaceWithDelta(databaseType);
        }

        @Test
        void testDeleteDatabaseBackup() throws IOException, InterruptedException {

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

        @Test
        void testBackupRestoreV1_importAndExportBackupMetadata() throws IOException {
            assumeTrue(helperV3.hasAdapterOfType(databaseType), MessageFormat.format("No {0} adapter. Skip test.", databaseType));

            String sourceNamespace = helperV3.generateTestNamespace();
            String targetNamespace = helperV3.generateTestNamespace();

            var backupedLogicalDatabase1 = helperV3.createDatabase(new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(sourceNamespace).build(), databaseType, 201);
            var backupedLogicalDatabase2 = helperV3.createDatabase(new ClassifierBuilder().ms(DBAAS_AUTO_TEST_2).ns(sourceNamespace).build(), databaseType, 201);

            backupHelperV3.checkConnections(false, List.of(backupedLogicalDatabase1, backupedLogicalDatabase2), BACKUPED_DATA, BACKUPED_DATA, false);

            var backupRequest = new BackupRequestBuilder().filterCriteria(fc -> fc.include(f -> f.ns(sourceNamespace))).build();
            var backupResponse = backupHelperV1.runBackupAndWait(backupRequest, false);
            assertEquals(BackupStatus.COMPLETED, backupResponse.getStatus());

            String backupName = backupResponse.getBackupName();
            var backupMetadata = backupHelperV1.getBackupMetadata(backupName);
            helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), sourceNamespace);
            backupHelperV1.deleteBackupFromDb(backupName);
            assertNull(backupHelperV1.getBackup(backupName, 404));

            backupHelperV1.uploadBackupMetadata((String) backupMetadata.get(DIGEST), (BackupResponse) backupMetadata.get(BACKUP_METADATA), 200);

            var restoreRequest = new RestoreRequestBuilder().mapping(m -> m.ns(sourceNamespace, targetNamespace)).build();
            var restoreResponse = backupHelperV1.runRestoreAndWait(backupRequest.getBackupName(), restoreRequest, false);
            assertEquals(RestoreStatus.COMPLETED, restoreResponse.getStatus());

            var restoredLogicalDatabaseInTarget1 = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                    new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(targetNamespace).build(),
                    targetNamespace, databaseType, 200
            );
            var restoredLogicalDatabaseInTarget2 = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                    new ClassifierBuilder().ms(DBAAS_AUTO_TEST_2).ns(targetNamespace).build(),
                    targetNamespace, databaseType, 200
            );

            assertNotEquals(backupedLogicalDatabase1.getName(), restoredLogicalDatabaseInTarget1.getName());
            assertNotEquals(backupedLogicalDatabase2.getName(), restoredLogicalDatabaseInTarget2.getName());
            assertNotEquals(backupedLogicalDatabase1.getConnectionProperties(), restoredLogicalDatabaseInTarget1.getConnectionProperties());
            assertNotEquals(backupedLogicalDatabase2.getConnectionProperties(), restoredLogicalDatabaseInTarget2.getConnectionProperties());

            backupHelperV3.checkConnections(true, List.of(backupedLogicalDatabase1, backupedLogicalDatabase2), null, BACKUPED_DATA, false);
            backupHelperV3.checkConnections(false, List.of(restoredLogicalDatabaseInTarget1, restoredLogicalDatabaseInTarget2), null, BACKUPED_DATA, false);
        }

        @Test
        void testBackupRestoreV1_testEnrichingBackupRestore() throws IOException {
            assumeTrue(helperV3.hasAdapterOfType(databaseType), MessageFormat.format("No {0} adapter. Skip test.", databaseType));

            String targetNamespace = helperV3.generateTestNamespace();
            String mappedNamespace1 = helperV3.generateTestNamespace();
            String mappedNamespace2 = helperV3.generateTestNamespace();
            var mappedNamespaces = List.of(mappedNamespace1, mappedNamespace2);

            String activeNamespace = helperV3.generateTestNamespace();
            String candidateNamespace = helperV3.generateTestNamespace();
            String controllerNamespace = helperV3.generateTestNamespace();

            try (Response initResponse = bgHelper.initDomain(activeNamespace, candidateNamespace, controllerNamespace)) {
                assertEquals(200, initResponse.code());
            }

            var payload = new DatabaseDeclaration.DeclarativeDBConfigBuilder()
                    .type(databaseType)
                    .classifier(new ClassifierBuilder().ms(DBAAS_AUTO_TEST_2))
                    .versioning("new")
                    .settings(helperV3.applySettings(databaseType, null))
                    .build().asPayload(activeNamespace, DBAAS_AUTO_TEST_2);
            declarativeHelper.applyDeclarativeConfig(payload);

            bgHelper.doWarmup(activeNamespace, candidateNamespace, controllerNamespace);

            var activeDatabaseTrans = helperV3.createDatabase(new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(activeNamespace).build(), databaseType, 201);
            var candidateDatabaseTrans = helperV3.createDatabase(new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(candidateNamespace).build(), databaseType, 200);

            var activeDatabaseConfig = helperV3.createDatabase(new ClassifierBuilder().ms(DBAAS_AUTO_TEST_2).ns(activeNamespace).build(), databaseType, 200);
            var candidateDatabaseConfig = helperV3.createDatabase(new ClassifierBuilder().ms(DBAAS_AUTO_TEST_2).ns(candidateNamespace).build(), databaseType, 200);

            var existDbInTargetEnv = helperV3.createDatabase(new ClassifierBuilder().ns(targetNamespace).ms(DBAAS_AUTO_TEST_1).build(), databaseType, 201);
            var immutableDbBefore = helperV3.createDatabase(new ClassifierBuilder().ns(targetNamespace).ms(DBAAS_AUTO_TEST_3).build(), databaseType, 201);

            for (int i = 0; i < 2; i++) {
                String namespace = mappedNamespaces.get(i);
                LinkDatabasesRequest requestBody = new LinkDatabasesRequest(List.of(DBAAS_AUTO_TEST_1), namespace);
                Request request = helperV3.createRequest(String.format("api/v3/dbaas/namespaces/%s/databases/link", targetNamespace),
                        helperV3.getDbaasDbEditorAuthorization(),
                        requestBody,
                        "POST");
                var response = helperV3.executeRequest(request, List.class, 200);
                assertEquals(1, response.size());
            }

            assertEquals(activeDatabaseTrans.getConnectionProperties(), candidateDatabaseTrans.getConnectionProperties());
            backupHelperV3.checkConnections(false,
                    List.of(activeDatabaseTrans, activeDatabaseConfig, candidateDatabaseConfig, existDbInTargetEnv, immutableDbBefore),
                    BACKUPED_DATA, BACKUPED_DATA, false);

            var backupRequest = new BackupRequestBuilder()
                    .filterCriteria(fc -> fc
                            .include(f -> f.ns(activeNamespace))
                            .include(f -> f.ns(candidateNamespace))
                    ).build();
            var backupResponse = backupHelperV1.runBackupAndWait(backupRequest, false);
            assertEquals(BackupStatus.COMPLETED, backupResponse.getStatus());

            var restoreRequest = new RestoreRequestBuilder()
                    .filterCriteria(fc -> fc
                            .include(f -> f.ns(activeNamespace).dbKind(DatabaseKind.TRANSACTIONAL))
                            .include(f -> f.ns(candidateNamespace))
                            .exclude(f -> f.dbKind(DatabaseKind.CONFIGURATION))
                    ).mapping(m -> m
                            .ns(activeNamespace, targetNamespace)
                            .ns(candidateNamespace, mappedNamespace1)
                    ).build();
            var restoreResponse = backupHelperV1.runRestoreAndWait(backupRequest.getBackupName(), restoreRequest, false);
            assertEquals(RestoreStatus.COMPLETED, restoreResponse.getStatus());

            var dbToRestore = restoreResponse.getLogicalRestores().getFirst().getRestoreDatabases().getFirst();
            assertEquals(3, dbToRestore.getClassifiers().size());

            var restoredDb1 = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                    new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(targetNamespace).build(),
                    targetNamespace, databaseType, 200);
            var restoredDb2 = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                    new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(mappedNamespace1).build(),
                    mappedNamespace1, databaseType, 200);
            var restoredDb3 = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                    new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(mappedNamespace2).build(),
                    mappedNamespace2, databaseType, 200);

            assertNotEquals(activeDatabaseTrans.getName(), restoredDb1.getName());
            assertNotEquals(candidateDatabaseTrans.getName(), restoredDb2.getName());

            assertNotEquals(activeDatabaseTrans.getConnectionProperties(), restoredDb1.getConnectionProperties());
            assertNotEquals(candidateDatabaseTrans.getConnectionProperties(), restoredDb2.getConnectionProperties());

            assertEquals(restoredDb1.getConnectionProperties(), restoredDb2.getConnectionProperties());
            assertEquals(restoredDb1.getConnectionProperties(), restoredDb3.getConnectionProperties());

            var markedDb1 = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                    new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(targetNamespace).markedForDrop().build(),
                    targetNamespace, databaseType, 200);
            var markedDb2 = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                    new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(mappedNamespace1).markedForDrop().build(),
                    mappedNamespace1, databaseType, 200);
            var markedDb3 = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                    new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(mappedNamespace2).markedForDrop().build(),
                    mappedNamespace2, databaseType, 200);

            var immutableDbAfter = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                    new ClassifierBuilder().ms(DBAAS_AUTO_TEST_3).ns(targetNamespace).build(),
                    targetNamespace, databaseType, 200);
            assertEquals(immutableDbBefore.getName(), immutableDbAfter.getName());
            assertEquals(immutableDbBefore.getConnectionProperties(), immutableDbAfter.getConnectionProperties());

            backupHelperV3.checkConnections(false,
                    List.of(activeDatabaseTrans, candidateDatabaseTrans, activeDatabaseConfig, candidateDatabaseConfig, existDbInTargetEnv, immutableDbBefore),
                    null, BACKUPED_DATA, false);
            backupHelperV3.checkConnections(false, List.of(restoredDb1), null, BACKUPED_DATA, false);
            backupHelperV3.checkConnections(false, List.of(markedDb1), null, BACKUPED_DATA, false);
        }

        @Test
        void testBackupRestoreV1_restoreDeletedBackup() throws IOException {
            assumeTrue(helperV3.hasAdapterOfType(databaseType), MessageFormat.format("No {0} adapter. Skip test.", databaseType));

            var sourceNamespace = helperV3.generateTestNamespace();
            var targetNamespace = helperV3.generateTestNamespace();

            var backupedLogicalDatabaseInSource1 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), DBAAS_AUTO_TEST_1, 201, databaseType, null, sourceNamespace, false);
            var backupedLogicalDatabaseInSource2 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), DBAAS_AUTO_TEST_2, 201, databaseType, null, sourceNamespace, false);
            backupHelperV3.checkConnections(false, List.of(backupedLogicalDatabaseInSource1, backupedLogicalDatabaseInSource2), BACKUPED_DATA, BACKUPED_DATA, false);

            var backupRequest = new BackupRequestBuilder()
                    .filterCriteria(fc -> fc
                            .include(f -> f.ns(sourceNamespace))).build();
            var backupResponse = backupHelperV1.runBackupAndWait(backupRequest, false);
            assertEquals(BackupStatus.COMPLETED, backupResponse.getStatus());

            var backupMetadataResponse = backupHelperV1.getBackupMetadata(backupResponse.getBackupName());
            backupHelperV1.deleteBackup(backupResponse.getBackupName(), true);
            backupHelperV1.uploadBackupMetadata((String) backupMetadataResponse.get(DIGEST), (BackupResponse) backupMetadataResponse.get(BACKUP_METADATA), 409);

            var metadata = (BackupResponse) backupMetadataResponse.get(BACKUP_METADATA);
            metadata.setBackupName(helperV3.generateTestNamespace());

            var digest = calculateDigest(metadata);
            backupHelperV1.uploadBackupMetadata(digest, metadata, 200);

            assertNotEquals(backupResponse.getBackupName(), metadata.getBackupName());

            var restoreRequest = new RestoreRequestBuilder().mapping(m -> m.ns(sourceNamespace, targetNamespace)).build();
            var restoreResponse = backupHelperV1.runRestoreAndWait(metadata.getBackupName(), restoreRequest, false);
            assertEquals(RestoreStatus.FAILED, restoreResponse.getStatus());
        }

        @Test
        void testBackupRestoreV1_backupRestore() throws IOException {
            assumeTrue(helperV3.hasAdapterOfType(databaseType), MessageFormat.format("No {0} adapter. Skip test.", databaseType));

            var sourceNamespace = helperV3.generateTestNamespace();

            var backupedSourceDb1 = helperV3.createDatabase(new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(sourceNamespace).build(), databaseType, 201);
            var backupedSourceDb2 = helperV3.createDatabase(new ClassifierBuilder().ms(DBAAS_AUTO_TEST_2).ns(sourceNamespace).build(), databaseType, 201);

            backupHelperV3.checkConnections(false, List.of(backupedSourceDb1, backupedSourceDb2), BACKUPED_DATA, BACKUPED_DATA, false);

            var backupRequest = new BackupRequestBuilder()
                    .filterCriteria(fc ->
                            fc.include(f -> f.ns(sourceNamespace).dbType(databaseType))
                    ).build();
            var backupResponse = backupHelperV1.runBackupAndWait(backupRequest, false);
            assertEquals(BackupStatus.COMPLETED, backupResponse.getStatus());
            assertEquals(1, backupResponse.getLogicalBackups().size());

            var backupedDbCount = backupResponse.getLogicalBackups().getFirst().getBackupDatabases().size();
            assertEquals(2, backupedDbCount);

            String changedData = "changedData";
            backupHelperV3.checkConnections(false, List.of(backupedSourceDb1, backupedSourceDb2), changedData, changedData, false);

            var restoreRequest = new RestoreRequestBuilder().filterCriteria(fc -> fc.exclude(e -> e.ms(DBAAS_AUTO_TEST_2))).build();
            var restoreResponse = backupHelperV1.runRestoreAndWait(backupRequest.getBackupName(), restoreRequest, false);
            assertEquals(RestoreStatus.COMPLETED, restoreResponse.getStatus());

            var restoredLogicalDatabase = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                    new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(sourceNamespace).build(),
                    sourceNamespace, databaseType, 200);
            assertNotEquals(backupedSourceDb1.getName(), restoredLogicalDatabase.getName());
            assertNotEquals(backupedSourceDb1.getConnectionProperties(), restoredLogicalDatabase.getConnectionProperties());

            var immutableDatabase = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                    new ClassifierBuilder().ms(DBAAS_AUTO_TEST_2).ns(sourceNamespace).build(),
                    sourceNamespace, databaseType, 200);
            assertEquals(backupedSourceDb2.getName(), immutableDatabase.getName());
            assertEquals(backupedSourceDb2.getConnectionProperties(), immutableDatabase.getConnectionProperties());

            var markedLogicalDatabase = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                    new ClassifierBuilder().ms(DBAAS_AUTO_TEST_1).ns(sourceNamespace).markedForDrop().build(),
                    sourceNamespace, databaseType, 200
            );
            assertEquals(backupedSourceDb1.getName(), markedLogicalDatabase.getName());
            assertEquals(backupedSourceDb1.getConnectionProperties(), markedLogicalDatabase.getConnectionProperties());

            helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                    new ClassifierBuilder().ms(DBAAS_AUTO_TEST_2).ns(sourceNamespace).markedForDrop().build(),
                    sourceNamespace, databaseType, 404
            );

            backupHelperV3.checkConnections(false, List.of(backupedSourceDb1, backupedSourceDb2), null, changedData, false);
            backupHelperV3.checkConnections(false, List.of(restoredLogicalDatabase), null, BACKUPED_DATA, false);
        }
    }
}
