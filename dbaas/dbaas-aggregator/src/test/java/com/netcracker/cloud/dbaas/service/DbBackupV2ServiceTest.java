package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.dto.EnsuredUser;
import com.netcracker.cloud.dbaas.dto.backupV2.*;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.DatabaseWithClassifiers;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.LogicalBackupAdapterResponse;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.LogicalRestoreAdapterResponse;
import com.netcracker.cloud.dbaas.entity.pg.*;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.*;
import com.netcracker.cloud.dbaas.entity.shared.AbstractDbState;
import com.netcracker.cloud.dbaas.enums.*;
import com.netcracker.cloud.dbaas.exceptions.*;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.*;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.core.LockProvider;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.internal.stubbing.answers.AnswersWithDelay;
import org.mockito.internal.stubbing.answers.Returns;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.netcracker.cloud.dbaas.Constants.*;
import static com.netcracker.cloud.dbaas.service.DBaaService.MARKED_FOR_DROP;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@Slf4j
@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class DbBackupV2ServiceTest {
    private static final String BLOB_PATH = "blobPath";
    private static final String STORAGE_NAME = "storageName";

    private static final String COMPLETED_STATUS = "completed";
    private static final String FAILED_STATUS = "failed";
    private static final String IN_PROGRESS_STATUS = "inProgress";
    private static final String NOT_STARTED = "notStarted";

    @Inject
    private DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    private DatabaseDbaasRepository dbaasRepository;
    @Inject
    private DBaaService dBaaService;
    @InjectMock
    private PasswordEncryption encryption;
    @InjectSpy
    private BackupRepository backupRepository;
    @Inject
    private LogicalBackupRepository logicalBackupRepository;
    @InjectMock
    private PhysicalDatabasesService physicalDatabasesService;
    @Inject
    private BackupDatabaseRepository backupDatabaseRepository;
    @Inject
    private DbBackupV2Service dbBackupV2Service;
    @InjectMock
    private BalancingRulesService balancingRulesService;
    @Inject
    private RestoreRepository restoreRepository;
    @Inject
    private RestoreDatabaseRepository restoreDatabaseRepository;
    @Inject
    private LogicalRestoreRepository logicalRestoreDatabaseRepository;
    @Inject
    private BackupExternalDatabaseRepository backupExternalDatabaseRepository;
    @Inject
    private RestoreExternalDatabaseRepository restoreExternalDatabaseRepository;
    @Inject
    private BgNamespaceRepository bgNamespaceRepository;
    @Inject
    private LockProvider lockProvider;
    @Inject
    @Named("process-orchestrator")
    private DataSource dataSource;
    @InjectMock
    private DbaaSHelper dbaaSHelper;

    @BeforeEach
    void setUp() {
        databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType()
                .forEach(databaseRegistryDbaasRepository::delete);
        restoreDatabaseRepository.deleteAll();
        logicalRestoreDatabaseRepository.deleteAll();
        restoreExternalDatabaseRepository.deleteAll();
        restoreRepository.deleteAll();
        backupDatabaseRepository.deleteAll();
        logicalBackupRepository.deleteAll();
        backupExternalDatabaseRepository.deleteAll();
        backupRepository.deleteAll();

        Mockito.reset(physicalDatabasesService);
    }

    @Test
    void backup_backupFinishedStatusCompletedExternalDatabaseStrategyInclude() {
        String backupName = "backupName";
        String namespace = "test-namespace";
        String tenantId = "test-tenant";
        String bgVersion = "bgVersion";

        String adapterId1 = "pg_adapter_1";
        String adapterId2 = "pg_adapter_2";
        String adapterId3 = "pg_adapter_3";

        String backupId1 = "backupId1";
        String backupId2 = "backupId2";

        String microserviceName1 = "microservice_1";
        String microserviceName2 = "microservice_2";
        String microserviceName3 = "microservice_3";
        String microserviceName4 = "microservice_4";
        String microserviceName5 = "microservice_5";
        String microserviceName6 = "microservice_6";

        String dbName1 = "pg_db_1";
        String dbName2 = "pg_db_2";
        String dbName3 = "pg_db_3";
        String dbName4 = "pg_db_4";
        String dbName5 = "pg_db_5";

        String postgresType = "postgresql";

        Database database1 = getDatabase(adapterId1, dbName1, false, false, null);
        Database database2 = getDatabase(adapterId1, dbName2, false, false, bgVersion);
        Database database3 = getDatabase(adapterId2, dbName3, false, false, null);
        Database database4 = getDatabase(adapterId3, dbName4, false, false, null);
        Database database5 = getDatabase(null, dbName5, true, false, null);

        DatabaseRegistry registry1 = getDatabaseRegistry(database1, namespace, microserviceName1, tenantId, postgresType);
        DatabaseRegistry registry2 = getDatabaseRegistry(database1, namespace, microserviceName2, tenantId, postgresType);
        DatabaseRegistry registry3 = getDatabaseRegistry(database2, namespace, microserviceName3, tenantId, postgresType);
        DatabaseRegistry registry4 = getDatabaseRegistry(database3, namespace, microserviceName4, tenantId, postgresType);
        DatabaseRegistry registry5 = getDatabaseRegistry(database4, namespace, microserviceName5, tenantId, postgresType);
        DatabaseRegistry registry6 = getDatabaseRegistry(database5, namespace, microserviceName6, tenantId, postgresType);

        BackupRequest backupRequest = getBackupRequest(
                backupName,
                List.of(namespace),
                ExternalDatabaseStrategy.INCLUDE,
                true
        );

        DbaasAdapter adapter1 = Mockito.mock(DbaasAdapter.class);
        DbaasAdapter adapter2 = Mockito.mock(DbaasAdapter.class);
        DbaasAdapter adapter3 = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapterId1)).thenReturn(adapter1);
        when(physicalDatabasesService.getAdapterById(adapterId2)).thenReturn(adapter2);
        when(physicalDatabasesService.getAdapterById(adapterId3)).thenReturn(adapter3);
        when(adapter1.type()).thenReturn(postgresType);
        when(adapter2.type()).thenReturn(postgresType);
        when(adapter3.type()).thenReturn(postgresType);
        when(adapter1.isBackupRestoreSupported()).thenReturn(true);
        when(adapter2.isBackupRestoreSupported()).thenReturn(true);
        when(adapter3.isBackupRestoreSupported()).thenReturn(false);

        // Response from adapters to start backup request
        LogicalBackupAdapterResponse adapterResponse1 = LogicalBackupAdapterResponse.builder()
                .status(IN_PROGRESS_STATUS)
                .backupId(backupId1)
                .storageName(STORAGE_NAME)
                .blobPath(BLOB_PATH)
                .databases(List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .databaseName(dbName1)
                                .status(IN_PROGRESS_STATUS)
                                .size(1)
                                .duration(1)
                                .path("path")
                                .build(),
                        LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .databaseName(dbName2)
                                .status(IN_PROGRESS_STATUS)
                                .size(1)
                                .duration(1)
                                .path("path")
                                .build()))
                .build();

        when(adapter1.backupV2(any())).thenReturn(adapterResponse1);

        LogicalBackupAdapterResponse adapterResponse2 = LogicalBackupAdapterResponse.builder()
                .status(IN_PROGRESS_STATUS)
                .backupId(backupId2)
                .storageName(STORAGE_NAME)
                .blobPath(BLOB_PATH)
                .databases(List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                        .databaseName(dbName3)
                        .status(IN_PROGRESS_STATUS)
                        .size(1)
                        .duration(1)
                        .path("path")
                        .build()))
                .build();

        when(adapter2.backupV2(any())).thenReturn(adapterResponse2);


        // Saving databases
        Stream.of(registry1, registry3, registry4, registry5, registry6)
                .forEach(databaseRegistryDbaasRepository::saveAnyTypeLogDb);

        // Sleep to wait saving of databases
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        BackupResponse response = dbBackupV2Service.backup(backupRequest, false);
        assertEquals(BackupStatus.IN_PROGRESS, response.getStatus());

        // Response from adapters to back up status check request
        LogicalBackupAdapterResponse adapterResponse3 = LogicalBackupAdapterResponse.builder()
                .status(COMPLETED_STATUS)
                .backupId(backupId1)
                .storageName(STORAGE_NAME)
                .blobPath(BLOB_PATH)
                .databases(List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .databaseName(dbName1)
                                .status(COMPLETED_STATUS)
                                .size(1)
                                .duration(1)
                                .path("path")
                                .build(),
                        LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .databaseName(dbName2)
                                .status(COMPLETED_STATUS)
                                .size(1)
                                .duration(1)
                                .path("path")
                                .build()))
                .build();
        when(adapter1.trackBackupV2(backupId1, STORAGE_NAME, BLOB_PATH)).thenReturn(adapterResponse3);

        LogicalBackupAdapterResponse adapterResponse4 = LogicalBackupAdapterResponse.builder()
                .status(COMPLETED_STATUS)
                .backupId(backupId2)
                .storageName(STORAGE_NAME)
                .blobPath(BLOB_PATH)
                .databases(List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                        .databaseName(dbName3)
                        .status(COMPLETED_STATUS)
                        .size(1)
                        .duration(1)
                        .path("path")
                        .build()))
                .build();
        when(adapter2.trackBackupV2(backupId2, STORAGE_NAME, BLOB_PATH)).thenReturn(adapterResponse4);
        LockAssert.TestHelper.makeAllAssertsPass(true);
        dbBackupV2Service.checkBackupsAsync();

        Backup backup = backupRepository.findById(backupName);
        assertNotNull(backup, String.format("backup with name '%s' not found", backupName));
        assertEquals(STORAGE_NAME, backup.getStorageName());
        assertEquals(BLOB_PATH, backup.getBlobPath());
        assertEquals(ExternalDatabaseStrategy.INCLUDE, backup.getExternalDatabaseStrategy());
        assertEquals(BackupStatus.COMPLETED, backup.getStatus());
        assertEquals(3, backup.getCompleted());
        assertEquals(3, backup.getSize());
        assertTrue(backup.getErrorMessage().isBlank());
        assertEquals(1, backup.getAttemptCount());
        assertTrue(backup.isIgnoreNotBackupableDatabases());

        FilterCriteriaEntity backupFilter = backup.getFilterCriteria();
        assertNotNull(backupFilter);
        assertEquals(1, backupFilter.getInclude().size());
        assertEquals(namespace, backupFilter.getInclude().getFirst().getNamespace().getFirst());

        List<BackupExternalDatabase> externalDatabases = backup.getExternalDatabases();
        assertNotNull(externalDatabases);
        assertEquals(1, externalDatabases.size());

        BackupExternalDatabase externalDatabase = externalDatabases.stream()
                .filter(db -> dbName5.equals(db.getName()))
                .findAny().orElse(null);
        assertNotNull(externalDatabase, String.format("External database with name '%s' not found", dbName4));
        assertEquals(postgresType, externalDatabase.getType());
        assertEquals(1, externalDatabase.getClassifiers().size());

        SortedMap<String, Object> externalClassifier = externalDatabase.getClassifiers().getFirst();
        assertEquals(namespace, externalClassifier.get(NAMESPACE));
        assertEquals(microserviceName6, externalClassifier.get(MICROSERVICE_NAME));

        List<LogicalBackup> logicalBackups = backup.getLogicalBackups();
        assertEquals(2, logicalBackups.size());

        LogicalBackup logicalBackup1 = logicalBackups.stream().filter(db -> backupId1.equals(db.getLogicalBackupName()))
                .findAny().orElse(null);
        assertNotNull(logicalBackup1, String.format("Logical backup with name '%s' not found", backupId1));
        assertEquals(adapterId1, logicalBackup1.getAdapterId());
        assertEquals(postgresType, logicalBackup1.getType());
        assertEquals(BackupTaskStatus.COMPLETED, logicalBackup1.getStatus());
        assertNull(logicalBackup1.getErrorMessage());
        assertEquals(2, logicalBackup1.getBackupDatabases().size());

        BackupDatabase backupDatabase1 = logicalBackup1.getBackupDatabases().stream()
                .filter(db -> dbName1.equals(db.getName()))
                .findAny().orElse(null);
        assertNotNull(backupDatabase1);
        assertFalse(backupDatabase1.isConfigurational());
        assertEquals(BackupTaskStatus.COMPLETED, backupDatabase1.getStatus());
        assertEquals(1, backupDatabase1.getSize());
        assertEquals(1, backupDatabase1.getDuration());
        assertEquals("path", backupDatabase1.getPath());

        BackupDatabase backupDatabase2 = logicalBackup1.getBackupDatabases().stream()
                .filter(db -> dbName2.equals(db.getName()))
                .findAny().orElse(null);
        assertNotNull(backupDatabase2);
        assertTrue(backupDatabase2.isConfigurational());
        assertEquals(BackupTaskStatus.COMPLETED, backupDatabase2.getStatus());
        assertEquals(1, backupDatabase2.getSize());
        assertEquals(1, backupDatabase2.getDuration());
        assertEquals("path", backupDatabase2.getPath());

        LogicalBackup logicalBackup2 = logicalBackups.stream().filter(db -> backupId2.equals(db.getLogicalBackupName()))
                .findAny().orElse(null);
        assertNotNull(logicalBackup2, String.format("Logical backup with name '%s' not found", backupId2));
        assertEquals(adapterId2, logicalBackup2.getAdapterId());
        assertEquals(postgresType, logicalBackup2.getType());
        assertEquals(BackupTaskStatus.COMPLETED, logicalBackup2.getStatus());
        assertNull(logicalBackup2.getErrorMessage());
        assertEquals(1, logicalBackup2.getBackupDatabases().size());

        BackupDatabase backupDatabase3 = logicalBackup2.getBackupDatabases().stream()
                .filter(db -> dbName3.equals(db.getName()))
                .findAny().orElse(null);
        assertNotNull(backupDatabase3);
        assertFalse(backupDatabase3.isConfigurational());
        assertEquals(BackupTaskStatus.COMPLETED, backupDatabase3.getStatus());
        assertEquals(1, backupDatabase3.getSize());
        assertEquals(1, backupDatabase3.getDuration());
        assertEquals("path", backupDatabase3.getPath());
    }

    @Test
    void backup_backupFinishedStatusCompletedExternalDatabaseStrategySkip() {
        String backupName = "backupName";
        String namespace = "test-namespace";
        String tenantId = "test-tenant";
        String bgVersion = "bgVersion";

        String adapterId1 = "pg_adapter_1";
        String adapterId2 = "pg_adapter_2";
        String adapterId3 = "pg_adapter_3";

        String backupId1 = "backupId1";
        String backupId2 = "backupId2";

        String microserviceName1 = "microservice_1";
        String microserviceName2 = "microservice_2";
        String microserviceName3 = "microservice_3";
        String microserviceName4 = "microservice_4";
        String microserviceName5 = "microservice_5";
        String microserviceName6 = "microservice_6";

        String dbName1 = "pg_db_1";
        String dbName2 = "pg_db_2";
        String dbName3 = "pg_db_3";
        String dbName4 = "pg_db_4";
        String dbName5 = "pg_db_5";

        String postgresType = "postgresql";

        Database database1 = getDatabase(adapterId1, dbName1, false, false, null);
        Database database2 = getDatabase(adapterId1, dbName2, false, false, bgVersion);
        Database database3 = getDatabase(adapterId2, dbName3, false, false, null);
        Database database4 = getDatabase(adapterId3, dbName4, false, false, null);
        Database database5 = getDatabase(null, dbName5, true, false, null);

        DatabaseRegistry registry1 = getDatabaseRegistry(database1, namespace, microserviceName1, tenantId, postgresType);
        DatabaseRegistry registry2 = getDatabaseRegistry(database1, namespace, microserviceName2, tenantId, postgresType);
        DatabaseRegistry registry3 = getDatabaseRegistry(database2, namespace, microserviceName3, tenantId, postgresType);
        DatabaseRegistry registry4 = getDatabaseRegistry(database3, namespace, microserviceName4, tenantId, postgresType);
        DatabaseRegistry registry5 = getDatabaseRegistry(database4, namespace, microserviceName5, tenantId, postgresType);
        DatabaseRegistry registry6 = getDatabaseRegistry(database5, namespace, microserviceName6, tenantId, postgresType);

        BackupRequest backupRequest = getBackupRequest(
                backupName,
                List.of(namespace),
                ExternalDatabaseStrategy.SKIP,
                true
        );

        DbaasAdapter adapter1 = Mockito.mock(DbaasAdapter.class);
        DbaasAdapter adapter2 = Mockito.mock(DbaasAdapter.class);
        DbaasAdapter adapter3 = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapterId1)).thenReturn(adapter1);
        when(physicalDatabasesService.getAdapterById(adapterId2)).thenReturn(adapter2);
        when(physicalDatabasesService.getAdapterById(adapterId3)).thenReturn(adapter3);
        when(adapter1.type()).thenReturn(postgresType);
        when(adapter2.type()).thenReturn(postgresType);
        when(adapter3.type()).thenReturn(postgresType);
        when(adapter1.isBackupRestoreSupported()).thenReturn(true);
        when(adapter2.isBackupRestoreSupported()).thenReturn(true);
        when(adapter3.isBackupRestoreSupported()).thenReturn(false);

        // Response from adapters to start backup request
        LogicalBackupAdapterResponse adapterResponse1 = LogicalBackupAdapterResponse.builder()
                .status(IN_PROGRESS_STATUS)
                .backupId(backupId1)
                .storageName(STORAGE_NAME)
                .blobPath(BLOB_PATH)
                .databases(List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .databaseName(dbName1)
                                .status(IN_PROGRESS_STATUS)
                                .size(1)
                                .duration(1)
                                .path("path")
                                .build(),
                        LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .databaseName(dbName2)
                                .status(IN_PROGRESS_STATUS)
                                .size(1)
                                .duration(1)
                                .path("path")
                                .build()))
                .build();

        when(adapter1.backupV2(any())).thenReturn(adapterResponse1);

        LogicalBackupAdapterResponse adapterResponse2 = LogicalBackupAdapterResponse.builder()
                .status(IN_PROGRESS_STATUS)
                .backupId(backupId2)
                .storageName(STORAGE_NAME)
                .blobPath(BLOB_PATH)
                .databases(List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                        .databaseName(dbName3)
                        .status(IN_PROGRESS_STATUS)
                        .size(1)
                        .duration(1)
                        .path("path")
                        .build()))
                .build();

        when(adapter2.backupV2(any())).thenReturn(adapterResponse2);


        // Saving databases
        Stream.of(registry1, registry3, registry4, registry5, registry6)
                .forEach(databaseRegistryDbaasRepository::saveAnyTypeLogDb);

        // Sleep to wait saving of databases
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        BackupResponse response = dbBackupV2Service.backup(backupRequest, false);
        assertEquals(BackupStatus.IN_PROGRESS, response.getStatus());

        Backup backup = backupRepository.findById(backupName);
        assertNotNull(backup, String.format("backup with name '%s' not found", backupName));
        assertEquals(ExternalDatabaseStrategy.SKIP, backup.getExternalDatabaseStrategy());

        List<BackupExternalDatabase> externalDatabases = backup.getExternalDatabases();
        assertTrue(externalDatabases.isEmpty());
    }

    @Test
    void backup_backupFinishedStatusFailed() {
        String backupName = "backupName";
        String namespace = "test-namespace";
        String tenantId = "test-tenant";
        String bgVersion = "bgVersion";
        String errorMsg = "some failure happen";

        String adapterId1 = "pg_adapter_1";
        String adapterId2 = "pg_adapter_2";
        String adapterId3 = "pg_adapter_3";

        String backupId1 = "backupId1";
        String backupId2 = "backupId2";

        String microserviceName1 = "microservice_1";
        String microserviceName2 = "microservice_2";
        String microserviceName3 = "microservice_3";
        String microserviceName4 = "microservice_4";
        String microserviceName5 = "microservice_5";
        String microserviceName6 = "microservice_6";

        String dbName1 = "pg_db_1";
        String dbName2 = "pg_db_2";
        String dbName3 = "pg_db_3";
        String dbName4 = "pg_db_4";
        String dbName5 = "pg_db_5";

        String postgresType = "postgresql";

        Database database1 = getDatabase(adapterId1, dbName1, false, false, null);
        Database database2 = getDatabase(adapterId1, dbName2, false, false, bgVersion);
        Database database3 = getDatabase(adapterId2, dbName3, false, false, null);
        Database database4 = getDatabase(adapterId3, dbName4, false, false, null);
        Database database5 = getDatabase(null, dbName5, true, false, null);

        DatabaseRegistry registry1 = getDatabaseRegistry(database1, namespace, microserviceName1, tenantId, postgresType);
        DatabaseRegistry registry2 = getDatabaseRegistry(database1, namespace, microserviceName2, tenantId, postgresType);
        DatabaseRegistry registry3 = getDatabaseRegistry(database2, namespace, microserviceName3, tenantId, postgresType);
        DatabaseRegistry registry4 = getDatabaseRegistry(database3, namespace, microserviceName4, tenantId, postgresType);
        DatabaseRegistry registry5 = getDatabaseRegistry(database4, namespace, microserviceName5, tenantId, postgresType);
        DatabaseRegistry registry6 = getDatabaseRegistry(database5, namespace, microserviceName6, tenantId, postgresType);

        BackupRequest backupRequest = getBackupRequest(
                backupName,
                List.of(namespace),
                ExternalDatabaseStrategy.SKIP,
                true
        );

        DbaasAdapter adapter1 = Mockito.mock(DbaasAdapter.class);
        DbaasAdapter adapter2 = Mockito.mock(DbaasAdapter.class);
        DbaasAdapter adapter3 = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapterId1)).thenReturn(adapter1);
        when(physicalDatabasesService.getAdapterById(adapterId2)).thenReturn(adapter2);
        when(physicalDatabasesService.getAdapterById(adapterId3)).thenReturn(adapter3);
        when(adapter1.type()).thenReturn(postgresType);
        when(adapter2.type()).thenReturn(postgresType);
        when(adapter3.type()).thenReturn(postgresType);
        when(adapter1.isBackupRestoreSupported()).thenReturn(true);
        when(adapter2.isBackupRestoreSupported()).thenReturn(true);
        when(adapter3.isBackupRestoreSupported()).thenReturn(false);

        // Response from adapters to start backup request
        LogicalBackupAdapterResponse adapterResponse1 = LogicalBackupAdapterResponse.builder()
                .status(IN_PROGRESS_STATUS)
                .backupId(backupId1)
                .storageName(STORAGE_NAME)
                .blobPath(BLOB_PATH)
                .databases(List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .databaseName(dbName1)
                                .status(IN_PROGRESS_STATUS)
                                .size(1)
                                .duration(1)
                                .path("path")
                                .build(),
                        LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .databaseName(dbName2)
                                .status(IN_PROGRESS_STATUS)
                                .size(1)
                                .duration(1)
                                .path("path")
                                .build()))
                .build();

        when(adapter1.backupV2(any())).thenReturn(adapterResponse1);

        LogicalBackupAdapterResponse adapterResponse2 = LogicalBackupAdapterResponse.builder()
                .status(IN_PROGRESS_STATUS)
                .backupId(backupId2)
                .storageName(STORAGE_NAME)
                .blobPath(BLOB_PATH)
                .databases(List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                        .databaseName(dbName3)
                        .status(IN_PROGRESS_STATUS)
                        .size(1)
                        .duration(1)
                        .path("path")
                        .build()))
                .build();

        when(adapter2.backupV2(any())).thenReturn(adapterResponse2);
        // Response from adapters to track backup request
        LogicalBackupAdapterResponse adapterResponse3 = LogicalBackupAdapterResponse.builder()
                .status(FAILED_STATUS)
                .backupId(backupId1)
                .errorMessage(errorMsg)
                .storageName(STORAGE_NAME)
                .blobPath(BLOB_PATH)
                .databases(List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .databaseName(dbName1)
                                .status(FAILED_STATUS)
                                .size(1)
                                .duration(1)
                                .path("path")
                                .build(),
                        LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .databaseName(dbName2)
                                .status(FAILED_STATUS)
                                .size(1)
                                .duration(1)
                                .path("path")
                                .build()))
                .build();

        when(adapter1.trackBackupV2(backupId1, STORAGE_NAME, BLOB_PATH)).thenReturn(adapterResponse3);

        LogicalBackupAdapterResponse adapterResponse4 = LogicalBackupAdapterResponse.builder()
                .status(COMPLETED_STATUS)
                .backupId(backupId2)
                .storageName(STORAGE_NAME)
                .blobPath(BLOB_PATH)
                .databases(List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                        .databaseName(dbName3)
                        .status(COMPLETED_STATUS)
                        .size(1)
                        .duration(1)
                        .path("path")
                        .build()))
                .build();

        when(adapter2.trackBackupV2(backupId2, STORAGE_NAME, BLOB_PATH)).thenReturn(adapterResponse4);

        // Saving databases
        Stream.of(registry1, registry3, registry4, registry5, registry6)
                .forEach(databaseRegistryDbaasRepository::saveAnyTypeLogDb);

        // Sleep to wait saving of databases
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        BackupResponse response = dbBackupV2Service.backup(backupRequest, false);
        assertEquals(BackupStatus.IN_PROGRESS, response.getStatus());

        dbBackupV2Service.checkBackupsAsync();
        Backup backup = backupRepository.findById(backupName);
        assertNotNull(backup);
        assertEquals(BackupStatus.FAILED, backup.getStatus());

        LogicalBackup logicalBackup1 = backup.getLogicalBackups().stream()
                .filter(db -> backupId1.equals(db.getLogicalBackupName()))
                .findAny().orElse(null);
        assertNotNull(logicalBackup1);
        assertEquals(BackupTaskStatus.FAILED, logicalBackup1.getStatus());

        BackupDatabase backupDatabase1 = logicalBackup1.getBackupDatabases().stream()
                .filter(db -> dbName1.equals(db.getName()))
                .findAny().orElse(null);
        assertNotNull(backupDatabase1);
        assertEquals(BackupTaskStatus.FAILED, backupDatabase1.getStatus());

        BackupDatabase backupDatabase2 = logicalBackup1.getBackupDatabases().stream()
                .filter(db -> dbName2.equals(db.getName()))
                .findAny().orElse(null);
        assertNotNull(backupDatabase2);
        assertEquals(BackupTaskStatus.FAILED, backupDatabase2.getStatus());

        LogicalBackup logicalBackup2 = backup.getLogicalBackups().stream()
                .filter(db -> backupId2.equals(db.getLogicalBackupName()))
                .findAny().orElse(null);
        assertNotNull(logicalBackup2);
        assertEquals(BackupTaskStatus.COMPLETED, logicalBackup2.getStatus());

        BackupDatabase backupDatabase3 = logicalBackup2.getBackupDatabases().stream()
                .filter(db -> dbName3.equals(db.getName()))
                .findAny().orElse(null);
        assertNotNull(backupDatabase3);
        assertEquals(BackupTaskStatus.COMPLETED, backupDatabase3.getStatus());
    }

    @Test
    void backup_dryRunTrue() {
        String backupName = "backupName";
        String namespace = "test-namespace";
        String tenantId = "test-tenant";
        String adapterId = "pg_adapter_1";
        String microserviceName = "microservice_1";
        String dbName = "pg_db_1";
        String postgresType = "postgresql";

        Database database = getDatabase(adapterId, dbName, false, false, null);
        DatabaseRegistry registry1 = getDatabaseRegistry(database, namespace, microserviceName, tenantId, postgresType);

        BackupRequest backupRequest = getBackupRequest(
                backupName,
                List.of(namespace),
                ExternalDatabaseStrategy.SKIP,
                true
        );

        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapterId)).thenReturn(adapter);
        when(adapter.type()).thenReturn(postgresType);
        when(adapter.isBackupRestoreSupported()).thenReturn(true);

        // Saving databases
        Stream.of(registry1)
                .forEach(databaseRegistryDbaasRepository::saveAnyTypeLogDb);

        // Sleep to wait saving of databases
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        BackupResponse response = dbBackupV2Service.backup(backupRequest, true);
        assertNotNull(response);
        assertEquals(backupName, response.getBackupName());
        assertEquals(STORAGE_NAME, response.getStorageName());
        assertEquals(BLOB_PATH, response.getBlobPath());
        assertEquals(ExternalDatabaseStrategy.SKIP, response.getExternalDatabaseStrategy());
        assertEquals(backupRequest.getFilterCriteria(), response.getFilterCriteria());
        assertEquals(1, response.getTotal());
        assertEquals(0, response.getCompleted());

        LogicalBackupResponse logicalBackup = response.getLogicalBackups().getFirst();
        assertNotNull(logicalBackup);
        assertEquals(adapterId, logicalBackup.getAdapterId());
        assertEquals(postgresType, logicalBackup.getType());

        BackupDatabaseResponse backupDatabase = logicalBackup.getBackupDatabases().getFirst();
        assertNotNull(backupDatabase);
        assertEquals(dbName, backupDatabase.getName());
        SortedMap<String, Object> classifier = backupDatabase.getClassifiers().getFirst();
        assertTrue(
                microserviceName.equals(classifier.get(MICROSERVICE_NAME)) &&
                        namespace.equals(classifier.get(NAMESPACE)) &&
                        "tenant".equals(classifier.get(SCOPE)) &&
                        tenantId.equals(classifier.get(TENANT_ID)));
        BackupDatabaseResponse.User user = backupDatabase.getUsers().getFirst();
        assertEquals("oldUsername", user.getName());
        assertEquals("admin", user.getRole());

        Backup backup = backupRepository.findById(backupName);
        assertNull(backup);
    }

    @Test
    void restore_withoutMapping_finishedWithStatusCompleted() {
        String restoreName = "restoreName";
        String backupName = "backupName";
        String namespace = "test-namespace";
        String tenantId = "tenantId";
        String postgresqlType = "postgresql";
        String physicalDatabaseId = "postgres-dev";
        String dbName1 = "dbName1";
        String dbName2 = "dbName2";
        String newName1 = "new-name1";
        String newName2 = "new-name2";

        String microserviceName1 = "microserviceName1";
        String microserviceName2 = "microserviceName2";

        String logicalBackupName1 = "logicalBackupName1";
        String logicalBackupName2 = "logicalBackupName2";
        String logicalRestoreName1 = "logicalRestoreName1";
        String logicalRestoreName2 = "logicalRestoreName2";

        String adapterId1 = "adapterId1";
        String adapterId2 = "adapterId2";

        BackupDatabase backupDatabase1 = getBackupDatabase(dbName1, List.of(getClassifier(namespace, microserviceName1, tenantId)), false, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase2 = getBackupDatabase(dbName2, List.of(getClassifier(namespace, microserviceName2, tenantId)), false, BackupTaskStatus.COMPLETED, null);

        LogicalBackup logicalBackup1 = getLogicalBackup(logicalBackupName1, adapterId1, postgresqlType, List.of(backupDatabase1), BackupTaskStatus.COMPLETED, null);
        LogicalBackup logicalBackup2 = getLogicalBackup(logicalBackupName2, adapterId2, postgresqlType, List.of(backupDatabase2), BackupTaskStatus.COMPLETED, null);

        Backup backup = getBackup(backupName, ExternalDatabaseStrategy.FAIL, getFilterCriteriaEntity(List.of(namespace)), List.of(logicalBackup1, logicalBackup2), List.of(), BackupStatus.COMPLETED, null);
        backupRepository.save(backup);

        DbaasAdapter dbaasAdapter1 = Mockito.mock(DbaasAdapter.class);
        DbaasAdapter dbaasAdapter2 = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapterId1)).thenReturn(dbaasAdapter1);
        when(physicalDatabasesService.getAdapterById(adapterId2)).thenReturn(dbaasAdapter2);
        when(dbaasAdapter1.isBackupRestoreSupported()).thenReturn(true);
        when(dbaasAdapter2.isBackupRestoreSupported()).thenReturn(true);

        // Mock logic of choosing adapter in new/current env
        ExternalAdapterRegistrationEntry adapter1 = new ExternalAdapterRegistrationEntry();
        adapter1.setAdapterId(adapterId1);
        PhysicalDatabase physicalDatabase1 = new PhysicalDatabase();
        physicalDatabase1.setAdapter(adapter1);
        physicalDatabase1.setType(postgresqlType);
        physicalDatabase1.setPhysicalDatabaseIdentifier("postgres-dev");

        ExternalAdapterRegistrationEntry adapter2 = new ExternalAdapterRegistrationEntry();
        adapter2.setAdapterId(adapterId2);
        PhysicalDatabase physicalDatabase2 = new PhysicalDatabase();
        physicalDatabase2.setAdapter(adapter2);
        physicalDatabase2.setType(postgresqlType);
        physicalDatabase2.setPhysicalDatabaseIdentifier(physicalDatabaseId);

        when(balancingRulesService.applyBalancingRules(postgresqlType, namespace, microserviceName1))
                .thenReturn(physicalDatabase1);
        when(balancingRulesService.applyBalancingRules(postgresqlType, namespace, microserviceName2))
                .thenReturn(physicalDatabase2);
        when(physicalDatabasesService.getByAdapterId(adapterId1)).thenReturn(physicalDatabase1);
        when(physicalDatabasesService.getByAdapterId(adapterId2)).thenReturn(physicalDatabase2);

        // Response during the sync restore process
        LogicalRestoreAdapterResponse response1 = LogicalRestoreAdapterResponse.builder()
                .status(IN_PROGRESS_STATUS)
                .restoreId(logicalRestoreName1)
                .databases(List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                        .status(IN_PROGRESS_STATUS)
                        .previousDatabaseName(dbName1)
                        .databaseName(newName1)
                        .duration(1)
                        .build()))
                .build();
        LogicalRestoreAdapterResponse response2 = LogicalRestoreAdapterResponse.builder()
                .status(IN_PROGRESS_STATUS)
                .restoreId(logicalRestoreName2)
                .databases(List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                        .status(IN_PROGRESS_STATUS)
                        .previousDatabaseName(dbName2)
                        .databaseName(newName2)
                        .duration(1)
                        .build()))
                .build();

        // Same answer to DryRun non-DryRun mode
        when(dbaasAdapter1.restoreV2(eq(logicalBackupName1), anyBoolean(), any()))
                .thenReturn(response1)
                .thenReturn(response1);
        when(dbaasAdapter2.restoreV2(eq(logicalBackupName2), anyBoolean(), any()))
                .thenReturn(response2)
                .thenReturn(response2);

        // Response during the async restore process
        LogicalRestoreAdapterResponse response3 = LogicalRestoreAdapterResponse.builder()
                .status(COMPLETED_STATUS)
                .restoreId(logicalRestoreName1)
                .databases(List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                        .status(COMPLETED_STATUS)
                        .previousDatabaseName(dbName1)
                        .databaseName(newName1)
                        .duration(1)
                        .build()))
                .build();
        LogicalRestoreAdapterResponse response4 = LogicalRestoreAdapterResponse.builder()
                .status(COMPLETED_STATUS)
                .restoreId(logicalRestoreName2)
                .databases(List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                        .status(COMPLETED_STATUS)
                        .previousDatabaseName(dbName2)
                        .databaseName(newName2)
                        .duration(1)
                        .build()))
                .build();

        when(dbaasAdapter1.trackRestoreV2(eq(logicalRestoreName1), any(), any()))
                .thenReturn(response3);
        when(dbaasAdapter2.trackRestoreV2(eq(logicalRestoreName2), any(), any()))
                .thenReturn(response4);

        // Mocks to ensure user process
        DbResource resource1 = new DbResource();
        resource1.setId(UUID.randomUUID());
        resource1.setKind("kind");
        resource1.setName("name");
        EnsuredUser user1 = new EnsuredUser();
        user1.setConnectionProperties(Map.of(
                "key", "value"
        ));
        user1.setResources(List.of(resource1));

        DbResource resource2 = new DbResource();
        resource2.setId(UUID.randomUUID());
        resource2.setKind("kind");
        resource2.setName("name");
        EnsuredUser user2 = new EnsuredUser();
        user2.setConnectionProperties(Map.of());
        user2.setResources(List.of(resource2));

        when(dbaasAdapter1.ensureUser(null, null, newName1, "admin")).thenReturn(user1);
        when(dbaasAdapter2.ensureUser(null, null, newName2, "admin")).thenReturn(user2);

        RestoreResponse restoreResponse = dbBackupV2Service.restore(
                backupName,
                getRestoreRequest(restoreName, List.of(namespace), ExternalDatabaseStrategy.FAIL, null, null),
                false, false);

        assertNotNull(restoreResponse);
        assertEquals(restoreName, restoreResponse.getRestoreName());
        assertEquals(RestoreStatus.IN_PROGRESS, restoreResponse.getStatus());

        dbBackupV2Service.checkRestoresAsync();
        Restore restore = restoreRepository.findById(restoreName);
        assertNotNull(restore);
        assertEquals(STORAGE_NAME, restore.getStorageName());
        assertEquals(BLOB_PATH, restore.getBlobPath());
        assertNotNull(restore.getFilterCriteria());
        assertNotNull(restore.getMapping());
        assertEquals(ExternalDatabaseStrategy.FAIL, restore.getExternalDatabaseStrategy());
        assertEquals(RestoreStatus.COMPLETED, restore.getStatus());
        assertEquals(2, restore.getTotal());
        assertEquals(2, restore.getCompleted());
        assertTrue(restore.getErrorMessage().isBlank());
        assertEquals(1, restore.getAttemptCount());
        assertEquals(2, restore.getLogicalRestores().size());
        assertEquals(0, restore.getExternalDatabases().size());

        LogicalRestore logicalRestore1 = restore.getLogicalRestores().stream()
                .filter(db -> logicalRestoreName1.equals(db.getLogicalRestoreName()))
                .findAny().orElse(null);
        assertNotNull(logicalRestore1);
        assertNotNull(logicalRestore1.getId());
        assertEquals(adapterId1, logicalRestore1.getAdapterId());
        assertEquals(postgresqlType, logicalRestore1.getType());
        assertEquals(RestoreTaskStatus.COMPLETED, logicalRestore1.getStatus());
        assertEquals(1, logicalRestore1.getRestoreDatabases().size());

        RestoreDatabase restoreDatabase1 = logicalRestore1.getRestoreDatabases().stream()
                .filter(db -> newName1.equals(db.getName()))
                .findAny().orElse(null);
        assertNotNull(restoreDatabase1);
        assertNotNull(restoreDatabase1.getId());
        assertEquals(RestoreTaskStatus.COMPLETED, restoreDatabase1.getStatus());
        assertEquals(1, restoreDatabase1.getClassifiers().size());
        assertEquals(1, restoreDatabase1.getDuration());

        SortedMap<String, Object> classifier = restoreDatabase1.getClassifiers().getFirst().getClassifierBeforeMapper();
        assertTrue(
                namespace.equals(classifier.get(NAMESPACE)) &&
                        microserviceName1.equals(classifier.get(MICROSERVICE_NAME))
        );

        LogicalRestore logicalRestore2 = restore.getLogicalRestores().stream()
                .filter(db -> logicalRestoreName2.equals(db.getLogicalRestoreName()))
                .findAny().orElse(null);
        assertNotNull(logicalRestore2);
        assertNotNull(logicalRestore2.getId());
        assertEquals(adapterId2, logicalRestore2.getAdapterId());
        assertEquals(postgresqlType, logicalRestore2.getType());
        assertEquals(RestoreTaskStatus.COMPLETED, logicalRestore2.getStatus());
        assertEquals(1, logicalRestore2.getRestoreDatabases().size());

        RestoreDatabase restoreDatabase2 = logicalRestore2.getRestoreDatabases().stream()
                .filter(db -> newName2.equals(db.getName()))
                .findAny().orElse(null);
        assertNotNull(restoreDatabase2);
        assertNotNull(restoreDatabase2.getId());
        assertEquals(RestoreTaskStatus.COMPLETED, restoreDatabase2.getStatus());
        assertEquals(1, restoreDatabase2.getClassifiers().size());
        assertEquals(1, restoreDatabase2.getDuration());

        classifier = restoreDatabase2.getClassifiers().getFirst().getClassifierBeforeMapper();
        assertTrue(
                namespace.equals(classifier.get(NAMESPACE)) &&
                        microserviceName2.equals(classifier.get(MICROSERVICE_NAME))
        );

        List<DatabaseRegistry> databaseRegistries = databaseRegistryDbaasRepository
                .findAnyLogDbRegistryTypeByNamespace(namespace);
        assertFalse(databaseRegistries.isEmpty());
        assertEquals(2, databaseRegistries.size());


        DatabaseRegistry registry1 = databaseRegistries.stream()
                .filter(db -> adapterId1.equals(db.getAdapterId()))
                .findAny().orElse(null);
        assertNotNull(registry1);
        assertEquals(namespace, registry1.getNamespace());
        assertEquals(postgresqlType, registry1.getType());

        SortedMap<String, Object> registry1Classifier = registry1.getClassifier();
        assertTrue(
                namespace.equals(registry1Classifier.get(NAMESPACE)) &&
                        microserviceName1.equals(registry1Classifier.get(MICROSERVICE_NAME))
        );

        Database database1 = registry1.getDatabase();
        assertNotNull(database1);
        assertEquals(adapterId1, database1.getAdapterId());
        assertFalse(database1.isExternallyManageable());
        assertFalse(database1.isMarkedForDrop());
        assertEquals(newName1, database1.getName());
        assertEquals(physicalDatabaseId, database1.getPhysicalDatabaseId());

        DatabaseRegistry registry2 = databaseRegistries.stream()
                .filter(db -> adapterId2.equals(db.getAdapterId()))
                .findAny().orElse(null);
        assertNotNull(registry2);
        assertEquals(namespace, registry2.getNamespace());
        assertEquals(postgresqlType, registry2.getType());

        SortedMap<String, Object> registry2Classifier = registry2.getClassifier();
        assertTrue(
                namespace.equals(registry2Classifier.get(NAMESPACE)) &&
                        microserviceName2.equals(registry2Classifier.get(MICROSERVICE_NAME))
        );

        Database database2 = registry2.getDatabase();
        assertNotNull(database2);
        assertEquals(adapterId2, database2.getAdapterId());
        assertFalse(database2.isExternallyManageable());
        assertFalse(database2.isMarkedForDrop());
        assertEquals(newName2, database2.getName());
        assertEquals(physicalDatabaseId, database2.getPhysicalDatabaseId());
    }

    @Test
    void restore_withMapping_finishedWithStatusCompleted() {
        String restoreName = "restoreName";
        String backupName = "backupName";
        String postgresqlType = "postgresql";
        String physicalDatabaseId = "postgres-dev";

        String namespace = "test-namespace";
        String mappedNamespace = "mapped-namespace";

        String tenantId = "tenantId";
        String mappedTenantId = "mappedTenantId";

        String externalDbName = "externalDbName";
        String dbName1 = "dbName1";
        String dbName2 = "dbName2";
        String newName1 = "new-name1";
        String newName2 = "new-name2";

        String microserviceName1 = "microserviceName1";
        String microserviceName2 = "microserviceName2";
        String microserviceName3 = "microserviceName3";

        String logicalBackupName1 = "logicalBackupName1";
        String logicalBackupName2 = "logicalBackupName2";
        String logicalRestoreName1 = "logicalRestoreName1";
        String logicalRestoreName2 = "logicalRestoreName2";

        String adapterId1 = "adapterId1";
        String adapterId2 = "adapterId2";


        BackupDatabase backupDatabase1 = getBackupDatabase(dbName1, List.of(getClassifier(namespace, microserviceName1, tenantId)), false, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase2 = getBackupDatabase(dbName2, List.of(getClassifier(namespace, microserviceName2, tenantId)), false, BackupTaskStatus.COMPLETED, null);

        LogicalBackup logicalBackup1 = getLogicalBackup(logicalBackupName1, adapterId1, postgresqlType, List.of(backupDatabase1), BackupTaskStatus.COMPLETED, null);
        LogicalBackup logicalBackup2 = getLogicalBackup(logicalBackupName2, adapterId2, postgresqlType, List.of(backupDatabase2), BackupTaskStatus.COMPLETED, null);

        BackupExternalDatabase externalDatabase = getBackupExternalDatabase(externalDbName, postgresqlType, List.of(getClassifier(namespace, microserviceName3, tenantId)));
        Backup backup = getBackup(backupName, ExternalDatabaseStrategy.INCLUDE, getFilterCriteriaEntity(List.of(namespace)), List.of(logicalBackup1, logicalBackup2), List.of(externalDatabase), BackupStatus.COMPLETED, null);
        backupRepository.save(backup);

        DbaasAdapter dbaasAdapter1 = Mockito.mock(DbaasAdapter.class);
        DbaasAdapter dbaasAdapter2 = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapterId1)).thenReturn(dbaasAdapter1);
        when(physicalDatabasesService.getAdapterById(adapterId2)).thenReturn(dbaasAdapter2);
        when(dbaasAdapter1.isBackupRestoreSupported()).thenReturn(true);
        when(dbaasAdapter2.isBackupRestoreSupported()).thenReturn(true);

        // Mock logic of choosing adapter in new/current env
        ExternalAdapterRegistrationEntry adapter1 = new ExternalAdapterRegistrationEntry();
        adapter1.setAdapterId(adapterId1);
        PhysicalDatabase physicalDatabase1 = new PhysicalDatabase();
        physicalDatabase1.setAdapter(adapter1);
        physicalDatabase1.setType(postgresqlType);
        physicalDatabase1.setPhysicalDatabaseIdentifier("postgres-dev");

        ExternalAdapterRegistrationEntry adapter2 = new ExternalAdapterRegistrationEntry();
        adapter2.setAdapterId(adapterId2);
        PhysicalDatabase physicalDatabase2 = new PhysicalDatabase();
        physicalDatabase2.setAdapter(adapter2);
        physicalDatabase2.setType(postgresqlType);
        physicalDatabase2.setPhysicalDatabaseIdentifier(physicalDatabaseId);

        when(balancingRulesService.applyBalancingRules(postgresqlType, mappedNamespace, microserviceName1))
                .thenReturn(physicalDatabase1);
        when(balancingRulesService.applyBalancingRules(postgresqlType, mappedNamespace, microserviceName2))
                .thenReturn(physicalDatabase2);
        when(physicalDatabasesService.getByAdapterId(adapterId1)).thenReturn(physicalDatabase1);
        when(physicalDatabasesService.getByAdapterId(adapterId2)).thenReturn(physicalDatabase2);

        // Response during the sync restore process
        LogicalRestoreAdapterResponse response1 = LogicalRestoreAdapterResponse.builder()
                .status(IN_PROGRESS_STATUS)
                .restoreId(logicalRestoreName1)
                .databases(List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                        .status(IN_PROGRESS_STATUS)
                        .previousDatabaseName(dbName1)
                        .databaseName(newName1)
                        .duration(1)
                        .build()))
                .build();
        LogicalRestoreAdapterResponse response2 = LogicalRestoreAdapterResponse.builder()
                .status(IN_PROGRESS_STATUS)
                .restoreId(logicalRestoreName2)
                .databases(List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                        .status(IN_PROGRESS_STATUS)
                        .previousDatabaseName(dbName2)
                        .databaseName(newName2)
                        .duration(1)
                        .build()))
                .build();

        // Same answer to DryRun non-DryRun mode
        when(dbaasAdapter1.restoreV2(eq(logicalBackupName1), anyBoolean(), any()))
                .thenReturn(response1)
                .thenReturn(response1);
        when(dbaasAdapter2.restoreV2(eq(logicalBackupName2), anyBoolean(), any()))
                .thenReturn(response2)
                .thenReturn(response2);

        // Response during the async restore process
        LogicalRestoreAdapterResponse response3 = LogicalRestoreAdapterResponse.builder()
                .status(COMPLETED_STATUS)
                .restoreId(logicalRestoreName1)
                .databases(List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                        .status(COMPLETED_STATUS)
                        .previousDatabaseName(dbName1)
                        .databaseName(newName1)
                        .duration(1)
                        .build()))
                .build();
        LogicalRestoreAdapterResponse response4 = LogicalRestoreAdapterResponse.builder()
                .status(COMPLETED_STATUS)
                .restoreId(logicalRestoreName2)
                .databases(List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                        .status(COMPLETED_STATUS)
                        .previousDatabaseName(dbName2)
                        .databaseName(newName2)
                        .duration(1)
                        .build()))
                .build();

        when(dbaasAdapter1.trackRestoreV2(eq(logicalRestoreName1), any(), any()))
                .thenReturn(response3);
        when(dbaasAdapter2.trackRestoreV2(eq(logicalRestoreName2), any(), any()))
                .thenReturn(response4);

        // Mocks to ensure user process
        DbResource resource1 = new DbResource();
        resource1.setId(UUID.randomUUID());
        resource1.setKind("kind");
        resource1.setName("name");
        EnsuredUser user1 = new EnsuredUser();
        user1.setConnectionProperties(Map.of(
                "key", "value"
        ));
        user1.setResources(List.of(resource1));

        DbResource resource2 = new DbResource();
        resource2.setId(UUID.randomUUID());
        resource2.setKind("kind");
        resource2.setName("name");
        EnsuredUser user2 = new EnsuredUser();
        user2.setConnectionProperties(Map.of());
        user2.setResources(List.of(resource2));

        when(dbaasAdapter1.ensureUser(null, null, newName1, "admin")).thenReturn(user1);
        when(dbaasAdapter2.ensureUser(null, null, newName2, "admin")).thenReturn(user2);


        Map<String, String> namespaceMap = Map.of(namespace, mappedNamespace);
        Map<String, String> tenantMap = Map.of(tenantId, mappedTenantId);

        RestoreResponse restoreResponse = dbBackupV2Service.restore(
                backupName,
                getRestoreRequest(restoreName, List.of(namespace), ExternalDatabaseStrategy.INCLUDE, namespaceMap, tenantMap),
                false, false);

        assertNotNull(restoreResponse);
        assertEquals(restoreName, restoreResponse.getRestoreName());
        assertEquals(RestoreStatus.IN_PROGRESS, restoreResponse.getStatus());

        dbBackupV2Service.checkRestoresAsync();
        Restore restore = restoreRepository.findById(restoreName);
        assertNotNull(restore);
        assertEquals(STORAGE_NAME, restore.getStorageName());
        assertEquals(BLOB_PATH, restore.getBlobPath());
        assertNotNull(restore.getFilterCriteria());
        assertNotNull(restore.getMapping());
        assertEquals(ExternalDatabaseStrategy.INCLUDE, restore.getExternalDatabaseStrategy());
        assertEquals(RestoreStatus.COMPLETED, restore.getStatus());
        assertEquals(2, restore.getTotal());
        assertEquals(2, restore.getCompleted());
        assertTrue(restore.getErrorMessage().isBlank());
        assertEquals(1, restore.getAttemptCount());
        assertEquals(2, restore.getLogicalRestores().size());
        assertEquals(1, restore.getExternalDatabases().size());

        RestoreExternalDatabase restoreExternalDatabase = restore.getExternalDatabases().getFirst();
        assertEquals(externalDbName, restoreExternalDatabase.getName());
        assertEquals(postgresqlType, restoreExternalDatabase.getType());

        SortedMap<String, Object> externalClassifier = restoreExternalDatabase.getClassifiers().getFirst().getClassifier();
        assertTrue(
                mappedNamespace.equals(externalClassifier.get(NAMESPACE)) &&
                        microserviceName3.equals(externalClassifier.get(MICROSERVICE_NAME)) &&
                        mappedTenantId.equals(externalClassifier.get(TENANT_ID))
        );

        LogicalRestore logicalRestore1 = restore.getLogicalRestores().stream()
                .filter(db -> logicalRestoreName1.equals(db.getLogicalRestoreName()))
                .findAny().orElse(null);
        assertNotNull(logicalRestore1);
        assertNotNull(logicalRestore1.getId());
        assertEquals(adapterId1, logicalRestore1.getAdapterId());
        assertEquals(postgresqlType, logicalRestore1.getType());
        assertEquals(RestoreTaskStatus.COMPLETED, logicalRestore1.getStatus());
        assertEquals(1, logicalRestore1.getRestoreDatabases().size());

        RestoreDatabase restoreDatabase1 = logicalRestore1.getRestoreDatabases().stream()
                .filter(db -> newName1.equals(db.getName()))
                .findAny().orElse(null);
        assertNotNull(restoreDatabase1);
        assertNotNull(restoreDatabase1.getId());
        assertEquals(RestoreTaskStatus.COMPLETED, restoreDatabase1.getStatus());
        assertEquals(1, restoreDatabase1.getClassifiers().size());
        assertEquals(1, restoreDatabase1.getDuration());

        SortedMap<String, Object> classifier = restoreDatabase1.getClassifiers().getFirst().getClassifier();
        assertTrue(
                mappedNamespace.equals(classifier.get(NAMESPACE)) &&
                        microserviceName1.equals(classifier.get(MICROSERVICE_NAME)) &&
                        mappedTenantId.equals(classifier.get(TENANT_ID))
        );

        LogicalRestore logicalRestore2 = restore.getLogicalRestores().stream()
                .filter(db -> logicalRestoreName2.equals(db.getLogicalRestoreName()))
                .findAny().orElse(null);
        assertNotNull(logicalRestore2);
        assertNotNull(logicalRestore2.getId());
        assertEquals(adapterId2, logicalRestore2.getAdapterId());
        assertEquals(postgresqlType, logicalRestore2.getType());
        assertEquals(RestoreTaskStatus.COMPLETED, logicalRestore2.getStatus());
        assertEquals(1, logicalRestore2.getRestoreDatabases().size());

        RestoreDatabase restoreDatabase2 = logicalRestore2.getRestoreDatabases().stream()
                .filter(db -> newName2.equals(db.getName()))
                .findAny().orElse(null);
        assertNotNull(restoreDatabase2);
        assertNotNull(restoreDatabase2.getId());
        assertEquals(RestoreTaskStatus.COMPLETED, restoreDatabase2.getStatus());
        assertEquals(1, restoreDatabase2.getClassifiers().size());
        assertEquals(1, restoreDatabase2.getDuration());

        classifier = restoreDatabase2.getClassifiers().getFirst().getClassifier();
        assertTrue(
                mappedNamespace.equals(classifier.get(NAMESPACE)) &&
                        microserviceName2.equals(classifier.get(MICROSERVICE_NAME)) &&
                        mappedTenantId.equals(classifier.get(TENANT_ID))
        );

        List<DatabaseRegistry> databaseRegistries = databaseRegistryDbaasRepository
                .findAnyLogDbRegistryTypeByNamespace(mappedNamespace);
        assertFalse(databaseRegistries.isEmpty());
        assertEquals(3, databaseRegistries.size());


        DatabaseRegistry registry1 = databaseRegistries.stream()
                .filter(db -> adapterId1.equals(db.getAdapterId()))
                .findAny().orElse(null);
        assertNotNull(registry1);
        assertEquals(mappedNamespace, registry1.getNamespace());
        assertEquals(postgresqlType, registry1.getType());

        SortedMap<String, Object> registry1Classifier = registry1.getClassifier();
        assertTrue(
                mappedNamespace.equals(registry1Classifier.get(NAMESPACE)) &&
                        microserviceName1.equals(registry1Classifier.get(MICROSERVICE_NAME)) &&
                        mappedTenantId.equals(registry1Classifier.get(TENANT_ID))
        );

        Database database1 = registry1.getDatabase();
        assertNotNull(database1);
        assertEquals(adapterId1, database1.getAdapterId());
        assertFalse(database1.isExternallyManageable());
        assertFalse(database1.isMarkedForDrop());
        assertEquals(newName1, database1.getName());
        assertEquals(physicalDatabaseId, database1.getPhysicalDatabaseId());

        DatabaseRegistry registry2 = databaseRegistries.stream()
                .filter(db -> adapterId2.equals(db.getAdapterId()))
                .findAny().orElse(null);
        assertNotNull(registry2);
        assertEquals(mappedNamespace, registry2.getNamespace());
        assertEquals(postgresqlType, registry2.getType());

        SortedMap<String, Object> registry2Classifier = registry2.getClassifier();
        assertTrue(
                mappedNamespace.equals(registry2Classifier.get(NAMESPACE)) &&
                        microserviceName2.equals(registry2Classifier.get(MICROSERVICE_NAME)) &&
                        mappedTenantId.equals(registry2Classifier.get(TENANT_ID))
        );

        Database database2 = registry2.getDatabase();
        assertNotNull(database2);
        assertEquals(adapterId2, database2.getAdapterId());
        assertFalse(database2.isExternallyManageable());
        assertFalse(database2.isMarkedForDrop());
        assertEquals(newName2, database2.getName());
        assertEquals(physicalDatabaseId, database2.getPhysicalDatabaseId());

        // External DB
        DatabaseRegistry registry3 = databaseRegistries.stream()
                .filter(db -> externalDbName.equals(db.getName()))
                .findAny().orElse(null);
        assertNotNull(registry3);
        assertEquals(mappedNamespace, registry3.getNamespace());
        assertEquals(postgresqlType, registry3.getType());

        Database database3 = registry3.getDatabase();
        assertNotNull(database3);
        assertNull(database3.getAdapterId());
        assertTrue(database3.isExternallyManageable());
        assertFalse(database3.isMarkedForDrop());
        assertEquals(externalDbName, database3.getName());
    }

    @Test
    void restore_mappingInvokeCollision() {
        String namespace = "namespace";
        String mappedNamespace = "mappedNamespace";

        String backupName = "backupName";
        String restoreName = "restoreName";

        String logicalBackupName = "logicalBackupName";
        String adapterId = "adapterId";

        String dbName = "dbName";

        String microserviceName = "microserviceName";
        String tenantId = "tenantId";
        String anotherTenantId = "anotherTenantId";
        String postgresType = "postgresType";

        Mapping mapping = new Mapping();
        mapping.setNamespaces(Map.of(namespace, mappedNamespace));
        mapping.setTenants(Map.of(tenantId, anotherTenantId));

        SortedMap<String, Object> classifier1 = getClassifier(namespace, microserviceName, tenantId);
        SortedMap<String, Object> classifier2 = getClassifier(namespace, microserviceName, anotherTenantId);
        SortedMap<String, Object> mappedClassifier = getClassifier(mappedNamespace, microserviceName, anotherTenantId);


        BackupDatabase backupDatabase = getBackupDatabase(dbName, List.of(classifier1, classifier2), false, BackupTaskStatus.COMPLETED, "");
        LogicalBackup logicalBackup = getLogicalBackup(logicalBackupName, adapterId, postgresType, List.of(backupDatabase), BackupTaskStatus.COMPLETED, "");
        Backup backup = getBackup(backupName, ExternalDatabaseStrategy.FAIL, getFilterCriteriaEntity(List.of(namespace)), List.of(logicalBackup), List.of(), BackupStatus.COMPLETED, "");
        backupRepository.save(backup);

        DbaasAdapter dbaasAdapter = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapterId)).thenReturn(dbaasAdapter);
        when(dbaasAdapter.isBackupRestoreSupported()).thenReturn(true);

        // Mock logic of choosing adapter in new/current env
        ExternalAdapterRegistrationEntry adapter1 = new ExternalAdapterRegistrationEntry();
        adapter1.setAdapterId(adapterId);
        PhysicalDatabase physicalDatabase1 = new PhysicalDatabase();
        physicalDatabase1.setAdapter(adapter1);
        physicalDatabase1.setType(postgresType);
        physicalDatabase1.setPhysicalDatabaseIdentifier("postgres-dev");

        when(balancingRulesService.applyBalancingRules(postgresType, mappedNamespace, microserviceName))
                .thenReturn(physicalDatabase1);
        when(physicalDatabasesService.getByAdapterId(adapterId)).thenReturn(physicalDatabase1);

        IllegalResourceStateException ex = assertThrows(IllegalResourceStateException.class, () -> dbBackupV2Service.restore(backupName,
                getRestoreRequest(restoreName, List.of(namespace), ExternalDatabaseStrategy.FAIL, mapping.getNamespaces(), mapping.getTenants()),
                false, false
        ));

        String msg = mappedClassifier.toString();
        assertTrue(ex.getDetail().contains(msg));
    }

    @Test
    void restore_similarRegistryInAnotherNamespace_shouldCreateNewDb() {
        String restoreName = "restoreName";
        String backupName = "backupName";
        String logicalBackupName = "logicalBackupName";
        String logicalRestoreName = "logicalRestoreName";

        String dbName = "dbName";
        String newName = "newName";
        String adapterId = "adapterId";

        String externalDbName = "externalDbName";

        String namespace = "namespace";
        String mappedNamespace = "mappedNamespace";
        String anotherNamespace = "anotherNamespace";

        String microserviceName1 = "microserviceName1";
        String microserviceName2 = "microserviceName2";

        String tenantId = "tenantId";
        String anotherTenantId = "tenantId";
        String mappedTenantId = "mappedTenantId";
        String postgresType = "postgresql";

        // Database with registries that exists in another namespace
        Database database = getDatabase(adapterId, newName, false, false, null);
        Database externalDb = getDatabase(null, externalDbName, true, false, null);
        DatabaseRegistry registry1 = getDatabaseRegistry(database, mappedNamespace, microserviceName1, mappedTenantId, postgresType);
        DatabaseRegistry registry2 = getDatabaseRegistry(database, anotherNamespace, microserviceName1, anotherTenantId, postgresType);
        DatabaseRegistry externalRegistry = getDatabaseRegistry(externalDb, mappedNamespace, microserviceName2, mappedTenantId, postgresType);

        databaseRegistryDbaasRepository.saveAnyTypeLogDb(registry1);
        databaseRegistryDbaasRepository.saveExternalDatabase(externalRegistry);

        BackupExternalDatabase externalDatabase = getBackupExternalDatabase(externalDbName, postgresType, List.of(getClassifier(namespace, microserviceName2, tenantId)));
        BackupDatabase backupDatabase = getBackupDatabase(dbName, List.of(getClassifier(namespace, microserviceName1, tenantId)), false, BackupTaskStatus.COMPLETED, null);
        LogicalBackup logicalBackup = getLogicalBackup(logicalBackupName, adapterId, postgresType, List.of(backupDatabase), BackupTaskStatus.COMPLETED, null);
        Backup backup = getBackup(backupName, ExternalDatabaseStrategy.INCLUDE, getFilterCriteriaEntity(List.of(namespace)), List.of(logicalBackup), List.of(externalDatabase), BackupStatus.COMPLETED, null);
        backupRepository.save(backup);

        DbaasAdapter dbaasAdapter = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapterId)).thenReturn(dbaasAdapter);
        when(dbaasAdapter.isBackupRestoreSupported()).thenReturn(true);

        // Mock logic of choosing adapter in new/current env
        ExternalAdapterRegistrationEntry adapter1 = new ExternalAdapterRegistrationEntry();
        adapter1.setAdapterId(adapterId);
        PhysicalDatabase physicalDatabase1 = new PhysicalDatabase();
        physicalDatabase1.setAdapter(adapter1);
        physicalDatabase1.setType(postgresType);
        physicalDatabase1.setPhysicalDatabaseIdentifier("postgres-dev");

        when(balancingRulesService.applyBalancingRules(eq(postgresType), anyString(), anyString()))
                .thenReturn(physicalDatabase1);
        when(physicalDatabasesService.getByAdapterId(adapterId)).thenReturn(physicalDatabase1);

        // Response during the sync restore process
        LogicalRestoreAdapterResponse response = LogicalRestoreAdapterResponse.builder()
                .status(IN_PROGRESS_STATUS)
                .restoreId(logicalRestoreName)
                .databases(List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                        .status(IN_PROGRESS_STATUS)
                        .previousDatabaseName(dbName)
                        .databaseName(newName)
                        .duration(1)
                        .build()))
                .build();

        // Same answer to DryRun non-DryRun mode
        when(dbaasAdapter.restoreV2(eq(logicalBackupName), anyBoolean(), any()))
                .thenReturn(response)
                .thenReturn(response);

        // Response during the async restore process
        LogicalRestoreAdapterResponse response2 = LogicalRestoreAdapterResponse.builder()
                .status(COMPLETED_STATUS)
                .restoreId(logicalRestoreName)
                .databases(List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                        .status(COMPLETED_STATUS)
                        .previousDatabaseName(dbName)
                        .databaseName(newName)
                        .duration(1)
                        .build()))
                .build();

        when(dbaasAdapter.trackRestoreV2(eq(logicalRestoreName), any(), any()))
                .thenReturn(response2);

        // Mocks to ensure user process
        DbResource resource1 = new DbResource();
        resource1.setId(UUID.randomUUID());
        resource1.setKind("kind");
        resource1.setName("newName");
        EnsuredUser user1 = new EnsuredUser();
        user1.setConnectionProperties(Map.of(
                "username", "newName"
        ));
        user1.setResources(List.of(resource1));
        user1.setName("newName");

        when(dbaasAdapter.ensureUser(null, null, newName, "admin")).thenReturn(user1);

        Map<String, String> namespaceMap = Map.of(namespace, mappedNamespace);
        Map<String, String> tenantMap = Map.of(tenantId, mappedTenantId);

        dbBackupV2Service.restore(backupName,
                getRestoreRequest(restoreName, List.of(namespace), ExternalDatabaseStrategy.INCLUDE, namespaceMap, tenantMap),
                false, false
        );
        dbBackupV2Service.checkRestoresAsync();

        Restore restore = restoreRepository.findById(restoreName);
        assertEquals("newName", restore.getLogicalRestores().getFirst().getRestoreDatabases().getFirst().getUsers().getFirst().getName());
        List<DatabaseRegistry> databaseRegistries = databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(mappedNamespace);
        assertEquals(4, databaseRegistries.size());

        // New logicalDb with new classifier
        DatabaseRegistry databaseRegistry1 = databaseRegistries.stream()
                .filter(db -> newName.equals(db.getName()))
                .filter(db -> !db.getClassifier().containsKey(MARKED_FOR_DROP))
                .findAny().orElse(null);
        assertNotNull(databaseRegistry1);
        assertEquals(postgresType, databaseRegistry1.getType());
        assertTrue(
                mappedNamespace.equals(databaseRegistry1.getClassifier().get(NAMESPACE)) &&
                        microserviceName1.equals(databaseRegistry1.getClassifier().get(MICROSERVICE_NAME)) &&
                        mappedTenantId.equals(databaseRegistry1.getClassifier().get(TENANT_ID)) &&
                        !databaseRegistry1.getClassifier().containsKey(MARKED_FOR_DROP)
        );

        Database database1 = databaseRegistry1.getDatabase();
        assertNotNull(database1);
        assertEquals(adapterId, database1.getAdapterId());
        assertFalse(database1.isExternallyManageable());
        assertFalse(database1.isMarkedForDrop());
        assertEquals(newName, database1.getName());
        assertEquals("newName", database1.getConnectionProperties().getFirst().get("username"));

        // Registry that was copied from DB that was MARKED_FOR_DROP
        DatabaseRegistry databaseRegistry2 = database1.getDatabaseRegistry().stream()
                .filter(db -> anotherNamespace.equals(db.getNamespace()))
                .findAny().orElse(null);
        assertNotNull(databaseRegistry2);
        assertEquals(anotherNamespace, databaseRegistry2.getNamespace());
        assertEquals(postgresType, databaseRegistry2.getType());
        assertTrue(
                anotherNamespace.equals(databaseRegistry2.getClassifier().get(NAMESPACE)) &&
                        microserviceName1.equals(databaseRegistry2.getClassifier().get(MICROSERVICE_NAME)) &&
                        anotherTenantId.equals(databaseRegistry2.getClassifier().get(TENANT_ID)) &&
                        !databaseRegistry2.getClassifier().containsKey(MARKED_FOR_DROP)
        );

        // External registry
        DatabaseRegistry databaseRegistry3 = databaseRegistries.stream()
                .filter(db -> externalDbName.equals(db.getName()))
                .filter(db -> !db.getClassifier().containsKey(MARKED_FOR_DROP))
                .findAny().orElse(null);
        assertNotNull(databaseRegistry3);
        assertEquals(postgresType, databaseRegistry3.getType());
        assertTrue(
                mappedNamespace.equals(databaseRegistry3.getClassifier().get(NAMESPACE)) &&
                        microserviceName2.equals(databaseRegistry3.getClassifier().get(MICROSERVICE_NAME)) &&
                        mappedTenantId.equals(databaseRegistry3.getClassifier().get(TENANT_ID)) &&
                        !databaseRegistry3.getClassifier().containsKey(MARKED_FOR_DROP)
        );

        Database database2 = databaseRegistry3.getDatabase();
        assertNotNull(database2);
        assertNull(database2.getAdapterId());
        assertTrue(database2.isExternallyManageable());
        assertFalse(database2.isMarkedForDrop());
        assertEquals(externalDbName, database2.getName());

        // Registry that MARKED_FOR_DROP
        DatabaseRegistry databaseRegistry4 = databaseRegistries.stream()
                .filter(db -> mappedNamespace.equals(db.getNamespace()))
                .findAny().orElse(null);
        assertNotNull(databaseRegistry4);
        assertEquals(postgresType, databaseRegistry4.getType());

        Database database3 = databaseRegistry4.getDatabase();
        assertNotNull(database3);
        assertEquals(adapterId, database3.getAdapterId());
        assertFalse(database3.isExternallyManageable());
        assertTrue(database3.isMarkedForDrop());
        assertEquals(newName, database3.getName());
        assertEquals("oldUsername", database3.getConnectionProperties().getFirst().get("username"));

        DatabaseRegistry databaseRegistry5 = database3.getDatabaseRegistry().stream()
                .filter(db -> anotherNamespace.equals(db.getNamespace()))
                .findAny().orElse(null);
        assertNotNull(databaseRegistry5);
        assertEquals(anotherNamespace, databaseRegistry5.getNamespace());
        assertEquals(postgresType, databaseRegistry5.getType());
        assertTrue(
                anotherNamespace.equals(databaseRegistry5.getClassifier().get(NAMESPACE)) &&
                        microserviceName1.equals(databaseRegistry5.getClassifier().get(MICROSERVICE_NAME)) &&
                        anotherTenantId.equals(databaseRegistry5.getClassifier().get(TENANT_ID)) &&
                        databaseRegistry5.getClassifier().containsKey(MARKED_FOR_DROP)
        );
    }

    @Test
    void restore_dryRun() {
        String restoreName = "restoreName";
        String backupName = "backupName";
        String logicalBackupName = "logicalBackupName";
        String logicalRestoreName = "logicalRestoreName";

        String dbName = "dbName";
        String newName = "newName";
        String adapterId = "adapterId";

        String externalDbName = "externalDbName";

        String namespace = "namespace";
        String mappedNamespace = "mappedNamespace";
        String anotherNamespace = "anotherNamespace";

        String microserviceName1 = "microserviceName1";
        String microserviceName2 = "microserviceName2";

        String tenantId = "tenantId";
        String anotherTenantId = "tenantId";
        String mappedTenantId = "mappedTenantId";
        String postgresType = "postgresql";

        // Database with registries that exists in another namespace
        Database database = getDatabase(adapterId, newName, false, false, null);
        Database externalDb = getDatabase(null, externalDbName, true, false, null);
        DatabaseRegistry registry1 = getDatabaseRegistry(database, mappedNamespace, microserviceName1, mappedTenantId, postgresType);
        DatabaseRegistry registry2 = getDatabaseRegistry(database, anotherNamespace, microserviceName1, anotherTenantId, postgresType);
        DatabaseRegistry externalRegistry = getDatabaseRegistry(externalDb, mappedNamespace, microserviceName2, mappedTenantId, postgresType);

        databaseRegistryDbaasRepository.saveAnyTypeLogDb(registry1);
        databaseRegistryDbaasRepository.saveExternalDatabase(externalRegistry);

        BackupExternalDatabase externalDatabase = getBackupExternalDatabase(externalDbName, postgresType, List.of(getClassifier(namespace, microserviceName2, tenantId)));
        BackupDatabase backupDatabase = getBackupDatabase(dbName, List.of(getClassifier(namespace, microserviceName1, tenantId)), false, BackupTaskStatus.COMPLETED, null);
        LogicalBackup logicalBackup = getLogicalBackup(logicalBackupName, adapterId, postgresType, List.of(backupDatabase), BackupTaskStatus.COMPLETED, null);
        Backup backup = getBackup(backupName, ExternalDatabaseStrategy.INCLUDE, getFilterCriteriaEntity(List.of(namespace)), List.of(logicalBackup), List.of(externalDatabase), BackupStatus.COMPLETED, null);
        backupRepository.save(backup);

        DbaasAdapter dbaasAdapter = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapterId)).thenReturn(dbaasAdapter);
        when(dbaasAdapter.isBackupRestoreSupported()).thenReturn(true);

        // Mock logic of choosing adapter in new/current env
        ExternalAdapterRegistrationEntry adapter1 = new ExternalAdapterRegistrationEntry();
        adapter1.setAdapterId(adapterId);
        PhysicalDatabase physicalDatabase1 = new PhysicalDatabase();
        physicalDatabase1.setAdapter(adapter1);
        physicalDatabase1.setType(postgresType);
        physicalDatabase1.setPhysicalDatabaseIdentifier("postgres-dev");

        when(balancingRulesService.applyBalancingRules(eq(postgresType), anyString(), anyString()))
                .thenReturn(physicalDatabase1);
        when(physicalDatabasesService.getByAdapterId(adapterId)).thenReturn(physicalDatabase1);

        // Response during the sync restore process
        LogicalRestoreAdapterResponse response = LogicalRestoreAdapterResponse.builder()
                .status(IN_PROGRESS_STATUS)
                .restoreId(logicalRestoreName)
                .databases(List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                        .status(IN_PROGRESS_STATUS)
                        .previousDatabaseName(dbName)
                        .databaseName(newName)
                        .duration(1)
                        .build()))
                .build();

        // Answer to DryRun
        when(dbaasAdapter.restoreV2(eq(logicalBackupName), anyBoolean(), any()))
                .thenReturn(response);

        Map<String, String> namespaceMap = Map.of(namespace, mappedNamespace);
        Map<String, String> tenantMap = Map.of(tenantId, mappedTenantId);

        RestoreResponse restoreResponse = dbBackupV2Service.restore(backupName,
                getRestoreRequest(restoreName, List.of(namespace), ExternalDatabaseStrategy.INCLUDE, namespaceMap, tenantMap),
                true, false
        );
        assertNull(restoreRepository.findById(restoreName));
        assertNotNull(restoreResponse);
        assertEquals(restoreName, restoreResponse.getRestoreName());
        assertEquals(backupName, restoreResponse.getBackupName());
        assertEquals(1, restoreResponse.getLogicalRestores().size());

        LogicalRestoreResponse logicalRestore = restoreResponse.getLogicalRestores().getFirst();
        assertEquals(logicalRestoreName, logicalRestore.getLogicalRestoreName());
        assertEquals(adapterId, logicalRestore.getAdapterId());
        assertEquals(postgresType, logicalRestore.getType());
        assertEquals(1, logicalRestore.getRestoreDatabases().size());

        RestoreDatabaseResponse restoreDatabase = logicalRestore.getRestoreDatabases().getFirst();
        assertEquals(newName, restoreDatabase.getName());
        assertEquals(1, restoreDatabase.getUsers().size());
        assertEquals(1, restoreDatabase.getSettings().size());
        assertEquals(2, restoreDatabase.getClassifiers().size());

        ClassifierDetailsResponse classifier1 = restoreDatabase.getClassifiers().stream()
                .filter(classifier -> ClassifierType.REPLACED == classifier.getType())
                .findAny().orElse(null);
        assertNotNull(classifier1);
        assertNotNull(classifier1.getClassifier());
        assertEquals(database.getName(), classifier1.getPreviousDatabase());
        assertTrue(
                mappedNamespace.equals(classifier1.getClassifier().get(NAMESPACE)) &&
                        microserviceName1.equals(classifier1.getClassifier().get(MICROSERVICE_NAME)) &&
                        "tenant".equals(classifier1.getClassifier().get("scope")) &&
                        mappedTenantId.equals(classifier1.getClassifier().get(TENANT_ID))
        );
        assertNotNull(classifier1.getClassifierBeforeMapper());
        assertTrue(
                namespace.equals(classifier1.getClassifierBeforeMapper().get(NAMESPACE)) &&
                        microserviceName1.equals(classifier1.getClassifierBeforeMapper().get(MICROSERVICE_NAME)) &&
                        "tenant".equals(classifier1.getClassifierBeforeMapper().get("scope")) &&
                        tenantId.equals(classifier1.getClassifierBeforeMapper().get(TENANT_ID))
        );

        ClassifierDetailsResponse classifier2 = restoreDatabase.getClassifiers().stream()
                .filter(classifier -> ClassifierType.TRANSIENT_REPLACED == classifier.getType())
                .findAny().orElse(null);
        assertNotNull(classifier2);
        assertNotNull(classifier2.getClassifier());
        assertEquals(database.getName(), classifier2.getPreviousDatabase());
        assertTrue(
                anotherNamespace.equals(classifier2.getClassifier().get(NAMESPACE)) &&
                        microserviceName1.equals(classifier2.getClassifier().get(MICROSERVICE_NAME)) &&
                        "tenant".equals(classifier2.getClassifier().get("scope")) &&
                        tenantId.equals(classifier2.getClassifier().get(TENANT_ID))
        );
        assertNull(classifier2.getClassifierBeforeMapper());
        assertEquals(1, restoreResponse.getExternalDatabases().size());

        RestoreExternalDatabaseResponse externalDatabaseResponse = restoreResponse.getExternalDatabases().getFirst();
        assertEquals(externalDbName, externalDatabaseResponse.getName());
        assertEquals(postgresType, externalDatabaseResponse.getType());
        assertEquals(1, externalDatabaseResponse.getClassifiers().size());

        ClassifierDetailsResponse classifier3 = externalDatabaseResponse.getClassifiers().getFirst();
        assertNotNull(classifier3);
        assertEquals(ClassifierType.REPLACED, classifier3.getType());
        assertEquals(externalDb.getName(), classifier3.getPreviousDatabase());
        assertNotNull(classifier3.getClassifier());
        assertTrue(
                mappedNamespace.equals(classifier3.getClassifier().get(NAMESPACE)) &&
                        microserviceName2.equals(classifier3.getClassifier().get(MICROSERVICE_NAME)) &&
                        "tenant".equals(classifier3.getClassifier().get("scope")) &&
                        mappedTenantId.equals(classifier3.getClassifier().get(TENANT_ID))
        );
        assertNotNull(classifier3.getClassifierBeforeMapper());
        assertTrue(
                namespace.equals(classifier3.getClassifierBeforeMapper().get(NAMESPACE)) &&
                        microserviceName2.equals(classifier3.getClassifierBeforeMapper().get(MICROSERVICE_NAME)) &&
                        "tenant".equals(classifier3.getClassifierBeforeMapper().get("scope")) &&
                        tenantId.equals(classifier3.getClassifierBeforeMapper().get(TENANT_ID))
        );
    }

    @Test
    void retryRestore() {
        // 1 ExternalDatabase 2 RestoreDatabase (1 FAILED, 1 COMPLETED), 1 logicalRestore (FAILED), 1 restore (FAILED)
        // 1 mapping { namespace : mappedNamespace }
        // Assert restore COMPLETED, 3 db created
        String restoreName = "restoreName";
        String backupName = "backupName";
        String logicalBackupName = "logicalBackupName";
        String logicalRestoreName = "logicalRestoreName";

        String dbName = "dbName";
        String dbName2 = "dbName2";

        String newName = "newName";
        String newName2 = "newName2";
        String externalName = "externalName";
        String adapterId = "adapterId";


        String namespace = "namespace";
        String mappedNamespace = "mappedNamespace";
        String anotherNamespace = "anotherNamespace";

        String microserviceName1 = "microserviceName1";
        String microserviceName2 = "microserviceName2";
        String microserviceName3 = "microserviceName3";

        String tenantId = "tenantId";
        String postgresType = "postgresql";

        BackupExternalDatabase externalDatabase = getBackupExternalDatabase(externalName, postgresType, List.of(getClassifier(namespace, microserviceName3, tenantId)));
        BackupDatabase backupDatabase1 = getBackupDatabase(dbName, List.of(getClassifier(namespace, microserviceName1, tenantId)), false, BackupTaskStatus.COMPLETED, "Internal Server Error");
        BackupDatabase backupDatabase2 = getBackupDatabase(dbName2, List.of(getClassifier(anotherNamespace, microserviceName2, null)), false, BackupTaskStatus.COMPLETED, null);
        LogicalBackup logicalBackup = getLogicalBackup(logicalBackupName, adapterId, postgresType, List.of(backupDatabase1, backupDatabase2), BackupTaskStatus.COMPLETED, "Internal Server Error");
        Backup backup = getBackup(backupName, ExternalDatabaseStrategy.INCLUDE, getFilterCriteriaEntity(List.of(namespace)), List.of(logicalBackup), List.of(externalDatabase), BackupStatus.COMPLETED, "Internal Server Error");
        backupRepository.save(backup);

        ClassifierDetails classifierWrapper1 = getClassifier(ClassifierType.NEW, mappedNamespace, microserviceName1, null, namespace, null);
        ClassifierDetails classifierWrapper2 = getClassifier(ClassifierType.NEW, namespace, microserviceName2, null, namespace, null);
        ClassifierDetails classifierWrapper3 = getClassifier(ClassifierType.NEW, namespace, microserviceName3, null, namespace, null);
        RestoreExternalDatabase restoreExternalDatabase = getRestoreExternalDb(externalName, postgresType, List.of(classifierWrapper3));
        RestoreDatabase restoreDatabase1 = getRestoreDatabase(backupDatabase1, newName, List.of(classifierWrapper1), Map.of(), null, RestoreTaskStatus.FAILED, 1, "Internal Server Error");
        RestoreDatabase restoreDatabase2 = getRestoreDatabase(backupDatabase2, newName2, List.of(classifierWrapper2), Map.of(), null, RestoreTaskStatus.COMPLETED, 1, null);
        LogicalRestore logicalRestore = getLogicalRestore(logicalRestoreName, adapterId, postgresType, List.of(restoreDatabase1, restoreDatabase2), RestoreTaskStatus.FAILED, "Internal Server Error");
        Restore restore = getRestore(backup, restoreName, getFilterCriteriaEntity(List.of(namespace)), null, List.of(logicalRestore), ExternalDatabaseStrategy.INCLUDE, List.of(restoreExternalDatabase), RestoreStatus.FAILED, "Internal Server Error");

        restoreRepository.save(restore);

        DbaasAdapter dbaasAdapter = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapterId)).thenReturn(dbaasAdapter);
//        when(dbaasAdapter.isBackupRestoreSupported()).thenReturn(true);

        // Mock logic of choosing adapter in new/current env
        ExternalAdapterRegistrationEntry adapter1 = new ExternalAdapterRegistrationEntry();
        adapter1.setAdapterId(adapterId);
        PhysicalDatabase physicalDatabase1 = new PhysicalDatabase();
        physicalDatabase1.setAdapter(adapter1);
        physicalDatabase1.setType(postgresType);
        physicalDatabase1.setPhysicalDatabaseIdentifier("postgres-dev");
//
//        when(balancingRulesService.applyBalancingRules(postgresType, mappedNamespace, microserviceName1))
//                .thenReturn(physicalDatabase1);
        when(physicalDatabasesService.getByAdapterId(adapterId)).thenReturn(physicalDatabase1);

        // Response during the async restore process
        LogicalRestoreAdapterResponse response = LogicalRestoreAdapterResponse.builder()
                .status(COMPLETED_STATUS)
                .restoreId(logicalRestoreName)
                .databases(List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                        .status(COMPLETED_STATUS)
                        .previousDatabaseName(dbName)
                        .databaseName(newName)
                        .duration(1)
                        .build()))
                .build();

        // Same answer to DryRun non-DryRun mode
        when(dbaasAdapter.restoreV2(eq(logicalBackupName), anyBoolean(), any()))
                .thenReturn(response);

        when(dbaasAdapter.trackRestoreV2(eq(logicalRestoreName), any(), any()))
                .thenReturn(response);
        // Mocks to ensure user process
        DbResource resource1 = new DbResource();
        resource1.setId(UUID.randomUUID());
        resource1.setKind("kind");
        resource1.setName("newName");
        EnsuredUser user1 = new EnsuredUser();
        user1.setConnectionProperties(Map.of(
                "key", "value"
        ));
        user1.setResources(List.of(resource1));

        when(dbaasAdapter.ensureUser(null, null, newName, "admin")).thenReturn(user1);

        DbResource resource2 = new DbResource();
        resource2.setId(UUID.randomUUID());
        resource2.setKind("kind");
        resource2.setName("newName");
        EnsuredUser user2 = new EnsuredUser();
        user2.setConnectionProperties(Map.of(
                "key", "value"
        ));
        user2.setResources(List.of(resource2));

        when(dbaasAdapter.ensureUser(null, null, newName2, "admin")).thenReturn(user2);

        RestoreResponse restoreResponse = dbBackupV2Service.retryRestore(restoreName, false);
        dbBackupV2Service.checkRestoresAsync();

        assertEquals(restoreName, restoreResponse.getRestoreName());
        assertEquals(backupName, restoreResponse.getBackupName());
        assertEquals(RestoreStatus.IN_PROGRESS, restoreResponse.getStatus());
        assertEquals(2, restoreResponse.getTotal());
        assertEquals(1, restoreResponse.getCompleted());
        assertEquals(1, restoreResponse.getLogicalRestores().size());

        LogicalRestoreResponse logicalRestoreResponse = restoreResponse.getLogicalRestores().getFirst();
        assertNull(logicalRestoreResponse.getLogicalRestoreName());
        assertEquals(adapterId, logicalRestoreResponse.getAdapterId());
        assertEquals(2, logicalRestoreResponse.getRestoreDatabases().size());

        // Start assert initialized dbs
        List<DatabaseRegistry> databaseRegistries = databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType();
        assertEquals(3, databaseRegistries.size());

        DatabaseRegistry databaseRegistry1 = databaseRegistries.stream()
                .filter(db -> newName.equals(db.getName()))
                .findAny().orElse(null);
        assert databaseRegistry1 != null;
        assertEquals(classifierWrapper1.getClassifier(), databaseRegistry1.getClassifier());
        assertEquals("postgres-dev", databaseRegistry1.getPhysicalDatabaseId());

        DatabaseRegistry databaseRegistry2 = databaseRegistries.stream()
                .filter(db -> newName2.equals(db.getName()))
                .findAny().orElse(null);
        assert databaseRegistry2 != null;
        assertEquals(classifierWrapper2.getClassifierBeforeMapper(), databaseRegistry2.getClassifier());
        assertEquals("postgres-dev", databaseRegistry2.getPhysicalDatabaseId());

        DatabaseRegistry databaseRegistry3 = databaseRegistries.stream()
                .filter(db -> externalName.equals(db.getName()))
                .findAny().orElse(null);
        assert databaseRegistry3 != null;
        assertEquals(classifierWrapper3.getClassifierBeforeMapper(), databaseRegistry3.getClassifier());
        assertNull(databaseRegistry3.getPhysicalDatabaseId());

    }

    @Test
    void updateAndValidateClassifier() {
        String namespace = "test1-namespace";
        String namespaceMapped = "test1-namespace-mapped";

        List<ClassifierDetails> classifiers = getClassifiers(namespace, namespaceMapped);
        Mapping mapping = new Mapping();
        mapping.setNamespaces(Map.of(namespace, namespaceMapped));

        Set<SortedMap<String, Object>> uniqueClassifiers = new HashSet<>();
    }

    @Test
    void updateClassifier_withoutMapping() {
        SortedMap<String, Object> oldClassifier = new TreeMap<>();
        oldClassifier.put(NAMESPACE, "namespace");
        oldClassifier.put(MICROSERVICE_NAME, "microserviceName");
        oldClassifier.put(TENANT_ID, "tenant");
        oldClassifier.put(SCOPE, "tenant");

        ClassifierDetails classifier = new ClassifierDetails();
        classifier.setClassifierBeforeMapper(oldClassifier);

        List<ClassifierDetails> updatedClassifiers = dbBackupV2Service.applyMapping(List.of(classifier), null);
        assertEquals(1, updatedClassifiers.size());

        ClassifierDetails updatedClassifier = updatedClassifiers.getFirst();
        assertEquals(updatedClassifier.getClassifier(), updatedClassifier.getClassifierBeforeMapper());
        assertEquals(updatedClassifier.getClassifierBeforeMapper(), oldClassifier);
    }

    @Test
    void updateClassifier_tenantMapping() {
        String tenant = "tenant";
        String mappedTenant = "mappedTenant";

        SortedMap<String, Object> oldClassifier = new TreeMap<>();
        oldClassifier.put(NAMESPACE, "namespace");
        oldClassifier.put(MICROSERVICE_NAME, "microserviceName");
        oldClassifier.put(TENANT_ID, tenant);
        oldClassifier.put(SCOPE, tenant);

        ClassifierDetails classifier = new ClassifierDetails();
        classifier.setClassifierBeforeMapper(oldClassifier);

        Mapping mapping = new Mapping();
        mapping.setTenants(Map.of(
                tenant, mappedTenant
        ));

        List<ClassifierDetails> updatedClassifiers = dbBackupV2Service.applyMapping(List.of(classifier), mapping);
        assertEquals(1, updatedClassifiers.size());
        ClassifierDetails updatedClassifier = updatedClassifiers.getFirst();
        SortedMap<String, Object> mappedClassifier = updatedClassifier.getClassifier();

        assertNotNull(mappedClassifier);
        assertEquals(updatedClassifier.getClassifierBeforeMapper(), oldClassifier);

        assertEquals(oldClassifier.get(NAMESPACE), mappedClassifier.get(NAMESPACE));
        assertEquals(oldClassifier.get(MICROSERVICE_NAME), mappedClassifier.get(MICROSERVICE_NAME));
        assertEquals(oldClassifier.get(SCOPE), mappedClassifier.get(SCOPE));
        assertEquals(mappedTenant, mappedClassifier.get(TENANT_ID));
    }

    @Test
    void updateClassifier_nullTenantMapping() {
        String tenant = "tenant";
        String mappedTenant = "mappedTenant";

        SortedMap<String, Object> oldClassifier = new TreeMap<>();
        oldClassifier.put(NAMESPACE, "namespace");
        oldClassifier.put(MICROSERVICE_NAME, "microserviceName");
        oldClassifier.put(SCOPE, "service");

        ClassifierDetails classifier = new ClassifierDetails();
        classifier.setClassifierBeforeMapper(oldClassifier);

        Mapping mapping = new Mapping();
        mapping.setTenants(Map.of(
                tenant, mappedTenant
        ));

        List<ClassifierDetails> updatedClassifiers = dbBackupV2Service.applyMapping(List.of(classifier), mapping);
        assertEquals(1, updatedClassifiers.size());

        ClassifierDetails updatedClassifier = updatedClassifiers.getFirst();
        assertEquals(updatedClassifier.getClassifier(), updatedClassifier.getClassifierBeforeMapper());
        assertEquals(updatedClassifier.getClassifierBeforeMapper(), oldClassifier);
    }

    @Test
    void updateClassifier_namespaceMapping() {
        String namespace = "namespace";
        String mappedNamespace = "mappedNamespace";

        SortedMap<String, Object> oldClassifier = new TreeMap<>();
        oldClassifier.put(NAMESPACE, namespace);
        oldClassifier.put(MICROSERVICE_NAME, "microserviceName");
        oldClassifier.put(SCOPE, "service");

        ClassifierDetails classifier = new ClassifierDetails();
        classifier.setClassifierBeforeMapper(oldClassifier);

        Mapping mapping = new Mapping();
        mapping.setNamespaces(Map.of(
                namespace, mappedNamespace
        ));

        List<ClassifierDetails> updatedClassifiers = dbBackupV2Service.applyMapping(List.of(classifier), mapping);
        assertEquals(1, updatedClassifiers.size());

        SortedMap<String, Object> mappedClassifier = updatedClassifiers.getFirst().getClassifier();

        assertEquals(updatedClassifiers.getFirst().getClassifierBeforeMapper(), oldClassifier);

        assertEquals(oldClassifier.get(MICROSERVICE_NAME), mappedClassifier.get(MICROSERVICE_NAME));
        assertEquals(oldClassifier.get(SCOPE), mappedClassifier.get(SCOPE));
        assertEquals(mappedNamespace, mappedClassifier.get(NAMESPACE));
    }

    private static @NotNull List<ClassifierDetails> getClassifiers(String namespace, String namespaceMapped) {
        SortedMap<String, Object> classifier1 = new TreeMap<>();
        classifier1.put("microserviceName", "test1");
        classifier1.put("namespace", namespace);
        classifier1.put("scope", "service");

        SortedMap<String, Object> classifier2 = new TreeMap<>();
        classifier2.put("microserviceName", "test1");
        classifier2.put("namespace", namespaceMapped);
        classifier2.put("scope", "service");

        ClassifierDetails classifierWrapper1 = new ClassifierDetails();
        classifierWrapper1.setClassifierBeforeMapper(classifier1);

        ClassifierDetails classifierWrapper2 = new ClassifierDetails();
        classifierWrapper2.setClassifierBeforeMapper(classifier2);

        return List.of(classifierWrapper1, classifierWrapper2);
    }

    @Test
    void getAllDbByFilter_1() {
        String namespace1 = "namespace1";
        String namespace2 = "namespace2";
        String namespace3 = "namespace3";
        String namespace4 = "namespace4";

        String microserviceName1 = "microserviceName1";
        String microserviceName3 = "microserviceName3";
        String microserviceName4 = "microserviceName4";
        String microserviceName5 = "microserviceName5";
        String microserviceName6 = "microserviceName6";

        String postgresqlType = "postgresql";
        String arangoDbType = "arangoDb";
        String cassandraType = "cassandra";

        String adapterId = "adapterId";

        String dbName1 = "db1";
        String dbName2 = "db2";
        String dbName3 = "db3";
        String dbName4 = "db4";
        String dbName5 = "db5";

        Database db1 = getDatabase(adapterId, dbName1, false, false, "");
        Database db2 = getDatabase(adapterId, dbName2, false, false, "cfg");
        Database db3 = getDatabase(adapterId, dbName3, false, false, "cfg");
        Database db4 = getDatabase(adapterId, dbName4, false, false, "");
        Database db5 = getDatabase(adapterId, dbName5, false, false, "");

        DatabaseRegistry registry1 = getDatabaseRegistry(db1, namespace1, microserviceName1, "", postgresqlType);
        DatabaseRegistry registry2 = getDatabaseRegistry(db1, namespace2, microserviceName1, "", postgresqlType);
        DatabaseRegistry registry3 = getDatabaseRegistry(db2, namespace2, microserviceName3, "", cassandraType);
        DatabaseRegistry registry4 = getDatabaseRegistry(db3, namespace2, microserviceName4, "", cassandraType);
        DatabaseRegistry registry5 = getDatabaseRegistry(db4, namespace3, microserviceName5, "", arangoDbType);
        DatabaseRegistry registry6 = getDatabaseRegistry(db5, namespace4, microserviceName6, "", arangoDbType);

        Stream.of(registry1, registry2, registry3, registry4, registry5, registry6)
                .forEach(databaseRegistryDbaasRepository::saveAnyTypeLogDb);

        Filter filter = new Filter();
        filter.setNamespace(List.of(namespace1, namespace2));
        filter.setMicroserviceName(List.of(microserviceName1, microserviceName4));
        filter.setDatabaseType(List.of(DatabaseType.POSTGRESQL, DatabaseType.CASSANDRA));
        filter.setDatabaseKind(List.of(DatabaseKind.TRANSACTIONAL));

        Filter exclude = new Filter();
        exclude.setNamespace(List.of(namespace2));
        exclude.setMicroserviceName(List.of(microserviceName1));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setInclude(List.of(filter));
        filterCriteria.setExclude(List.of(exclude));

        Map<Database, List<DatabaseRegistry>> dbToBackup = dbBackupV2Service.getAllDbByFilter(filterCriteria);
        assertNotNull(dbToBackup);
        assertEquals(1, dbToBackup.size());

        dbToBackup.forEach((db, registries) -> {
            assertEquals(db1.getId(), db.getId());
            assertEquals(1, registries.size());
            assertEquals(registry1, db.getDatabaseRegistry().getFirst());
            assertEquals(registry1, registries.getFirst());
        });
    }

    @Test
    void getAllDbByFilter_2() {
        String namespace1 = "namespace1";
        String namespace2 = "namespace2";
        String namespace3 = "namespace3";
        String namespace4 = "namespace4";

        String microserviceName1 = "microserviceName1";
        String microserviceName3 = "microserviceName3";
        String microserviceName4 = "microserviceName4";
        String microserviceName5 = "microserviceName5";
        String microserviceName6 = "microserviceName6";

        String postgresqlType = "postgresql";
        String arangoDbType = "arangodb";
        String cassandraType = "cassandra";
        String adapterId = "adapterId";

        String dbName1 = "db1";
        String dbName2 = "db2";
        String dbName3 = "db3";
        String dbName4 = "db4";
        String dbName5 = "db5";

        Database db1 = getDatabase(adapterId, dbName1, false, false, "");
        Database db2 = getDatabase(adapterId, dbName2, false, false, "cfg");
        Database db3 = getDatabase(adapterId, dbName3, false, false, "cfg");
        Database db4 = getDatabase(adapterId, dbName4, false, false, "");
        Database db5 = getDatabase(adapterId, dbName5, false, false, "");

        DatabaseRegistry registry1 = getDatabaseRegistry(db1, namespace1, microserviceName1, "", postgresqlType);
        DatabaseRegistry registry2 = getDatabaseRegistry(db1, namespace2, microserviceName1, "", postgresqlType);
        DatabaseRegistry registry3 = getDatabaseRegistry(db2, namespace2, microserviceName3, "", cassandraType);
        DatabaseRegistry registry4 = getDatabaseRegistry(db3, namespace2, microserviceName4, "", cassandraType);
        DatabaseRegistry registry5 = getDatabaseRegistry(db4, namespace3, microserviceName5, "", arangoDbType);
        DatabaseRegistry registry6 = getDatabaseRegistry(db5, namespace4, microserviceName6, "", arangoDbType);

        Stream.of(registry1, registry2, registry3, registry4, registry5, registry6)
                .forEach(databaseRegistryDbaasRepository::saveAnyTypeLogDb);

        Filter filter1 = new Filter();
        filter1.setNamespace(List.of(namespace1));
        filter1.setDatabaseType(List.of(DatabaseType.POSTGRESQL, DatabaseType.CASSANDRA));

        Filter filter2 = new Filter();
        filter2.setNamespace(List.of(namespace2));
        filter2.setDatabaseType(List.of(DatabaseType.POSTGRESQL, DatabaseType.CASSANDRA));

        Filter exclude = new Filter();
        exclude.setMicroserviceName(List.of(microserviceName1));

        Filter exclude2 = new Filter();
        exclude2.setNamespace(List.of(namespace2));
        exclude2.setMicroserviceName(List.of(microserviceName4));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setInclude(List.of(filter1, filter2));
        filterCriteria.setExclude(List.of(exclude, exclude2));

        Map<Database, List<DatabaseRegistry>> dbToBackup = dbBackupV2Service.getAllDbByFilter(filterCriteria);
        assertNotNull(dbToBackup);
        assertEquals(1, dbToBackup.size());

        dbToBackup.forEach((db, registries) -> {
            assertEquals(db2.getId(), db.getId());
            assertEquals(1, registries.size());
            assertEquals(registry3, registries.getFirst());
        });
    }

    @Test
    void getAllDbByFilter_3() {
        String namespace1 = "namespace1";
        String namespace2 = "namespace2";
        String namespace3 = "namespace3";
        String namespace4 = "namespace4";

        String microserviceName1 = "microserviceName1";
        String microserviceName2 = "microserviceName2";
        String microserviceName3 = "microserviceName3";
        String microserviceName4 = "microserviceName4";
        String microserviceName5 = "microserviceName5";

        String postgresqlType = "postgresql";
        String arangoDbType = "arangodb";
        String cassandraType = "cassandra";
        String adapterId = "adapterId";

        String dbName1 = "db1";
        String dbName2 = "db2";
        String dbName3 = "db3";
        String dbName4 = "db4";
        String dbName5 = "db5";

        Database db1 = getDatabase(adapterId, dbName1, false, false, "");
        Database db2 = getDatabase(adapterId, dbName2, false, false, "cfg");
        Database db3 = getDatabase(adapterId, dbName3, false, false, "cfg");
        Database db4 = getDatabase(adapterId, dbName4, false, false, "");
        Database db5 = getDatabase(adapterId, dbName5, false, false, "");

        DatabaseRegistry registry1 = getDatabaseRegistry(db1, namespace1, microserviceName1, "", postgresqlType);
        DatabaseRegistry registry2 = getDatabaseRegistry(db1, namespace2, microserviceName1, "", postgresqlType);
        DatabaseRegistry registry3 = getDatabaseRegistry(db2, namespace2, microserviceName2, "", cassandraType);
        DatabaseRegistry registry4 = getDatabaseRegistry(db3, namespace3, microserviceName3, "", cassandraType);
        DatabaseRegistry registry5 = getDatabaseRegistry(db4, namespace4, microserviceName4, "", arangoDbType);
        DatabaseRegistry registry6 = getDatabaseRegistry(db5, namespace4, microserviceName5, "", arangoDbType);

        Stream.of(registry1, registry2, registry3, registry4, registry5, registry6)
                .forEach(databaseRegistryDbaasRepository::saveAnyTypeLogDb);

        Filter filter = new Filter();
        filter.setNamespace(List.of(namespace1, namespace2));
        filter.setMicroserviceName(List.of(microserviceName1));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setInclude(List.of(filter));

        Map<Database, List<DatabaseRegistry>> filteredDbs = dbBackupV2Service.getAllDbByFilter(filterCriteria);
        assertEquals(1, filteredDbs.size());

        filteredDbs.forEach((db, registries) -> {
            if (db.getId() == db1.getId()) {
                assertEquals(db1.getId(), db.getId());
                assertEquals(2, registries.size());
                DatabaseRegistry actualRegistry1 = filteredDbs.get(db1).stream()
                        .filter(r -> namespace1.equals(r.getNamespace()))
                        .findAny().orElse(null);

                DatabaseRegistry actualRegistry2 = filteredDbs.get(db1).stream()
                        .filter(r -> namespace2.equals(r.getNamespace()))
                        .findAny().orElse(null);
                assertEquals(registry1, actualRegistry1);
                assertEquals(registry2, actualRegistry2);
            } else {
                assertEquals(db1.getId(), db.getId());
            }
        });
    }

    @Test
    void getAllDbByFilter_4() {
        String namespace1 = "namespace1";
        String namespace2 = "namespace2";
        String namespace3 = "namespace3";
        String namespace4 = "namespace4";

        String microserviceName1 = "microserviceName1";
        String microserviceName2 = "microserviceName2";
        String microserviceName3 = "microserviceName3";
        String microserviceName4 = "microserviceName4";
        String microserviceName5 = "microserviceName5";
        String microserviceName6 = "microserviceName6";

        String postgresqlType = "postgresql";
        String arangoDbType = "arangodb";
        String cassandraType = "cassandra";
        String adapterId = "adapterId";

        String dbName1 = "db1";
        String dbName2 = "db2";
        String dbName3 = "db3";
        String dbName4 = "db4";
        String dbName5 = "db5";

        Database db1 = getDatabase(adapterId, dbName1, false, false, "");
        Database db2 = getDatabase(adapterId, dbName2, false, false, "cfg");
        Database db3 = getDatabase(adapterId, dbName3, false, false, "cfg");
        Database db4 = getDatabase(adapterId, dbName4, false, false, "");
        Database db5 = getDatabase(adapterId, dbName5, false, false, "");

        DatabaseRegistry registry1 = getDatabaseRegistry(db1, namespace1, microserviceName1, "", postgresqlType);
        DatabaseRegistry registry2 = getDatabaseRegistry(db1, namespace1, microserviceName2, "", postgresqlType);
        DatabaseRegistry registry3 = getDatabaseRegistry(db2, namespace2, microserviceName3, "", cassandraType);
        DatabaseRegistry registry4 = getDatabaseRegistry(db3, namespace2, microserviceName4, "", cassandraType);
        DatabaseRegistry registry5 = getDatabaseRegistry(db4, namespace3, microserviceName5, "", arangoDbType);
        DatabaseRegistry registry6 = getDatabaseRegistry(db5, namespace4, microserviceName6, "", arangoDbType);

        Stream.of(registry1, registry2, registry3, registry4, registry5, registry6)
                .forEach(databaseRegistryDbaasRepository::saveAnyTypeLogDb);

        Filter filter = new Filter();
        filter.setNamespace(List.of(namespace1));

        Filter filter1 = new Filter();
        filter1.setNamespace(List.of(namespace1));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setInclude(List.of(filter, filter1));

        Map<Database, List<DatabaseRegistry>> allDbByFilter = dbBackupV2Service.getAllDbByFilter(filterCriteria);

        assertEquals(1, allDbByFilter.size());

        allDbByFilter.forEach((db, registries) -> {
            assertEquals(db1.getId(), db.getId());
            assertEquals(2, registries.size());
        });
    }

    @Test
    void getAllDbByFilter_whenDatabasesNotFound() {
        Filter filter = new Filter();
        filter.setNamespace(List.of("namespace"));
        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setInclude(List.of(filter));

        assertThrows(DbNotFoundException.class,
                () -> dbBackupV2Service.getAllDbByFilter(filterCriteria));
    }

    @Test
    void getAllDbByFilter_RestorePart_1() {
        String dbName1 = "db1";
        String dbName2 = "db2";
        String dbName3 = "db3";
        String dbName4 = "db4";
        String dbName5 = "db5";
        String dbName6 = "db6";

        String logicalBackupName1 = "lb1";
        String logicalBackupName2 = "db2";
        String logicalBackupName3 = "db3";

        String adapterId1 = "adpater1";
        String adapterId2 = "adapter2";
        String adapterId3 = "adapter3";

        String postgresqlType = "postgresql";
        String cassandraType = "cassandra";
        String arangoType = "arangodb";

        String namespace1 = "namespace1";
        String namespace2 = "namespace2";
        String namespace3 = "namespace3";
        String namespace4 = "namespace4";

        String microserviceName1 = "microserviceName1";
        String microserviceName2 = "microserviceName2";
        String microserviceName3 = "microserviceName3";
        String microserviceName4 = "microserviceName4";
        String microserviceName5 = "microserviceName5";
        String microserviceName6 = "microserviceName6";

        BackupDatabase backupDatabase1 = getBackupDatabase(dbName1, List.of(getClassifier(namespace1, microserviceName1, null)), false, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase2 = getBackupDatabase(dbName2, List.of(getClassifier(namespace1, microserviceName2, null)), false, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase3 = getBackupDatabase(dbName3, List.of(getClassifier(namespace2, microserviceName3, null)), true, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase4 = getBackupDatabase(dbName4, List.of(getClassifier(namespace2, microserviceName4, null)), false, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase5 = getBackupDatabase(dbName5, List.of(getClassifier(namespace3, microserviceName5, null)), true, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase6 = getBackupDatabase(dbName6, List.of(getClassifier(namespace4, microserviceName6, null)), false, BackupTaskStatus.COMPLETED, null);

        LogicalBackup logicalBackup1 = getLogicalBackup(logicalBackupName1, adapterId1, postgresqlType, List.of(backupDatabase1, backupDatabase2), BackupTaskStatus.COMPLETED, null);
        LogicalBackup logicalBackup2 = getLogicalBackup(logicalBackupName2, adapterId2, cassandraType, List.of(backupDatabase3, backupDatabase4), BackupTaskStatus.COMPLETED, null);
        LogicalBackup logicalBackup3 = getLogicalBackup(logicalBackupName3, adapterId3, arangoType, List.of(backupDatabase5, backupDatabase6), BackupTaskStatus.COMPLETED, null);

        Filter filter = new Filter();
        filter.setNamespace(List.of(namespace1, namespace2));
        filter.setMicroserviceName(List.of(microserviceName1, microserviceName4));
        filter.setDatabaseType(List.of(DatabaseType.POSTGRESQL, DatabaseType.CASSANDRA));
        filter.setDatabaseKind(List.of(DatabaseKind.TRANSACTIONAL));

        Filter exclude = new Filter();
        exclude.setMicroserviceName(List.of(microserviceName4));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setInclude(List.of(filter));
        filterCriteria.setExclude(List.of(exclude));

        List<BackupDatabase> backupDatabases = List.of(backupDatabase1, backupDatabase2, backupDatabase3, backupDatabase4, backupDatabase5, backupDatabase6);
        List<DatabaseWithClassifiers> filteredDatabases = dbBackupV2Service.getAllDbByFilter(backupDatabases, filterCriteria);

        assertEquals(1, filteredDatabases.size());

        DatabaseWithClassifiers backupDatabaseDelegate = filteredDatabases.getFirst();

        assertEquals(backupDatabaseDelegate.backupDatabase(), backupDatabase1);
        assertEquals(backupDatabaseDelegate.classifiers().getFirst().getClassifierBeforeMapper(), backupDatabase1.getClassifiers().getFirst());
    }

    @Test
    void getAllDbByFilter_RestorePart_2() {
        String dbName1 = "db1";
        String dbName2 = "db2";
        String dbName3 = "db3";
        String dbName4 = "db4";
        String dbName5 = "db5";
        String dbName6 = "db6";

        String logicalBackupName1 = "lb1";
        String logicalBackupName2 = "db2";
        String logicalBackupName3 = "db3";

        String adapterId1 = "adpater1";
        String adapterId2 = "adapter2";
        String adapterId3 = "adapter3";

        String postgresqlType = "postgresql";
        String cassandraType = "cassandra";
        String arangoType = "arangodb";

        String namespace1 = "namespace1";
        String namespace2 = "namespace2";
        String namespace3 = "namespace3";
        String namespace4 = "namespace4";

        String microserviceName1 = "microserviceName1";
        String microserviceName2 = "microserviceName2";
        String microserviceName3 = "microserviceName3";
        String microserviceName4 = "microserviceName4";
        String microserviceName5 = "microserviceName5";
        String microserviceName6 = "microserviceName6";

        BackupDatabase backupDatabase1 = getBackupDatabase(dbName1, List.of(getClassifier(namespace1, microserviceName1, null)), false, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase2 = getBackupDatabase(dbName2, List.of(getClassifier(namespace1, microserviceName2, null)), false, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase3 = getBackupDatabase(dbName3, List.of(getClassifier(namespace2, microserviceName3, null)), true, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase4 = getBackupDatabase(dbName4, List.of(getClassifier(namespace2, microserviceName4, null)), false, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase5 = getBackupDatabase(dbName5, List.of(getClassifier(namespace3, microserviceName5, null)), true, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase6 = getBackupDatabase(dbName6, List.of(getClassifier(namespace4, microserviceName6, null)), false, BackupTaskStatus.COMPLETED, null);

        LogicalBackup logicalBackup1 = getLogicalBackup(logicalBackupName1, adapterId1, postgresqlType, List.of(backupDatabase1, backupDatabase2), BackupTaskStatus.COMPLETED, null);
        LogicalBackup logicalBackup2 = getLogicalBackup(logicalBackupName2, adapterId2, cassandraType, List.of(backupDatabase3, backupDatabase4), BackupTaskStatus.COMPLETED, null);
        LogicalBackup logicalBackup3 = getLogicalBackup(logicalBackupName3, adapterId3, arangoType, List.of(backupDatabase5, backupDatabase6), BackupTaskStatus.COMPLETED, null);

        Filter filter1 = new Filter();
        filter1.setNamespace(List.of(namespace1));
        filter1.setDatabaseType(List.of(DatabaseType.POSTGRESQL, DatabaseType.CASSANDRA));
        filter1.setDatabaseKind(List.of(DatabaseKind.TRANSACTIONAL));

        Filter filter2 = new Filter();
        filter2.setNamespace(List.of(namespace2));
        filter2.setMicroserviceName(List.of(microserviceName3));
        filter2.setDatabaseType(List.of(DatabaseType.POSTGRESQL, DatabaseType.CASSANDRA));

        Filter exclude = new Filter();
        exclude.setDatabaseType(List.of(DatabaseType.POSTGRESQL));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setInclude(List.of(filter1, filter2));
        filterCriteria.setExclude(List.of(exclude));

        List<BackupDatabase> backupDatabases = List.of(backupDatabase1, backupDatabase2, backupDatabase3, backupDatabase4, backupDatabase5, backupDatabase6);
        List<DatabaseWithClassifiers> filteredDatabases = dbBackupV2Service.getAllDbByFilter(backupDatabases, filterCriteria);

        assertEquals(1, filteredDatabases.size());

        DatabaseWithClassifiers backupDatabaseDelegate = filteredDatabases.getFirst();

        assertEquals(backupDatabaseDelegate.backupDatabase(), backupDatabase3);
        assertEquals(backupDatabaseDelegate.classifiers().getFirst().getClassifierBeforeMapper(), backupDatabase3.getClassifiers().getFirst());
    }

    @Test
    void getAllDbByFilter_RestorePart_3() {
        String dbName1 = "db1";
        String dbName2 = "db2";
        String dbName3 = "db3";

        String logicalBackupName1 = "lb1";
        String logicalBackupName2 = "db2";

        String adapterId1 = "adpater1";
        String adapterId2 = "adapter2";

        String postgresqlType = "postgresql";
        String cassandraType = "cassandra";

        String namespace1 = "namespace1";
        String namespace2 = "namespace2";
        String namespace3 = "namespace3";

        String microserviceName1 = "microserviceName1";
        String microserviceName2 = "microserviceName2";
        String microserviceName3 = "microserviceName3";

        BackupDatabase backupDatabase1 = getBackupDatabase(dbName1, List.of(getClassifier(namespace1, microserviceName1, null), getClassifier(namespace2, microserviceName1, null)), false, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase2 = getBackupDatabase(dbName2, List.of(getClassifier(namespace2, microserviceName2, null)), false, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase3 = getBackupDatabase(dbName3, List.of(getClassifier(namespace3, microserviceName3, null)), true, BackupTaskStatus.COMPLETED, null);

        LogicalBackup logicalBackup1 = getLogicalBackup(logicalBackupName1, adapterId1, postgresqlType, List.of(backupDatabase1, backupDatabase2), BackupTaskStatus.COMPLETED, null);
        LogicalBackup logicalBackup2 = getLogicalBackup(logicalBackupName2, adapterId2, cassandraType, List.of(backupDatabase3), BackupTaskStatus.COMPLETED, null);

        Filter filter1 = new Filter();
        filter1.setNamespace(List.of(namespace1));

        Filter filter2 = new Filter();
        filter2.setNamespace(List.of(namespace2));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setInclude(List.of(filter1, filter2));

        List<BackupDatabase> backupDatabases = List.of(backupDatabase1, backupDatabase2, backupDatabase3);
        List<DatabaseWithClassifiers> filteredDatabases = dbBackupV2Service.getAllDbByFilter(backupDatabases, filterCriteria);

        assertEquals(2, filteredDatabases.size());

        DatabaseWithClassifiers backupDatabaseDelegate1 = filteredDatabases.stream()
                .filter(db -> dbName1.equals(db.backupDatabase().getName()))
                .findAny().orElse(null);
        assertNotNull(backupDatabaseDelegate1);
        assertEquals(backupDatabaseDelegate1.backupDatabase(), backupDatabase1);
        assertEquals(2, backupDatabaseDelegate1.classifiers().size());

        SortedMap<String, Object> classifier1 = backupDatabase1.getClassifiers().stream()
                .filter(classifier -> namespace1.equals(classifier.get(NAMESPACE)))
                .findAny().orElse(null);
        SortedMap<String, Object> classifier2 = backupDatabase1.getClassifiers().stream()
                .filter(classifier -> namespace2.equals(classifier.get(NAMESPACE)))
                .findAny().orElse(null);
        assertNotNull(classifier1);
        assertNotNull(classifier2);

        DatabaseWithClassifiers backupDatabaseDelegate2 = filteredDatabases.stream()
                .filter(db -> dbName2.equals(db.backupDatabase().getName()))
                .findAny().orElse(null);
        assertNotNull(backupDatabaseDelegate2);
        assertEquals(backupDatabaseDelegate2.backupDatabase(), backupDatabase2);
        assertEquals(1, backupDatabaseDelegate2.classifiers().size());
        assertEquals(backupDatabaseDelegate2.classifiers().getFirst().getClassifierBeforeMapper(), backupDatabase2.getClassifiers().getFirst());
    }

    @Test
    void getAllDbByFilter_RestorePart_4() {
        String dbName1 = "db1";
        String dbName2 = "db2";
        String dbName3 = "db3";

        String logicalBackupName1 = "lb1";
        String logicalBackupName2 = "db2";

        String adapterId1 = "adpater1";
        String adapterId2 = "adapter2";

        String postgresqlType = "postgresql";
        String cassandraType = "cassandra";

        String namespace1 = "namespace1";
        String namespace2 = "namespace2";
        String namespace3 = "namespace3";

        String microserviceName1 = "microserviceName1";
        String microserviceName2 = "microserviceName2";
        String microserviceName3 = "microserviceName3";

        BackupDatabase backupDatabase1 = getBackupDatabase(dbName1, List.of(getClassifier(namespace1, microserviceName1, null), getClassifier(namespace2, microserviceName1, null)), false, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase2 = getBackupDatabase(dbName2, List.of(getClassifier(namespace2, microserviceName2, null)), false, BackupTaskStatus.COMPLETED, null);
        BackupDatabase backupDatabase3 = getBackupDatabase(dbName3, List.of(getClassifier(namespace3, microserviceName3, null)), true, BackupTaskStatus.COMPLETED, null);

        LogicalBackup logicalBackup1 = getLogicalBackup(logicalBackupName1, adapterId1, postgresqlType, List.of(backupDatabase1, backupDatabase2), BackupTaskStatus.COMPLETED, null);
        LogicalBackup logicalBackup2 = getLogicalBackup(logicalBackupName2, adapterId2, cassandraType, List.of(backupDatabase3), BackupTaskStatus.COMPLETED, null);

        Filter filter1 = new Filter();
        filter1.setNamespace(List.of(namespace1));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setInclude(List.of(filter1));

        List<BackupDatabase> backupDatabases = List.of(backupDatabase1, backupDatabase2, backupDatabase3);
        List<DatabaseWithClassifiers> filteredDatabases = dbBackupV2Service.getAllDbByFilter(backupDatabases, filterCriteria);

        assertEquals(1, filteredDatabases.size());

        DatabaseWithClassifiers backupDatabaseDelegate1 = filteredDatabases.stream()
                .filter(db -> dbName1.equals(db.backupDatabase().getName()))
                .findAny().orElse(null);
        assertNotNull(backupDatabaseDelegate1);
        assertEquals(backupDatabaseDelegate1.backupDatabase(), backupDatabase1);
        assertEquals(1, backupDatabaseDelegate1.classifiers().size());

        SortedMap<String, Object> classifier1 = backupDatabase1.getClassifiers().stream()
                .filter(classifier -> namespace1.equals(classifier.get(NAMESPACE)))
                .findAny().orElse(null);
        assertNotNull(classifier1);
    }

    @Test
    void validateAndFilterExternalDb_testFiltering() {
        String namespace1 = "namespace1";
        String namespace2 = "namespace2";
        String namespace3 = "namespace3";

        String microserviceName1 = "microserviceName1";
        String microserviceName2 = "microserviceName2";
        String microserviceName3 = "microserviceName3";

        String dbName1 = "db1";
        String dbName2 = "db2";
        String dbName3 = "db3";

        String postgresqlType = "postgresql";
        SortedMap<String, Object> classifier = getClassifier(namespace1, microserviceName1, null);

        BackupExternalDatabase externalDatabase1 = getBackupExternalDatabase(dbName1, postgresqlType, List.of(classifier));
        BackupExternalDatabase externalDatabase2 = getBackupExternalDatabase(dbName2, postgresqlType, List.of(getClassifier(namespace2, microserviceName2, null)));
        BackupExternalDatabase externalDatabase3 = getBackupExternalDatabase(dbName3, postgresqlType, List.of(getClassifier(namespace3, microserviceName3, null)));

        Filter filter = new Filter();
        filter.setNamespace(List.of(namespace1, namespace2));

        Filter exclude = new Filter();
        exclude.setMicroserviceName(List.of(microserviceName2));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setInclude(List.of(filter));
        filterCriteria.setExclude(List.of(exclude));

        List<RestoreExternalDatabase> restoreExternalDatabases = dbBackupV2Service.validateAndFilterExternalDb(List.of(externalDatabase1, externalDatabase2, externalDatabase3), ExternalDatabaseStrategy.INCLUDE, filterCriteria);
        assertEquals(1, restoreExternalDatabases.size());

        RestoreExternalDatabase externalDb = restoreExternalDatabases.getFirst();
        assertEquals(dbName1, externalDb.getName());
        assertEquals(postgresqlType, externalDb.getType());
        assertEquals(1, externalDb.getClassifiers().size());
        assertEquals(classifier, externalDb.getClassifiers().getFirst().getClassifierBeforeMapper());
    }

    @Test
    void validateAndFilterDatabasesForBackup_ExternalDatabaseStrategyInclude() {
        String namespace = "namespace";
        String db1Name = "db1";
        String db2Name = "db2";
        String db3Name = "db3";
        String db4Name = "db4";

        String adapter1Name = "adapter1";
        String adapter2Name = "adapter2";

        Map<Database, List<DatabaseRegistry>> databaseList = getDatabase(
                Map.of(
                        db1Name, adapter1Name,
                        db2Name, adapter2Name,
                        db3Name, "",
                        db4Name, adapter2Name
                ),
                namespace
        );

        Database db1 = databaseList.keySet().stream().filter(db -> db1Name.equals(db.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(db1);
        db1.setExternallyManageable(false);

        Database db2 = databaseList.keySet().stream().filter(db -> db2Name.equals(db.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(db2);
        db2.setExternallyManageable(false);
        db2.setBackupDisabled(true);

        Database db3 = databaseList.keySet().stream().filter(db -> db3Name.equals(db.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(db3);
        db3.setExternallyManageable(true);
        db3.setAdapterId(null);

        DbaasAdapter adapter1 = Mockito.mock(DbaasAdapter.class);
        when(adapter1.isBackupRestoreSupported())
                .thenReturn(false);
        when(physicalDatabasesService.getAdapterById(adapter1Name))
                .thenReturn(adapter1);

        DbaasAdapter adapter2 = Mockito.mock(DbaasAdapter.class);
        when(adapter2.isBackupRestoreSupported())
                .thenReturn(true);
        when(physicalDatabasesService.getAdapterById(adapter2Name))
                .thenReturn(adapter2);

        Map<Database, List<DatabaseRegistry>> filteredDatabases = dbBackupV2Service.validateAndFilterDatabasesForBackup(
                databaseList,
                true,
                ExternalDatabaseStrategy.INCLUDE
        );

        assertNotNull(filteredDatabases);
        assertEquals(2, filteredDatabases.size());

        Database filteredDatabase3 = filteredDatabases.keySet().stream()
                .filter(db -> db3Name.equals(db.getName()))
                .findAny()
                .orElse(null);
        assertNotNull(filteredDatabase3);
        assertEquals(db3, filteredDatabase3);

        Database db4 = databaseList.keySet().stream()
                .filter(db -> db4Name.equals(db.getName()))
                .findFirst()
                .orElse(null);

        Database filteredDatabase4 = filteredDatabases.keySet().stream()
                .filter(db -> db4Name.equals(db.getName()))
                .findAny()
                .orElse(null);
        assertNotNull(filteredDatabase4);
        assertEquals(db4, filteredDatabase4);
    }


    @Test
    void validateAndFilterDatabasesForBackup_whenStrategyFail() {
        String namespace = "namespace";
        String db1Name = "db1";
        String adapter1Name = "adapter1";

        Map<Database, List<DatabaseRegistry>> databaseList = getDatabase(
                Map.of(db1Name, adapter1Name),
                namespace
        );
        Database db1 = databaseList.keySet().stream().filter(db -> db1Name.equals(db.getName()))
                .findAny()
                .orElse(null);
        assertNotNull(db1);
        db1.setExternallyManageable(true);

        DatabaseBackupRestoreNotSupportedException ex = assertThrows(DatabaseBackupRestoreNotSupportedException.class,
                () -> dbBackupV2Service.validateAndFilterDatabasesForBackup(
                        databaseList,
                        false,
                        ExternalDatabaseStrategy.FAIL
                ));

        assertTrue(ex.getMessage().contains("External databases not allowed for backup by strategy=FAIL: " + db1Name));
    }

    @Test
    void checkBackupsAsync_shouldNotRunInParallelAcrossNodes() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        when(backupRepository.findBackupsToTrack())
                .thenAnswer(new AnswersWithDelay(100, new Returns(List.of())));
        Mockito.clearInvocations(backupRepository);

        Runnable pod1Job = () -> {
            dbBackupV2Service.checkBackupsAsync();
        };

        Runnable pod2Job = () -> {
            dbBackupV2Service.checkBackupsAsync();
        };

        executor.submit(pod1Job);
        executor.submit(pod2Job);
        executor.shutdown();
        executor.awaitTermination(1, SECONDS);

        Mockito.verify(backupRepository, Mockito.times(1)).findBackupsToTrack();
        Mockito.reset(backupRepository);
    }

    @Test
    void trackAndAggregate_backupFinishedWithStatusFailed() {
        String backupName = "backupName";
        String namespace = "namespace";
        String db1Name = "db1";
        String db2Name = "db2";
        String db3Name = "db3";

        LogicalBackupAdapterResponse adapterResponse = new LogicalBackupAdapterResponse(
                "completed",
                null,
                "logicalBackupName0",
                Instant.now(),
                Instant.now(),
                "storageName",
                "blobPath",
                List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .status("completed")
                                .databaseName(db1Name)
                                .size(1)
                                .build(),
                        LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .status("completed")
                                .databaseName(db2Name)
                                .size(1)
                                .build()
                )
        );

        Backup backup = getBackup(
                backupName,
                namespace
        );

        backup.getLogicalBackups().stream()
                .filter(db -> "logicalBackupName0".equals(db.getLogicalBackupName()))
                .forEach(logicalBackup -> {
                    logicalBackup.setStatus(BackupTaskStatus.IN_PROGRESS);
                    logicalBackup.getBackupDatabases().stream()
                            .filter(db -> db1Name.equals(db.getName()))
                            .findAny()
                            .ifPresent(db -> db.setStatus(BackupTaskStatus.COMPLETED));
                    logicalBackup.getBackupDatabases().stream()
                            .filter(db -> db2Name.equals(db.getName()))
                            .findAny()
                            .ifPresent(db -> db.setStatus(BackupTaskStatus.IN_PROGRESS));
                });
        backup.getLogicalBackups().stream()
                .filter(db -> "logicalBackupName1".equals(db.getLogicalBackupName()))
                .forEach(logicalBackup -> {
                    logicalBackup.setStatus(BackupTaskStatus.FAILED);
                    logicalBackup.setErrorMessage(db3Name + "=Error during backup process");
                    logicalBackup.getBackupDatabases().stream()
                            .filter(db -> db3Name.equals(db.getName()))
                            .findAny()
                            .ifPresent(db -> db.setStatus(BackupTaskStatus.FAILED));
                });
        Backup updatedBackup = backupRepository.save(backup);
        DbaasAdapter adapter1 = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById("0"))
                .thenReturn(adapter1);
        when(adapter1.trackBackupV2("logicalBackupName0", "storageName", "blobPath"))
                .thenReturn(adapterResponse);

        dbBackupV2Service.trackAndAggregate(updatedBackup);

        Backup expectedBackup = backupRepository.findById(backupName);
        assertNotNull(expectedBackup);
        assertEquals(BackupStatus.FAILED, expectedBackup.getStatus());

        String errorMsg = String.format("LogicalBackup %s failed: %s=Error during backup process", "logicalBackupName1", db3Name);
        assertEquals(errorMsg, expectedBackup.getErrorMessage());
        assertEquals(1, expectedBackup.getAttemptCount());
    }

    @Test
    void trackAndAggregate_backupAttemptExceeded_aggregatorMustBeFailed() {
        String backupName = "backupName";

        Backup backup = getBackup(backupName, "namespace");
        backup.setStatus(BackupStatus.IN_PROGRESS);
        backup.setAttemptCount(21);

        dbBackupV2Service.trackAndAggregate(backup);

        Backup expectedBackup = backupRepository.findById(backupName);

        assertNotNull(expectedBackup);
        assertEquals(21, backup.getAttemptCount());
        assertNotNull(expectedBackup.getStatus());
        assertEquals(BackupStatus.FAILED, backup.getStatus());

        String errorMsg = "The number of attempts exceeded 20";
        assertEquals(errorMsg, backup.getErrorMessage());
    }

    @Test
    void aggregateStatus_shouldReturnInProgress_whenInputInProgressFailedCompleted() {
        Set<BackupTaskStatus> statusSet = Set.of(BackupTaskStatus.IN_PROGRESS, BackupTaskStatus.FAILED, BackupTaskStatus.COMPLETED);
        BackupStatus backupStatus = dbBackupV2Service.aggregateBackupTaskStatus(statusSet);

        assertNotNull(backupStatus);
        assertEquals(BackupStatus.IN_PROGRESS, backupStatus);
    }

    @Test
    void aggregateStatus_shouldReturnInProgress_whenInputNotStartedFailedCompleted() {
        Set<BackupTaskStatus> statusSet = Set.of(BackupTaskStatus.NOT_STARTED, BackupTaskStatus.FAILED, BackupTaskStatus.COMPLETED);
        BackupStatus backupStatus = dbBackupV2Service.aggregateBackupTaskStatus(statusSet);

        assertNotNull(backupStatus);
        assertEquals(BackupStatus.IN_PROGRESS, backupStatus);
    }

    @Test
    void aggregateStatus_shouldReturnFailed_whenInputFailedCompleted() {
        Set<BackupTaskStatus> statusSet = Set.of(BackupTaskStatus.FAILED, BackupTaskStatus.COMPLETED);
        BackupStatus backupStatus = dbBackupV2Service.aggregateBackupTaskStatus(statusSet);

        assertNotNull(backupStatus);
        assertEquals(BackupStatus.FAILED, backupStatus);
    }

    @Test
    void aggregateStatus_shouldReturnCompleted_whenInputCompleted() {
        Set<BackupTaskStatus> statusSet = Set.of(BackupTaskStatus.COMPLETED);
        BackupStatus backupStatus = dbBackupV2Service.aggregateBackupTaskStatus(statusSet);

        assertNotNull(backupStatus);
        assertEquals(BackupStatus.COMPLETED, backupStatus);
    }

    @Test
    void getBackup() {
        String backupName = "backupName";
        String namespace = "namespace";

        String logicalBackupName1 = "logicalBackupName0";
        String logicalBackupName2 = "logicalBackupName1";

        String dbName1 = "db0";
        String dbName2 = "db1";
        String dbName3 = "db2";

        Backup backup = getBackup(backupName, namespace);
        backupRepository.save(backup);

        BackupResponse response = dbBackupV2Service.getBackup(backupName);
        assertNotNull(response);
        assertEquals(backupName, response.getBackupName());
        assertEquals(BLOB_PATH, response.getBlobPath());
        assertEquals(STORAGE_NAME, response.getStorageName());
        assertEquals(ExternalDatabaseStrategy.INCLUDE, response.getExternalDatabaseStrategy());
        assertEquals(BackupStatus.NOT_STARTED, response.getStatus());

        FilterCriteria responseFilterCriteria = response.getFilterCriteria();
        assertNotNull(responseFilterCriteria);
        assertEquals(1, responseFilterCriteria.getInclude().size());
        assertEquals(namespace, responseFilterCriteria.getInclude().getFirst().getNamespace().getFirst());

        List<BackupExternalDatabase> externalDatabases = backup.getExternalDatabases();
        assertNull(externalDatabases);

        List<LogicalBackupResponse> logicalBackups = response.getLogicalBackups();
        assertEquals(2, logicalBackups.size());

        LogicalBackupResponse logicalBackup1 = logicalBackups.stream().filter(db -> logicalBackupName1.equals(db.getLogicalBackupName()))
                .findAny().orElse(null);
        assertNotNull(logicalBackup1, String.format("Logical backup with name '%s' not found", logicalBackupName1));
        assertEquals("0", logicalBackup1.getAdapterId());
        assertEquals("postgresql", logicalBackup1.getType());
        assertNull(logicalBackup1.getErrorMessage());
        assertEquals(2, logicalBackup1.getBackupDatabases().size());

        BackupDatabaseResponse backupDatabase1 = logicalBackup1.getBackupDatabases().stream()
                .filter(db -> dbName1.equals(db.getName()))
                .findAny().orElse(null);

        assertNotNull(backupDatabase1);
        assertFalse(backupDatabase1.isConfigurational());
        assertEquals("path", backupDatabase1.getPath());

        BackupDatabaseResponse backupDatabase2 = logicalBackup1.getBackupDatabases().stream()
                .filter(db -> dbName2.equals(db.getName()))
                .findAny().orElse(null);

        assertNotNull(backupDatabase2);
        assertFalse(backupDatabase2.isConfigurational());
        assertEquals("path", backupDatabase2.getPath());

        LogicalBackupResponse logicalBackup2 = logicalBackups.stream()
                .filter(db -> logicalBackupName2.equals(db.getLogicalBackupName()))
                .findAny().orElse(null);
        assertNotNull(logicalBackup2, String.format("Logical backup with name '%s' not found", logicalBackupName2));
        assertEquals("1", logicalBackup2.getAdapterId());
        assertEquals("postgresql", logicalBackup2.getType());
        assertNull(logicalBackup2.getErrorMessage());
        assertEquals(1, logicalBackup2.getBackupDatabases().size());

        BackupDatabaseResponse backupDatabase3 = logicalBackup2.getBackupDatabases().stream()
                .filter(db -> dbName3.equals(db.getName()))
                .findAny().orElse(null);
        assertNotNull(backupDatabase3);
        assertFalse(backupDatabase3.isConfigurational());
        assertEquals("path", backupDatabase3.getPath());
    }

    @Test
    void getRestore() {
        String restoreName = "restoreName";
        String namespace = "namespace";

        String logicalRestoreName1 = "logicalRestoreName0";
        String logicalRestoreName2 = "logicalRestoreName1";

        String dbName1 = "db0";
        String dbName2 = "db1";
        String dbName3 = "db2";

        Restore restore = getRestore(restoreName, namespace);
        restoreRepository.save(restore);

        RestoreResponse response = dbBackupV2Service.getRestore(restoreName);
        assertNotNull(response);
        assertEquals(BLOB_PATH, response.getBlobPath());
        assertEquals(STORAGE_NAME, response.getStorageName());
        assertEquals(ExternalDatabaseStrategy.INCLUDE, response.getExternalDatabaseStrategy());
        assertEquals(RestoreStatus.NOT_STARTED, response.getStatus());

        FilterCriteria responseFilterCriteria = response.getFilterCriteria();
        assertNotNull(responseFilterCriteria);
        assertEquals(1, responseFilterCriteria.getInclude().size());
        assertEquals(namespace, responseFilterCriteria.getInclude().getFirst().getNamespace().getFirst());

        List<RestoreExternalDatabase> externalDatabases = restore.getExternalDatabases();
        assertNull(externalDatabases);

        List<LogicalRestoreResponse> logicalRestores = response.getLogicalRestores();
        assertEquals(2, logicalRestores.size());

        LogicalRestoreResponse logicalRestore1 = logicalRestores.stream().filter(db -> logicalRestoreName1.equals(db.getLogicalRestoreName()))
                .findAny().orElse(null);
        assertNotNull(logicalRestore1, String.format("Logical backup with name '%s' not found", logicalRestoreName1));
        assertEquals("0", logicalRestore1.getAdapterId());
        assertEquals("postgresql", logicalRestore1.getType());
        assertNull(logicalRestore1.getErrorMessage());
        assertEquals(2, logicalRestore1.getRestoreDatabases().size());

        RestoreDatabaseResponse restoreDatabase1 = logicalRestore1.getRestoreDatabases().stream()
                .filter(db -> dbName1.equals(db.getName()))
                .findAny().orElse(null);

        assertNotNull(restoreDatabase1);
        assertEquals("path", restoreDatabase1.getPath());

        RestoreDatabaseResponse restoreDatabase2 = logicalRestore1.getRestoreDatabases().stream()
                .filter(db -> dbName2.equals(db.getName()))
                .findAny().orElse(null);

        assertNotNull(restoreDatabase2);
        assertEquals("path", restoreDatabase2.getPath());

        LogicalRestoreResponse logicalRestore2 = logicalRestores.stream()
                .filter(db -> logicalRestoreName2.equals(db.getLogicalRestoreName()))
                .findAny().orElse(null);
        assertNotNull(logicalRestore2, String.format("Logical backup with name '%s' not found", logicalRestoreName2));
        assertEquals("1", logicalRestore2.getAdapterId());
        assertEquals("postgresql", logicalRestore2.getType());
        assertNull(logicalRestore2.getErrorMessage());
        assertEquals(1, logicalRestore2.getRestoreDatabases().size());

        RestoreDatabaseResponse restoreDatabase3 = logicalRestore2.getRestoreDatabases().stream()
                .filter(db -> dbName3.equals(db.getName()))
                .findAny().orElse(null);
        assertNotNull(restoreDatabase3);
        assertEquals("path", restoreDatabase3.getPath());
    }

    @Test
    void getBackupStatus() {
        String backupName = "backupName";
        Backup backup = getBackup(backupName, "namespace");
        backup.setStatus(BackupStatus.COMPLETED);
        backup.setTotal(3);
        backup.setCompleted(3);
        backup.setSize(3L);

        backupRepository.save(backup);

        BackupStatusResponse response = dbBackupV2Service.getCurrentStatus(backupName);
        assertEquals(BackupStatus.COMPLETED, response.getStatus());
        assertEquals(3, response.getTotal());
        assertEquals(3, response.getCompleted());
        assertEquals(3L, response.getSize());
    }

    @Test
    void getBackupStatus_whenBackupDeleted() {
        String backupName = "backupName";
        assertThrows(BackupNotFoundException.class,
                () -> dbBackupV2Service.getCurrentStatus(backupName));
    }

    @Test
    void getRestoreStatus() {
        String restoreName = "restoreName";
        Restore restore = getRestore(restoreName, "namespace");
        restore.setStatus(RestoreStatus.COMPLETED);
        restore.setTotal(3);
        restore.setCompleted(3);

        restoreRepository.save(restore);

        RestoreStatusResponse response = dbBackupV2Service.getRestoreStatus(restoreName);
        assertEquals(RestoreStatus.COMPLETED, response.getStatus());
        assertEquals(3, response.getTotal());
        assertEquals(3, response.getCompleted());
    }

    @Test
    void getRestoreStatus_whenRestoreDeleted() {
        String restoreName = "restoreName";
        assertThrows(BackupRestorationNotFoundException.class,
                () -> dbBackupV2Service.getRestoreStatus(restoreName));
    }

    @Test
    void getBackupMetadata() {
        String backupName = "backupName";
        String namespace = "namespace";
        Backup backup = getBackup(backupName, namespace);
        backup.setStatus(BackupStatus.COMPLETED);
        backupRepository.save(backup);

        BackupResponse response = dbBackupV2Service.getBackupMetadata(backupName);
        assertNotNull(response);
        assertEquals(backupName, response.getBackupName());
        assertEquals("storageName", response.getStorageName());
        assertEquals("blobPath", response.getBlobPath());
        assertEquals(2, response.getLogicalBackups().size());

        LogicalBackupResponse logicalBackupResponse1 = response.getLogicalBackups().stream()
                .filter(db -> "logicalBackupName0".equals(db.getLogicalBackupName()))
                .findAny()
                .orElse(null);
        assertNotNull(logicalBackupResponse1);
        assertEquals(2, logicalBackupResponse1.getBackupDatabases().size());

        LogicalBackupResponse logicalBackupResponse2 = response.getLogicalBackups().stream()
                .filter(db -> "logicalBackupName1".equals(db.getLogicalBackupName()))
                .findAny()
                .orElse(null);
        assertNotNull(logicalBackupResponse2);
        assertEquals(1, logicalBackupResponse2.getBackupDatabases().size());
    }

    @Test
    void getBackupMetadata_whenBackupMetadataUnprocessable() {
        String backupName = "backupName";
        String namespace = "namespace";
        Backup backup = getBackup(backupName, namespace);
        backup.setStatus(BackupStatus.IN_PROGRESS);
        backupRepository.save(backup);

        assertThrows(UnprocessableEntityException.class,
                () -> dbBackupV2Service.getBackupMetadata(backupName));
    }

    @Test
    void uploadBackupMetadata_uploadToNewEnvironment() {
        String backupName = "backupName";
        String namespace = "namespace";
        BackupResponse backupResponse = getBackupResponse(backupName, namespace);
        dbBackupV2Service.uploadBackupMetadata(backupResponse);
        Backup backup = backupRepository.findById(backupName);
        assertNotNull(backup);
        assertTrue(backup.isImported());
    }

    @Test
    void uploadBackupMetadata_restoreDeletedBackup() {
        String backupName = "backupName";
        String namespace = "namespace";
        String digest = "digest";

        Backup expectedBackup = getBackup(backupName, namespace);
        expectedBackup.setStatus(BackupStatus.DELETED);
        expectedBackup.setImported(true);
        expectedBackup.setDigest(digest);
        backupRepository.save(expectedBackup);

        BackupResponse backupResponse = getBackupResponse(backupName, namespace);
        backupResponse.setDigest(digest);

        dbBackupV2Service.uploadBackupMetadata(backupResponse);

        Backup actualBackup = backupRepository.findById(backupName);
        assertNotNull(actualBackup);
        assertEquals(BackupStatus.COMPLETED, actualBackup.getStatus());
    }

    @Test
    void uploadBackupMetadata_restoreDeletedBackup_digestMismatch() {
        String backupName = "backupName";
        String namespace = "namespace";
        String digest = "digest";
        String anotherDigest = "anotherDigest";

        Backup expectedBackup = getBackup(backupName, namespace);
        expectedBackup.setStatus(BackupStatus.DELETED);
        expectedBackup.setImported(true);
        expectedBackup.setDigest(digest);
        backupRepository.save(expectedBackup);

        BackupResponse backupResponse = getBackupResponse(backupName, namespace);
        backupResponse.setDigest(anotherDigest);


        IntegrityViolationException ex = assertThrows(IntegrityViolationException.class,
                () -> dbBackupV2Service.uploadBackupMetadata(backupResponse));
        assertEquals(
                String.format("Digest header mismatch: expected digest %s but got %s",
                        digest, anotherDigest),
                ex.getDetail());
    }

    @Test
    void uploadBackupMetadata_restoreDeletedBackup_backupNotImported() {
        String backupName = "backupName";
        String namespace = "namespace";

        Backup expectedBackup = getBackup(backupName, namespace);
        expectedBackup.setStatus(BackupStatus.DELETED);
        backupRepository.save(expectedBackup);

        BackupResponse backupResponse = getBackupResponse(backupName, namespace);

        IllegalResourceStateException ex = assertThrows(IllegalResourceStateException.class,
                () -> dbBackupV2Service.uploadBackupMetadata(backupResponse));

        assertEquals(
                String.format("Resource has illegal state: can't restore a %s backup that is not imported",
                        BackupStatus.DELETED),
                ex.getDetail());
    }

    @Test
    void deleteBackup_forceTrue() {
        String backupName = "backupName";
        String adapter1Name = "0";
        String adapter2Name = "1";

        Backup backup = getBackup(backupName, "namespace");
        backup.setStatus(BackupStatus.COMPLETED);

        backup.getLogicalBackups().forEach(db -> db.setBackup(backup));

        backupRepository.save(backup);

        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        DbaasAdapter adapter1 = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapter1Name))
                .thenReturn(adapter);
        when(physicalDatabasesService.getAdapterById(adapter2Name))
                .thenReturn(adapter1);

        dbBackupV2Service.deleteBackup(backupName, true);

        Backup deletedBackup = backupRepository.findById(backupName);
        assertNotNull(deletedBackup);
        assertTrue(
                deletedBackup.getStatus() == BackupStatus.DELETE_IN_PROGRESS
                        || deletedBackup.getStatus() == BackupStatus.DELETED
        );
    }

    @Test
    void deleteRestore() {
        String restoreName = "restoreName";
        Restore restore = getRestore(restoreName, "namespace");
        restore.setStatus(RestoreStatus.COMPLETED);
        restoreRepository.save(restore);
        dbBackupV2Service.deleteRestore(restoreName);
        Restore response = restoreRepository.findById(restoreName);
        assertNotNull(response);
        assertEquals(RestoreStatus.DELETED, response
                .getStatus());
    }

    @Test
    void deleteRestore_whenRestoreStatusUnprocessable() {
        String restoreName = "restoreName";
        restoreRepository.save(getRestore(restoreName, "namespace"));
        assertThrows(UnprocessableEntityException.class,
                () -> dbBackupV2Service.deleteRestore(restoreName));
    }

    @Test
    void deleteBackupFromDb() {
        String backupName = "backupName";
        String namespace = "namespace";
        Backup backup = getBackup(backupName, namespace);
        backupRepository.save(backup);

        Backup existedBackup = backupRepository.findById(backupName);
        assertNotNull(existedBackup);

        when(dbaaSHelper.isProductionMode()).thenReturn(false);

        dbBackupV2Service.deleteBackupFromDb(backupName);

        Backup deletedBackup = backupRepository.findById(backupName);
        assertNull(deletedBackup);
    }

    private Database getDatabase(String adapterId, String name, boolean isExternal, boolean isBackupDisabled, String bgVersion) {
        DbState dbState = new DbState();
        dbState.setId(UUID.randomUUID());
        dbState.setState(AbstractDbState.DatabaseStateStatus.CREATED);
        dbState.setDatabaseState(AbstractDbState.DatabaseStateStatus.CREATED);
        dbState.setDescription("descripton");
        dbState.setPodName("podName");

        Map<String, Object> map = new HashMap<>();
        map.put("role", "admin");
        map.put("username", "oldUsername");
        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setAdapterId(adapterId);
        database.setName(name);
        database.setDbState(dbState);
        database.setDatabaseRegistry(new ArrayList<>());
        database.setSettings(new HashMap<>());
        database.setBackupDisabled(isBackupDisabled);
        database.setExternallyManageable(isExternal);
        database.setBgVersion(bgVersion);
        database.setConnectionProperties(List.of(map));
        database.setClassifier(getClassifier("namespace", "microserviceName", "tenantId"));
        return database;
    }

    private DatabaseRegistry getDatabaseRegistry(Database database,
                                                 String namespace,
                                                 String microserviceName,
                                                 String tenantId, String type) {
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setNamespace(namespace);
        databaseRegistry.setDatabase(database);
        databaseRegistry.setClassifier(getClassifier(namespace, microserviceName, tenantId));
        databaseRegistry.setType(type);
        database.getDatabaseRegistry().add(databaseRegistry);
        return databaseRegistry;
    }

    private SortedMap<String, Object> getClassifier(String namespace, String microserviceName, String tenantId) {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", namespace);
        classifier.put("microserviceName", microserviceName);
        if (tenantId != null && !tenantId.isBlank()) {
            classifier.put("tenantId", tenantId);
            classifier.put("scope", "tenant");
        } else
            classifier.put("scope", "service");
        return classifier;
    }

    private ClassifierDetails getClassifier(ClassifierType classifierType, String namespace, String microserviceName, String tenantId, String namespaceBeforeMap, String tenantIdBeforeMap) {
        assertFalse(namespaceBeforeMap.isBlank());

        ClassifierDetails classifierWrapper = new ClassifierDetails();
        classifierWrapper.setType(classifierType);

        if (namespace != null && !namespace.isBlank()) {
            SortedMap<String, Object> classifier = new TreeMap<>();
            classifier.put("namespace", namespace);
            classifier.put("microserviceName", microserviceName);
            if (tenantId != null && !tenantId.isBlank()) {
                classifier.put("tenantId", tenantId);
                classifier.put("scope", "tenant");
            } else
                classifier.put("scope", "service");
            classifierWrapper.setClassifier(classifier);
        }

        SortedMap<String, Object> classifierBeforeMapping = new TreeMap<>();
        classifierBeforeMapping.put("namespace", namespaceBeforeMap);
        classifierBeforeMapping.put("microserviceName", microserviceName);
        if (tenantId != null && !tenantId.isBlank()) {
            classifierBeforeMapping.put("tenantId", tenantIdBeforeMap);
            classifierBeforeMapping.put("scope", "tenant");
        } else
            classifierBeforeMapping.put("scope", "service");

        classifierWrapper.setClassifierBeforeMapper(classifierBeforeMapping);
        return classifierWrapper;
    }

    private BackupExternalDatabase getBackupExternalDatabase(String name, String type, List<SortedMap<String, Object>> classifiers) {
        BackupExternalDatabase externalDatabase = new BackupExternalDatabase();
        externalDatabase.setId(UUID.randomUUID());
        externalDatabase.setName(name);
        externalDatabase.setType(type);
        externalDatabase.setClassifiers(classifiers);
        return externalDatabase;
    }

    private BackupDatabase getBackupDatabase(String dbName,
                                             List<SortedMap<String, Object>> classifiers,
                                             boolean configurational,
                                             BackupTaskStatus status,
                                             String errorMessage) {
        BackupDatabase backupDatabase = new BackupDatabase();
        backupDatabase.setId(UUID.randomUUID());
        backupDatabase.setName(dbName);
        backupDatabase.setClassifiers(classifiers);
        backupDatabase.setSettings(Map.of("setting", "setting"));
        backupDatabase.setUsers(List.of(new BackupDatabase.User("oldUsername", "admin")));
        backupDatabase.setConfigurational(configurational);
        backupDatabase.setStatus(status);
        backupDatabase.setSize(1);
        backupDatabase.setDuration(1);
        backupDatabase.setPath("path");
        backupDatabase.setErrorMessage(errorMessage);
        return backupDatabase;
    }

    private LogicalBackup getLogicalBackup(String logicalBackupName,
                                           String adapterId,
                                           String type,
                                           List<BackupDatabase> backupDatabases,
                                           BackupTaskStatus status,
                                           String errorMsg
    ) {
        LogicalBackup logicalBackup = new LogicalBackup();
        logicalBackup.setId(UUID.randomUUID());
        logicalBackup.setLogicalBackupName(logicalBackupName);
        logicalBackup.setAdapterId(adapterId);
        logicalBackup.setType(type);
        logicalBackup.setBackupDatabases(backupDatabases);
        logicalBackup.setStatus(status);
        logicalBackup.setErrorMessage(errorMsg);

        backupDatabases.forEach(db -> db.setLogicalBackup(logicalBackup));
        return logicalBackup;
    }

    private Backup getBackup(String name,
                             ExternalDatabaseStrategy strategy,
                             FilterCriteriaEntity filterCriteria,
                             List<LogicalBackup> logicalBackups,
                             List<BackupExternalDatabase> externalDatabases,
                             BackupStatus status,
                             String errorMsg
    ) {
        Backup backup = new Backup();
        backup.setName(name);
        backup.setStorageName(STORAGE_NAME);
        backup.setBlobPath(BLOB_PATH);
        backup.setExternalDatabaseStrategy(strategy);
        backup.setFilterCriteria(filterCriteria);
        backup.setLogicalBackups(logicalBackups);
        backup.setExternalDatabases(externalDatabases);
        backup.setStatus(status);
        backup.setTotal(logicalBackups.stream().mapToInt(db -> db.getBackupDatabases().size()).sum());
        backup.setCompleted((int) logicalBackups.stream()
                .flatMap(db -> db.getBackupDatabases().stream())
                .filter(bd -> BackupTaskStatus.COMPLETED == bd.getStatus())
                .count());
        backup.setSize(logicalBackups.stream()
                .flatMap(db -> db.getBackupDatabases().stream())
                .mapToLong(BackupDatabase::getSize)
                .sum());
        backup.setErrorMessage(errorMsg);

        logicalBackups.forEach(db -> db.setBackup(backup));
        externalDatabases.forEach(db -> db.setBackup(backup));
        return backup;
    }

    private Backup getBackup(String backupName, String namespace) {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", namespace);

        List<BackupDatabase> backupDatabases = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            BackupDatabase backupDatabase = new BackupDatabase();
            backupDatabase.setId(UUID.randomUUID());
            backupDatabase.setName("db" + i);
            backupDatabase.setUsers(List.of(new BackupDatabase.User("oldUsername", "role")));
            backupDatabase.setStatus(BackupTaskStatus.COMPLETED);
            backupDatabase.setPath("path");
            backupDatabase.setClassifiers(List.of(classifier));
            backupDatabases.add(backupDatabase);
        }

        List<LogicalBackup> logicalBackups = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            LogicalBackup logicalBackup = new LogicalBackup();
            logicalBackup.setId(UUID.randomUUID());
            logicalBackup.setLogicalBackupName("logicalBackupName" + i);
            logicalBackup.setStatus(BackupTaskStatus.COMPLETED);
            logicalBackup.setAdapterId(Integer.toString(i));
            logicalBackup.setType("postgresql");
            logicalBackups.add(logicalBackup);
        }

        List<BackupDatabase> first = backupDatabases.subList(0, 2);
        List<BackupDatabase> second = backupDatabases.subList(2, backupDatabases.size());

        LogicalBackup firstLogical = logicalBackups.get(0);
        firstLogical.setBackupDatabases(first);
        first.forEach(db -> db.setLogicalBackup(firstLogical));

        LogicalBackup secondLogical = logicalBackups.get(1);
        secondLogical.setBackupDatabases(second);
        second.forEach(db -> db.setLogicalBackup(secondLogical));

        FilterEntity filter = new FilterEntity();
        filter.setNamespace(List.of(namespace));

        FilterCriteriaEntity criteriaEntity = new FilterCriteriaEntity();
        criteriaEntity.setInclude(List.of(filter));

        Backup backup = new Backup();
        backup.setName(backupName);
        backup.setLogicalBackups(logicalBackups);
        backup.setFilterCriteria(criteriaEntity);
        backup.setBlobPath(BLOB_PATH);
        backup.setStorageName(STORAGE_NAME);
        backup.setExternalDatabaseStrategy(ExternalDatabaseStrategy.INCLUDE);

        logicalBackups.forEach(db -> db.setBackup(backup));
        return backup;
    }

    private Restore getRestore(String restoreName, String namespace) {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", namespace);

        ClassifierDetails classifierMapper = new ClassifierDetails();
        classifierMapper.setClassifierBeforeMapper(classifier);

        List<RestoreDatabase> restoreDatabases = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RestoreDatabase restoreDatabase = new RestoreDatabase();
            restoreDatabase.setId(UUID.randomUUID());
            restoreDatabase.setName("db" + i);
            restoreDatabase.setUsers(List.of(new RestoreDatabase.User("oldUsername", "admin")));
            restoreDatabase.setStatus(RestoreTaskStatus.COMPLETED);
            restoreDatabase.setPath("path");
            restoreDatabase.setClassifiers(List.of(classifierMapper));
            restoreDatabases.add(restoreDatabase);
        }

        List<LogicalRestore> logicalRestores = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            LogicalRestore logicalRestore = new LogicalRestore();
            logicalRestore.setId(UUID.randomUUID());
            logicalRestore.setLogicalRestoreName("logicalRestoreName" + i);
            logicalRestore.setStatus(RestoreTaskStatus.COMPLETED);
            logicalRestore.setAdapterId(Integer.toString(i));
            logicalRestore.setType("postgresql");
            logicalRestores.add(logicalRestore);
        }

        List<RestoreDatabase> first = restoreDatabases.subList(0, 2);
        List<RestoreDatabase> second = restoreDatabases.subList(2, restoreDatabases.size());

        LogicalRestore firstLogical = logicalRestores.get(0);
        firstLogical.setRestoreDatabases(first);
        first.forEach(db -> db.setLogicalRestore(firstLogical));

        LogicalRestore secondLogical = logicalRestores.get(1);
        secondLogical.setRestoreDatabases(second);
        second.forEach(db -> db.setLogicalRestore(secondLogical));

        FilterEntity filter = new FilterEntity();
        filter.setNamespace(List.of(namespace));
        FilterCriteriaEntity criteriaEntity = new FilterCriteriaEntity();
        criteriaEntity.setInclude(List.of(filter));

        Restore restore = new Restore();
        restore.setName(restoreName);
        restore.setLogicalRestores(logicalRestores);
        restore.setFilterCriteria(criteriaEntity);
        restore.setBlobPath(BLOB_PATH);
        restore.setStorageName(STORAGE_NAME);
        restore.setExternalDatabaseStrategy(ExternalDatabaseStrategy.INCLUDE);

        logicalRestores.forEach(db -> db.setRestore(restore));
        return restore;
    }

    private Map<Database, List<DatabaseRegistry>> getDatabase(Map<String, String> dbNameToAdapter, String namespace) {
        List<Database> databases = new ArrayList<>();
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", namespace);

        DbState dbState = new DbState();
        dbState.setId(UUID.randomUUID());
        dbState.setState(AbstractDbState.DatabaseStateStatus.CREATED);
        dbState.setDatabaseState(AbstractDbState.DatabaseStateStatus.CREATED);
        dbState.setDescription("descripton");
        dbState.setPodName("podName");

        dbNameToAdapter.forEach((dbName, adapterId) -> {
            Database database = new Database();
            database.setAdapterId(adapterId);
            database.setName(dbName);
            database.setDbState(dbState);

            DatabaseRegistry databaseRegistry = new DatabaseRegistry();
            databaseRegistry.setNamespace(namespace);
            databaseRegistry.setDatabase(database);
            databaseRegistry.setClassifier(classifier);
            database.setDatabaseRegistry(new ArrayList<>());
            database.getDatabaseRegistry().add(databaseRegistry);
            database.setSettings(new HashMap<>());
            databaseRegistry.setConnectionProperties(List.of());
            databases.add(database);
        });
        return databases.stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        Database::getDatabaseRegistry
                ));
    }

    private RestoreDatabase getRestoreDatabase(BackupDatabase backupDatabase,
                                               String dbName,
                                               List<ClassifierDetails> classifiers,
                                               Map<String, Object> settings,
                                               String bgVersion,
                                               RestoreTaskStatus status,
                                               long duration,
                                               String errorMessage) {
        RestoreDatabase db = new RestoreDatabase();
        db.setId(UUID.randomUUID());
        db.setBackupDatabase(backupDatabase);
        db.setName(dbName);
        db.setClassifiers(classifiers);
        db.setSettings(settings);
        db.setUsers(List.of(new RestoreDatabase.User("oldUsername", "admin")));
        db.setBgVersion(bgVersion);
        db.setStatus(status);
        db.setDuration(duration);
        db.setErrorMessage(errorMessage);
        return db;
    }

    private LogicalRestore getLogicalRestore(String logicalRestoreName,
                                             String adapterId,
                                             String type,
                                             List<RestoreDatabase> restoreDatabases,
                                             RestoreTaskStatus status,
                                             String errorMsg) {
        LogicalRestore logicalRestore = new LogicalRestore();
        logicalRestore.setId(UUID.randomUUID());
        logicalRestore.setLogicalRestoreName(logicalRestoreName);
        logicalRestore.setAdapterId(adapterId);
        logicalRestore.setType(type);
        logicalRestore.setRestoreDatabases(restoreDatabases);
        logicalRestore.setStatus(status);
        logicalRestore.setErrorMessage(errorMsg);

        restoreDatabases.forEach(db -> db.setLogicalRestore(logicalRestore));
        return logicalRestore;
    }

    private Restore getRestore(Backup backup,
                               String name,
                               FilterCriteriaEntity filterCriteria,
                               Restore.MappingEntity mapping,
                               List<LogicalRestore> logicalRestores,
                               ExternalDatabaseStrategy strategy,
                               List<RestoreExternalDatabase> externalDatabases,
                               RestoreStatus status,
                               String errorMsg) {

        Restore restore = new Restore();
        restore.setName(name);
        restore.setBackup(backup);
        restore.setStorageName(STORAGE_NAME);
        restore.setBlobPath(BLOB_PATH);
        restore.setFilterCriteria(filterCriteria);
        restore.setMapping(mapping);
        restore.setLogicalRestores(logicalRestores);
        restore.setExternalDatabaseStrategy(strategy);
        restore.setExternalDatabases(externalDatabases);
        restore.setStatus(status);
        restore.setErrorMessage(errorMsg);

        logicalRestores.forEach(db -> db.setRestore(restore));
        externalDatabases.forEach(db -> db.setRestore(restore));
        return restore;
    }

    private RestoreExternalDatabase getRestoreExternalDb(
            String name,
            String type,
            List<ClassifierDetails> classifiers
    ) {
        RestoreExternalDatabase db = new RestoreExternalDatabase();
        db.setId(UUID.randomUUID());
        db.setName(name);
        db.setType(type);
        db.setClassifiers(classifiers);
        return db;
    }

    private BackupRequest getBackupRequest(String backupName,
                                           List<String> namespaces,
                                           ExternalDatabaseStrategy strategy,
                                           boolean ignoreNotBackupableDatabases
    ) {
        Filter filter = new Filter();
        filter.setNamespace(namespaces);

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setInclude(List.of(filter));

        BackupRequest dto = new BackupRequest();
        dto.setFilterCriteria(filterCriteria);
        dto.setBackupName(backupName);
        dto.setExternalDatabaseStrategy(strategy);
        dto.setBlobPath(BLOB_PATH);
        dto.setStorageName(STORAGE_NAME);
        dto.setIgnoreNotBackupableDatabases(ignoreNotBackupableDatabases);
        return dto;
    }

    private RestoreRequest getRestoreRequest(
            String restoreName,
            List<String> namespaces,
            ExternalDatabaseStrategy strategy,
            Map<String, String> namespaceMapping,
            Map<String, String> tenantMapping
    ) {
        Filter filter = new Filter();
        filter.setNamespace(namespaces);

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setInclude(List.of(filter));

        Mapping mapping = new Mapping();
        mapping.setNamespaces(namespaceMapping);
        mapping.setTenants(tenantMapping);

        RestoreRequest restoreRequest = new RestoreRequest();
        restoreRequest.setRestoreName(restoreName);
        restoreRequest.setStorageName(STORAGE_NAME);
        restoreRequest.setBlobPath(BLOB_PATH);
        restoreRequest.setFilterCriteria(filterCriteria);
        restoreRequest.setMapping(mapping);
        restoreRequest.setExternalDatabaseStrategy(strategy);
        return restoreRequest;
    }

    private BackupResponse getBackupResponse(String backupName, String namespace) {
        SortedMap<String, Object> sortedMap = new TreeMap<>();
        sortedMap.put("key-first", Map.of("inner-key", "inner-value"));
        sortedMap.put("key-second", Map.of("inner-key", "inner-value"));

        BackupDatabaseResponse backupDatabaseResponse = new BackupDatabaseResponse(
                UUID.randomUUID(),
                "backup-database",
                List.of(sortedMap),
                Map.of("settings-key", "settings-value"),
                List.of(BackupDatabaseResponse.User.builder()
                        .name("name")
                        .role("role")
                        .build()),
                true,
                BackupTaskStatus.COMPLETED,
                1,
                1,
                "path",
                null,
                Instant.now()
        );

        LogicalBackupResponse logicalBackupResponse = new LogicalBackupResponse(
                UUID.randomUUID(),
                "logicalBackupName",
                "adapterID",
                "type",
                BackupTaskStatus.COMPLETED,
                null,
                null,
                null,
                List.of(backupDatabaseResponse)
        );

        Filter filter = new Filter();
        filter.setNamespace(List.of(namespace));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setInclude(List.of(filter));

        SortedMap<String, Object> map = new TreeMap<>();
        map.put("key", "value");

        BackupExternalDatabaseResponse backupExternalDatabase = new BackupExternalDatabaseResponse();
        backupExternalDatabase.setId(UUID.randomUUID());
        backupExternalDatabase.setName("Name");
        backupExternalDatabase.setType("postgresql");
        backupExternalDatabase.setClassifiers(List.of(map));

        BackupResponse backupResponse = new BackupResponse();
        backupResponse.setBackupName(backupName);
        backupResponse.setStatus(BackupStatus.COMPLETED);
        backupResponse.setTotal(1);
        backupResponse.setCompleted(1);
        backupResponse.setSize(1L);
        backupResponse.setErrorMessage(null);
        backupResponse.setLogicalBackups(List.of(logicalBackupResponse));
        backupResponse.setBlobPath("BlobPath");
        backupResponse.setStorageName("storageName");
        backupResponse.setFilterCriteria(filterCriteria);
        backupResponse.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);
        backupResponse.setIgnoreNotBackupableDatabases(true);
        backupResponse.setExternalDatabases(List.of(backupExternalDatabase));

        return backupResponse;
    }

    private FilterCriteriaEntity getFilterCriteriaEntity(List<String> namespaces) {
        FilterEntity filter = new FilterEntity();
        filter.setNamespace(namespaces);

        FilterCriteriaEntity filterCriteria = new FilterCriteriaEntity();
        filterCriteria.setInclude(List.of(filter));
        return filterCriteria;
    }
}
