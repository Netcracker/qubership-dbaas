package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.dto.EnsuredUser;
import com.netcracker.cloud.dbaas.dto.Source;
import com.netcracker.cloud.dbaas.dto.backupV2.*;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.BackupAdapterRequest;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.BackupDatabaseDelegate;
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
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.netcracker.cloud.dbaas.service.DBaaService.MARKED_FOR_DROP;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Slf4j
@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class DbBackupV2ServiceTest {

    @InjectMock
    private DatabaseDbaasRepository databaseDbaasRepository;

    @Inject
    private DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @Inject
    private DBaaService dBaaService;

    @InjectMock
    private PasswordEncryption encryption;

    @Inject
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
    private LockProvider lockProvider;

    @Inject
    @Named("process-orchestrator")
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        restoreDatabaseRepository.deleteAll();
        logicalRestoreDatabaseRepository.deleteAll();
        restoreRepository.deleteAll();
        backupDatabaseRepository.deleteAll();
        logicalBackupRepository.deleteAll();
        backupExternalDatabaseRepository.deleteAll();
        backupRepository.deleteAll();
    }


    @Test
    void backup_shouldWorkCorrectly_FailStatusTest() {
        String namespace = "test-namespace";
        String adapterOneName = "1";
        String adapterTwoName = "2";
        String dbName = "db-name";
        String logicalBackupNameOne = "logicalBackupName1";
        String logicalBackupNameTwo = "logicalBackupName2";
        String backupName = "backup123";

        BackupRequest backupRequest = createBackupRequest(backupName, namespace);

        List<Database> registries = getDatabases(dbName, namespace);

        registries.forEach(db -> db.setBgVersion("bgVersion"));

        LogicalBackupAdapterResponse adapterResponseFirst = new LogicalBackupAdapterResponse(
                "failed",
                null,
                logicalBackupNameOne,
                LocalDateTime.now(),
                LocalDateTime.now(),
                "storageName",
                "blobPath",
                List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .status("failed")
                                .size(1L)
                                .databaseName(dbName + 0)
                                .build(),
                        LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .status("failed")
                                .size(1L)
                                .databaseName(dbName + 2)
                                .build()
                )
        );
        LogicalBackupAdapterResponse adapterResponseSecond = new LogicalBackupAdapterResponse(
                "failed",
                null,
                logicalBackupNameTwo,
                LocalDateTime.now(),
                LocalDateTime.now(),
                "storageName",
                "blobPath",
                List.of(
                        LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .status("completed")
                                .size(1L)
                                .databaseName(dbName + 1)
                                .build(),

                        LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .status("completed")
                                .size(1L)
                                .databaseName(dbName + 3)
                                .build()
                )
        );
        DbaasAdapter adapterOne = mock(DbaasAdapter.class);
        DbaasAdapter adapterTwo = mock(DbaasAdapter.class);

        when(databaseDbaasRepository.findAnyLogDbTypeByNamespace(namespace))
                .thenReturn(registries);

        when(physicalDatabasesService.getAdapterById(adapterOneName))
                .thenReturn(adapterOne);
        when(physicalDatabasesService.getAdapterById(adapterTwoName))
                .thenReturn(adapterTwo);

        when(adapterOne.type()).thenReturn("postgresql");
        when(adapterTwo.type()).thenReturn("mongodb");

        when(adapterOne.backupV2(any()))
                .thenReturn(adapterResponseFirst);
        when(adapterOne.isBackupRestoreSupported())
                .thenReturn(true);
        when(adapterTwo.backupV2(any()))
                .thenReturn(adapterResponseSecond);
        when(adapterTwo.isBackupRestoreSupported())
                .thenReturn(true);

        BackupResponse response = dbBackupV2Service.backup(backupRequest, false);

        assertEquals(backupName, response.getBackupName());
        assertEquals(BackupStatus.FAILED, response.getStatus());
        assertEquals("storageName", response.getStorageName());
        assertEquals("blobPath", response.getBlobPath());
        assertEquals(4, response.getTotal());
        assertEquals(2, response.getCompleted());
        assertEquals(4, response.getSize());

        List<LogicalBackup> logicalBackups = logicalBackupRepository.getByBackupName(backupName);

        LogicalBackup logicalBackupFirst = logicalBackups.stream()
                .filter(db -> db.getLogicalBackupName().equals(logicalBackupNameOne))
                .findFirst().get();

        assertEquals(2, logicalBackups.size());
        assertEquals(logicalBackupNameOne, logicalBackupFirst.getLogicalBackupName());
        assertEquals(BackupTaskStatus.FAILED, logicalBackupFirst.getStatus());
        assertNull(logicalBackupFirst.getErrorMessage());

        assertNotNull(logicalBackupFirst.getBackupDatabases());
        assertEquals(2, logicalBackupFirst.getBackupDatabases().size());

        BackupDatabase db0 = logicalBackupFirst.getBackupDatabases().stream()
                .filter(db -> db.getName().equals(dbName + 0))
                .findFirst().get();
        assertNotNull(db0);
        assertEquals(BackupTaskStatus.FAILED, db0.getStatus());
        assertTrue(db0.isConfigurational());

        Mockito.verify(adapterOne, times(1)).backupV2(any());
        Mockito.verify(adapterTwo, times(1)).backupV2(any());

    }

    // TODO
    @Test
    void backup_externalStrategySkip_shouldExcludeExternalDatabasesFromMetadataAndLogicalBackups() {
        String namespace = "test-namespace";
        String adapterOneName = "1";
        String adapterTwoName = "2";
        String dbName = "db-name";
        String logicalBackupNameOne = "logicalBackupName1";
        String logicalBackupNameTwo = "logicalBackupName2";
        String backupName = "backup123";

        BackupRequest backupRequest = createBackupRequest(backupName, namespace);
        backupRequest.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);
        List<Database> registries = getDatabases(dbName, namespace);

        // Mark one of the databases as externally managed
        registries.stream()
                .filter(db -> db.getName().equals(dbName + 0))
                .findAny()
                .ifPresent(db -> db.setExternallyManageable(true));

        registries.forEach(db -> db.setBgVersion("bgVersion"));

        LogicalBackupAdapterResponse adapterResponseFirst = new LogicalBackupAdapterResponse(
                "failed",
                null,
                logicalBackupNameOne,
                LocalDateTime.now(),
                LocalDateTime.now(),
                "storageName",
                "blobPath",
                List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                        .status("failed")
                        .size(1L)
                        .databaseName(dbName + 2)
                        .build())
        );

        LogicalBackupAdapterResponse adapterResponseSecond = new LogicalBackupAdapterResponse(
                "failed",
                null,
                logicalBackupNameTwo,
                LocalDateTime.now(),
                LocalDateTime.now(),
                "storageName",
                "blobPath",
                List.of(
                        LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .status("completed")
                                .size(1L)
                                .databaseName(dbName + 1)
                                .build(),
                        LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .status("completed")
                                .size(1L)
                                .databaseName(dbName + 3)
                                .build())
        );

        DbaasAdapter adapterOne = mock(DbaasAdapter.class);
        DbaasAdapter adapterTwo = mock(DbaasAdapter.class);

        when(databaseDbaasRepository.findAnyLogDbTypeByNamespace(namespace))
                .thenReturn(registries);
        when(physicalDatabasesService.getAdapterById(adapterOneName))
                .thenReturn(adapterOne);
        when(physicalDatabasesService.getAdapterById(adapterTwoName))
                .thenReturn(adapterTwo);

        when(adapterOne.type()).thenReturn("postgresql");
        when(adapterTwo.type()).thenReturn("mongodb");

        when(adapterOne.backupV2(any())).thenReturn(adapterResponseFirst);
        when(adapterOne.isBackupRestoreSupported()).thenReturn(true);
        when(adapterTwo.backupV2(any())).thenReturn(adapterResponseSecond);
        when(adapterTwo.isBackupRestoreSupported()).thenReturn(true);

        BackupResponse response = dbBackupV2Service.backup(backupRequest, false);

        // Verify that metadata contains information for 3 databases
        // and does not include external databases when strategy = SKIP
        assertEquals(3, response.getTotal());
        assertTrue(response.getExternalDatabases().isEmpty());

        List<LogicalBackup> logicalBackups = logicalBackupRepository.getByBackupName(backupName);

        LogicalBackup logicalBackupFirst = logicalBackups.stream()
                .filter(db -> db.getLogicalBackupName().equals(logicalBackupNameOne))
                .findFirst()
                .orElse(null);
        LogicalBackup logicalBackupSecond = logicalBackups.stream()
                .filter(db -> db.getLogicalBackupName().equals(logicalBackupNameTwo))
                .findFirst()
                .orElse(null);

        assertNotNull(logicalBackupFirst);
        assertNotNull(logicalBackupSecond);
        assertNotNull(logicalBackupFirst.getBackupDatabases());

        // Verify that the first logical backup contains only one database
        assertEquals(1, logicalBackupFirst.getBackupDatabases().size());

        // Verify that the external database (dbName0) was excluded
        BackupDatabase db0 = logicalBackupFirst.getBackupDatabases().stream()
                .filter(db -> db.getName().equals(dbName + 0))
                .findFirst()
                .orElse(null);
        assertNull(db0);

        db0 = logicalBackupSecond.getBackupDatabases().stream()
                .filter(db -> db.getName().equals(dbName + 0))
                .findFirst()
                .orElse(null);
        assertNull(db0);

        Backup backup = backupRepository.findById(backupName);
        assertTrue(backup.getExternalDatabases().isEmpty());

        Mockito.verify(adapterOne, times(1)).backupV2(any());
        Mockito.verify(adapterTwo, times(1)).backupV2(any());
    }


    @Test
    void getAllDbByFilter_shouldFilterRegistriesAndCreateCopy() {
        String dbName = "dbName";
        String otherNamespace = "other-namespace";
        String namespace = "namespace";

        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", "namespace");

        SortedMap<String, Object> markedClassifier = new TreeMap<>();
        markedClassifier.put("namespace", "namespace");
        markedClassifier.put(MARKED_FOR_DROP, MARKED_FOR_DROP);

        DbState dbState = new DbState();
        dbState.setId(UUID.randomUUID());
        dbState.setState(AbstractDbState.DatabaseStateStatus.CREATED);
        dbState.setDatabaseState(AbstractDbState.DatabaseStateStatus.CREATED);
        dbState.setDescription("descripton");
        dbState.setPodName("podName");

        DbState wrongDbState = new DbState();
        wrongDbState.setId(UUID.randomUUID());
        wrongDbState.setState(AbstractDbState.DatabaseStateStatus.DELETING);
        wrongDbState.setDatabaseState(AbstractDbState.DatabaseStateStatus.DELETING);
        wrongDbState.setDescription("descripton");
        wrongDbState.setPodName("podName");

        Database originalDb = new Database();
        originalDb.setNamespace(namespace);
        originalDb.setName(dbName);
        originalDb.setConnectionProperties(new ArrayList<>());
        originalDb.setResources(new ArrayList<>());

        DatabaseRegistry registry1 = new DatabaseRegistry();
        registry1.setNamespace(namespace);
        registry1.setClassifier(classifier);
        registry1.setDatabase(originalDb);
        registry1.setDbState(dbState);

        DatabaseRegistry registry2 = new DatabaseRegistry();
        registry2.setNamespace(otherNamespace);
        registry2.setClassifier(classifier);
        registry2.setDatabase(originalDb);


        originalDb.setDatabaseRegistry(List.of(registry1, registry2));

        Database originalDb2 = new Database();
        originalDb2.setNamespace(namespace);
        originalDb2.setName(dbName);
        originalDb2.setConnectionProperties(new ArrayList<>());
        originalDb2.setResources(new ArrayList<>());

        DatabaseRegistry registry5 = new DatabaseRegistry();
        registry5.setNamespace(namespace);
        registry5.setClassifier(classifier);
        registry5.setDatabase(originalDb2);
        registry5.setDbState(wrongDbState);
        registry5.setClassifier(markedClassifier);
        registry5.setMarkedForDrop(true);

        originalDb2.setDatabaseRegistry(List.of(registry5));

        when(databaseDbaasRepository.findAnyLogDbTypeByNamespace(namespace))
                .thenReturn(List.of(originalDb, originalDb2));

        Filter filter = new Filter();
        filter.setNamespace(List.of(namespace));
        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setFilter(List.of(filter));

        Map<Database, List<DatabaseRegistry>> result = dbBackupV2Service.getAllDbByFilter(filterCriteria);

        // TODO write assertions
//        assertEquals(1, result.size());
//        Database copy = result.getFirst();
//
//        assertNotSame(copy, originalDb);
//
//        assertEquals(1, copy.getDatabaseRegistry().size());
//        assertEquals(namespace, copy.getDatabaseRegistry().getFirst().getNamespace());
//        assertEquals(dbName, copy.getName());
//
//        assertSame(copy, copy.getDatabaseRegistry().getFirst().getDatabase());
    }

    @Test
    void getAllDbByFilter_whenPassedSeveralNamespaces_returnFunctionalityNotImplemented() {
        Filter filter = new Filter();
        filter.setNamespace(List.of("namespace1", "namespace2"));
        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setFilter(List.of(filter));

        FunctionalityNotImplemented ex = assertThrows(FunctionalityNotImplemented.class,
                () -> dbBackupV2Service.getAllDbByFilter(filterCriteria));

        assertEquals("The requested functionality is not implemented: backup by several namespace", ex.getDetail());
    }

    @Test
    void getAllDbByFilter_whenFilterByMicroservice_returnFunctionalityNotImplemented() {
        Filter filter = new Filter();
        filter.setMicroserviceName(List.of("microserviceName"));
        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setFilter(List.of(filter));

        FunctionalityNotImplemented ex = assertThrows(FunctionalityNotImplemented.class,
                () -> dbBackupV2Service.getAllDbByFilter(filterCriteria));

        assertEquals("The requested functionality is not implemented: backup by microservice", ex.getDetail());
    }

    @Test
    void backup_shouldReturnBadRequest_namespaceReturnEmptyList() {
        BackupRequest backupRequest = createBackupRequest("test-backup", "test-namespace");
        assertThrows(DbNotFoundException.class, () ->
                dbBackupV2Service.backup(backupRequest, false));
    }

    @Test
    void backup_backupNameAlreadyExist() {
        String namespace = "test-namespace";
        String backupName = "test-backup";

        BackupRequest backupRequest = createBackupRequest(backupName, namespace);

        Backup backup = createBackup(backupName, List.of());

        Filter filter = new Filter();
        filter.setNamespace(List.of(namespace));
        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setFilter(List.of(filter));
        backupRepository.save(backup);

        when(databaseDbaasRepository.findAnyLogDbTypeByNamespace(namespace))
                .thenReturn(List.of(new Database()));

        assertThrows(ResourceAlreadyExistsException.class, () ->
                dbBackupV2Service.backup(backupRequest, false));
    }

    @Test
    void validateAndFilterDatabasesForBackup() {
        String databaseFirstName = "databaseFirst";
        Database databaseFirst = new Database();
        databaseFirst.setName(databaseFirstName);
        databaseFirst.setConnectionProperties(List.of(Map.of()));
        databaseFirst.setSettings(Map.of());
        databaseFirst.setResources(List.of());
        databaseFirst.setExternallyManageable(false);
        databaseFirst.setAdapterId("1");
        databaseFirst.setConnectionProperties(List.of(Map.of("username", "user", "role", "role")));

        SortedMap<String, Object> classifierFirst = new TreeMap<>();
        classifierFirst.put("microserviceName", databaseFirstName);
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setType("postgresql");
        databaseRegistry.setClassifier(classifierFirst);
        databaseRegistry.setDatabase(databaseFirst);
        databaseFirst.setDatabaseRegistry(List.of(databaseRegistry));
        databaseFirst.setSettings(Map.of("key", "value"));

        String databaseSecondName = "databaseSecond";
        Database databaseSecond = new Database();
        databaseSecond.setName(databaseSecondName);
        databaseSecond.setConnectionProperties(List.of(Map.of()));
        databaseSecond.setSettings(Map.of());
        databaseSecond.setResources(List.of());
        databaseSecond.setExternallyManageable(false);
        databaseSecond.setBackupDisabled(true);
        databaseSecond.setAdapterId("2");
        databaseSecond.setConnectionProperties(List.of(Map.of("username", "user", "role", "role")));

        SortedMap<String, Object> classifierSecond = new TreeMap<>();
        classifierSecond.put("microserviceName", databaseSecondName);
        DatabaseRegistry databaseRegistrySecond = new DatabaseRegistry();
        databaseRegistrySecond.setType("postgresql");
        databaseRegistrySecond.setClassifier(classifierSecond);
        databaseRegistrySecond.setDatabase(databaseSecond);
        databaseSecond.setDatabaseRegistry(List.of(databaseRegistrySecond));
        databaseSecond.setSettings(Map.of("key", "value"));

        String databaseThirdName = "databaseThird";
        Database databaseThird = new Database();
        databaseThird.setName(databaseThirdName);
        databaseThird.setSettings(Map.of());
        databaseThird.setResources(List.of());
        databaseThird.setExternallyManageable(true);
        databaseThird.setAdapterId(null);
        databaseThird.setConnectionProperties(List.of(Map.of("username", "user", "role", "role")));

        SortedMap<String, Object> classifierThird = new TreeMap<>();
        classifierThird.put("microserviceName", databaseThirdName);
        DatabaseRegistry databaseRegistryThird = new DatabaseRegistry();
        databaseRegistryThird.setType("postgresql");
        databaseRegistryThird.setClassifier(classifierThird);
        databaseRegistryThird.setDatabase(databaseThird);
        databaseThird.setDatabaseRegistry(List.of(databaseRegistryThird));
        databaseThird.setSettings(Map.of("key", "value"));

        String databaseFourthName = "databaseFourth";
        Database databaseFourth = new Database();
        databaseFourth.setName(databaseFourthName);
        databaseFourth.setSettings(Map.of());
        databaseFourth.setResources(List.of());
        databaseFourth.setAdapterId("3");
        databaseFourth.setConnectionProperties(List.of(Map.of("username", "user", "role", "role")));

        SortedMap<String, Object> classifierFourth = new TreeMap<>();
        classifierThird.put("microserviceName", databaseFourthName);
        DatabaseRegistry databaseRegistryFourth = new DatabaseRegistry();
        databaseRegistryFourth.setType("postgresql");
        databaseRegistryFourth.setClassifier(classifierFourth);
        databaseRegistryFourth.setDatabase(databaseFourth);
        databaseFourth.setDatabaseRegistry(List.of(databaseRegistryFourth));
        databaseFourth.setSettings(Map.of("key", "value"));

        Map<Database, List<DatabaseRegistry>> databaseList = Stream.of(databaseFirst, databaseSecond, databaseThird, databaseFourth)
                .collect(Collectors.toMap(
                        Function.identity(),
                        Database::getDatabaseRegistry
                ));

        DbaasAdapter adapter1 = Mockito.mock(DbaasAdapter.class);
        when(adapter1.isBackupRestoreSupported())
                .thenReturn(false);
        when(physicalDatabasesService.getAdapterById("1"))
                .thenReturn(adapter1);

        DbaasAdapter adapter2 = Mockito.mock(DbaasAdapter.class);
        when(adapter2.isBackupRestoreSupported())
                .thenReturn(true);
        when(physicalDatabasesService.getAdapterById("3"))
                .thenReturn(adapter2);

        Map<Database, List<DatabaseRegistry>> filteredDatabases = dbBackupV2Service.validateAndFilterDatabasesForBackup(databaseList,
                true,
                ExternalDatabaseStrategy.INCLUDE
        );
        // TODO write assertions
//        assertNotNull(filteredDatabases);
//        assertEquals(2, filteredDatabases.size());
//
//        Database internalDatabase = filteredDatabases.stream()
//                .filter(db -> databaseFourthName.equals(db.getName()))
//                .findFirst().get();
//
//        assertEquals(databaseFourth, internalDatabase);
//
//        Database externalDatabase = filteredDatabases.stream()
//                .filter(db -> databaseThirdName.equals(db.getName()))
//                .findFirst().get();
//        assertEquals(databaseThird, externalDatabase);
    }

    @Test
    void validateAndFilterDatabasesForBackup_whenStrategyFail() {
        String databaseThirdName = "databaseThird";
        Database databaseThird = new Database();
        databaseThird.setName(databaseThirdName);
        databaseThird.setSettings(Map.of());
        databaseThird.setResources(List.of());
        databaseThird.setExternallyManageable(true);
        databaseThird.setAdapterId(null);
        databaseThird.setConnectionProperties(List.of(Map.of("username", "user", "role", "role")));

        SortedMap<String, Object> classifierThird = new TreeMap<>();
        classifierThird.put("microserviceName", databaseThirdName);
        DatabaseRegistry databaseRegistryThird = new DatabaseRegistry();
        databaseRegistryThird.setType("postgresql");
        databaseRegistryThird.setClassifier(classifierThird);
        databaseThird.setDatabaseRegistry(List.of(databaseRegistryThird));
        databaseThird.setSettings(Map.of("key", "value"));

        DatabaseBackupNotSupportedException ex = assertThrows(DatabaseBackupNotSupportedException.class,
                () -> dbBackupV2Service.validateAndFilterDatabasesForBackup(
                        Map.of(databaseThird, databaseThird.getDatabaseRegistry()),
                        false,
                        ExternalDatabaseStrategy.FAIL
                ));

        assertTrue(ex.getMessage().contains("External databases not allowed by strategy=FAIL:"));
    }

    @Test
    void validateAndFilterDatabasesForBackup_whenStrategyFailAndIgnoreBackupableDbTrue() {
        String databaseFirstName = "databaseFirst";
        Database databaseFirst = new Database();
        databaseFirst.setName(databaseFirstName);
        databaseFirst.setConnectionProperties(List.of(Map.of()));
        databaseFirst.setSettings(Map.of());
        databaseFirst.setResources(List.of());
        databaseFirst.setExternallyManageable(false);
        databaseFirst.setAdapterId("1");
        databaseFirst.setConnectionProperties(List.of(Map.of("username", "user", "role", "role")));

        SortedMap<String, Object> classifierFirst = new TreeMap<>();
        classifierFirst.put("microserviceName", databaseFirstName);
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setType("postgresql");
        databaseRegistry.setClassifier(classifierFirst);
        databaseFirst.setDatabaseRegistry(List.of(databaseRegistry));
        databaseFirst.setSettings(Map.of("key", "value"));

        String databaseSecondName = "databaseSecond";
        Database databaseSecond = new Database();
        databaseSecond.setName(databaseSecondName);
        databaseSecond.setConnectionProperties(List.of(Map.of()));
        databaseSecond.setSettings(Map.of());
        databaseSecond.setResources(List.of());
        databaseSecond.setExternallyManageable(false);
        databaseSecond.setBackupDisabled(true);
        databaseSecond.setAdapterId("2");
        databaseSecond.setConnectionProperties(List.of(Map.of("username", "user", "role", "role")));

        SortedMap<String, Object> classifierSecond = new TreeMap<>();
        classifierSecond.put("microserviceName", databaseSecondName);
        DatabaseRegistry databaseRegistrySecond = new DatabaseRegistry();
        databaseRegistrySecond.setType("postgresql");
        databaseRegistrySecond.setClassifier(classifierSecond);
        databaseSecond.setDatabaseRegistry(List.of(databaseRegistrySecond));
        databaseSecond.setSettings(Map.of("key", "value"));

        String databaseThirdName = "databaseThird";
        Database databaseThird = new Database();
        databaseThird.setName(databaseThirdName);
        databaseThird.setSettings(Map.of());
        databaseThird.setResources(List.of());
        databaseThird.setExternallyManageable(true);
        databaseThird.setAdapterId(null);
        databaseThird.setConnectionProperties(List.of(Map.of("username", "user", "role", "role")));

        SortedMap<String, Object> classifierThird = new TreeMap<>();
        classifierThird.put("microserviceName", databaseThirdName);
        DatabaseRegistry databaseRegistryThird = new DatabaseRegistry();
        databaseRegistryThird.setType("postgresql");
        databaseRegistryThird.setClassifier(classifierThird);
        databaseThird.setDatabaseRegistry(List.of(databaseRegistryThird));
        databaseThird.setSettings(Map.of("key", "value"));

        String databaseFourthName = "databaseFourth";
        Database databaseFourth = new Database();
        databaseFourth.setName(databaseFourthName);
        databaseFourth.setSettings(Map.of());
        databaseFourth.setResources(List.of());
        databaseFourth.setAdapterId("3");
        databaseFourth.setConnectionProperties(List.of(Map.of("username", "user", "role", "role")));

        SortedMap<String, Object> classifierFourth = new TreeMap<>();
        classifierThird.put("microserviceName", databaseFourthName);
        DatabaseRegistry databaseRegistryFourth = new DatabaseRegistry();
        databaseRegistryFourth.setType("postgresql");
        databaseRegistryFourth.setClassifier(classifierFourth);
        databaseFourth.setDatabaseRegistry(List.of(databaseRegistryFourth));
        databaseFourth.setSettings(Map.of("key", "value"));

        Map<Database, List<DatabaseRegistry>> databaseList = Stream.of(databaseFirst, databaseSecond, databaseThird, databaseFourth)
                .collect(Collectors.toMap(
                        Function.identity(),
                        Database::getDatabaseRegistry
                ));

        DbaasAdapter adapter1 = Mockito.mock(DbaasAdapter.class);
        when(adapter1.isBackupRestoreSupported())
                .thenReturn(false);
        when(physicalDatabasesService.getAdapterById("1"))
                .thenReturn(adapter1);

        DbaasAdapter adapter2 = Mockito.mock(DbaasAdapter.class);
        when(adapter2.isBackupRestoreSupported())
                .thenReturn(true);
        when(physicalDatabasesService.getAdapterById("3"))
                .thenReturn(adapter2);

        Map<Database, List<DatabaseRegistry>> filteredDatabases = dbBackupV2Service.validateAndFilterDatabasesForBackup(databaseList,
                true,
                ExternalDatabaseStrategy.SKIP
        );
        //TODO write assertions
//        assertNotNull(filteredDatabases);
//        assertEquals(1, filteredDatabases.size());
//
//        Database internalDatabase = filteredDatabases.stream()
//                .filter(db -> databaseFourthName.equals(db.getName()))
//                .findFirst().get();
//
//        assertEquals(databaseFourth, internalDatabase);
    }

    @Test
    void validateAndFilterDatabasesForBackup_whenIgnoreBackupableDbFalse() {
        String databaseFirstName = "databaseFirst";
        Database databaseFirst = new Database();
        databaseFirst.setName(databaseFirstName);
        databaseFirst.setConnectionProperties(List.of(Map.of()));
        databaseFirst.setSettings(Map.of());
        databaseFirst.setResources(List.of());
        databaseFirst.setExternallyManageable(false);
        databaseFirst.setAdapterId("1");
        databaseFirst.setConnectionProperties(List.of(Map.of("username", "user", "role", "role")));

        SortedMap<String, Object> classifierFirst = new TreeMap<>();
        classifierFirst.put("microserviceName", databaseFirstName);
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setType("postgresql");
        databaseRegistry.setClassifier(classifierFirst);
        databaseFirst.setDatabaseRegistry(List.of(databaseRegistry));
        databaseFirst.setSettings(Map.of("key", "value"));

        DbaasAdapter adapter1 = Mockito.mock(DbaasAdapter.class);
        when(adapter1.isBackupRestoreSupported())
                .thenReturn(false);
        when(physicalDatabasesService.getAdapterById("1"))
                .thenReturn(adapter1);

        DatabaseBackupNotSupportedException ex = assertThrows(DatabaseBackupNotSupportedException.class,
                () -> dbBackupV2Service.validateAndFilterDatabasesForBackup(
                        Map.of(databaseFirst, databaseFirst.getDatabaseRegistry()),
                        false,
                        ExternalDatabaseStrategy.FAIL
                ));

        assertTrue(ex.getMessage().contains("Backup operation unsupported for databases:"));
    }

    @Test
    void startBackup_whenValidInput_thenBackupIsInitialized() {
        //when
        int logicalBackupCount = 2;
        List<LogicalBackup> logicalBackups = generateLogicalBackups(logicalBackupCount);

        Backup backup = createBackup("name", logicalBackups);
        logicalBackups.forEach(lb -> lb.setBackup(backup));
        DbaasAdapter adapter = mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAdapterById(any()))
                .thenReturn(adapter);

        List<LogicalBackupAdapterResponse> adapterResponses = List.of(
                LogicalBackupAdapterResponse.builder()
                        .status("completed")
                        .databases(List.of(
                                LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                        .databaseName("db1-0")
                                        .status("completed")
                                        .build()
                        ))
                        .build(),
                LogicalBackupAdapterResponse.builder()
                        .status("completed")
                        .databases(List.of(
                                LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                        .databaseName("db2-0")
                                        .status("completed")
                                        .build()
                        ))
                        .build()
        );

        for (int i = 0; i < logicalBackupCount; i++) {
            Backup currBackup = logicalBackups.get(i).getBackup();
            String storageName = currBackup.getStorageName();
            String blobPath = currBackup.getBlobPath();

            List<Map<String, String>> dbNames = logicalBackups.get(i).getBackupDatabases().stream()
                    .map(db -> Map.of("databaseName", db.getName()))
                    .toList();
            adapterResponses.get(i).setBackupId("backup" + logicalBackups.get(i).getAdapterId());
            when(adapter.backupV2(new BackupAdapterRequest(storageName, blobPath, dbNames)))
                    .thenReturn(adapterResponses.get(i));
        }

        //then
        dbBackupV2Service.startBackup(backup);

        //check
        List<LogicalBackup> logicalBackupFromRepository = logicalBackupRepository.findAll().stream().toList();
        assertEquals(logicalBackupCount, logicalBackupFromRepository.size());

        LogicalBackup logicalBackup = logicalBackupFromRepository.getFirst();


        for (int i = 0; i < logicalBackupFromRepository.size(); i++) {
            assertEquals("backup" + logicalBackup.getAdapterId(), logicalBackup.getLogicalBackupName());
            Backup currBackup = logicalBackups.get(i).getBackup();
            String storageName = currBackup.getStorageName();
            String blobPath = currBackup.getBlobPath();

            List<Map<String, String>> dbNames = logicalBackups.get(i).getBackupDatabases().stream()
                    .map(db -> Map.of("databaseName", db.getName()))
                    .toList();
            Mockito.verify(adapter, times(1))
                    .backupV2(new BackupAdapterRequest(storageName, blobPath, dbNames));
        }
    }

    @Test
    void initializeFullBackupStructure_shouldReturnFullBackupStructure() {
        //when
        String databaseFirstName = "databaseFirst";

        Database databaseFirst = new Database();
        databaseFirst.setName(databaseFirstName);
        databaseFirst.setConnectionProperties(List.of(Map.of()));
        databaseFirst.setSettings(Map.of());
        databaseFirst.setResources(List.of());
        databaseFirst.setExternallyManageable(false);
        databaseFirst.setAdapterId("1");
        databaseFirst.setConnectionProperties(List.of(Map.of("username", "user", "role", "role")));

        SortedMap<String, Object> classifierFirst = new TreeMap<>();
        classifierFirst.put("microserviceName", databaseFirstName);
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setClassifier(classifierFirst);
        databaseFirst.setDatabaseRegistry(List.of(databaseRegistry));
        databaseFirst.setSettings(Map.of("key", "value"));

        String databaseSecondName = "databaseSecond";

        Database databaseSecond = new Database();
        databaseSecond.setName(databaseSecondName);
        databaseSecond.setConnectionProperties(List.of(Map.of()));
        databaseSecond.setSettings(Map.of());
        databaseSecond.setResources(List.of());
        databaseSecond.setExternallyManageable(false);
        databaseSecond.setAdapterId("2");
        databaseSecond.setConnectionProperties(List.of(Map.of("username", "user", "role", "role")));

        SortedMap<String, Object> classifierSecond = new TreeMap<>();
        classifierSecond.put("microserviceName", databaseSecondName);
        DatabaseRegistry databaseRegistrySecond = new DatabaseRegistry();
        databaseRegistrySecond.setClassifier(classifierSecond);
        databaseSecond.setDatabaseRegistry(List.of(databaseRegistrySecond));
        databaseSecond.setSettings(Map.of("key", "value"));

        String databaseThirdName = "databaseThird";

        Database databaseThird = new Database();
        databaseThird.setName(databaseThirdName);
        databaseThird.setSettings(Map.of());
        databaseThird.setResources(List.of());
        databaseThird.setExternallyManageable(true);
        databaseThird.setAdapterId(null);
        databaseThird.setConnectionProperties(List.of(Map.of("username", "user", "role", "role")));

        SortedMap<String, Object> classifierThird = new TreeMap<>();
        classifierThird.put("microserviceName", databaseThirdName);
        DatabaseRegistry databaseRegistryThird = new DatabaseRegistry();
        databaseRegistryThird.setType("postgresql");
        databaseRegistryThird.setClassifier(classifierThird);
        databaseThird.setDatabaseRegistry(List.of(databaseRegistryThird));
        databaseThird.setSettings(Map.of("key", "value"));


        Map<Database, List<DatabaseRegistry>> databaseList = Stream.of(databaseFirst, databaseSecond, databaseThird)
                .collect(Collectors.toMap(
                        Function.identity(),
                        Database::getDatabaseRegistry
                ));
        String backupName = "test-backup";

        BackupRequest backupRequest = createBackupRequest(backupName, "namespace");

        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(any()))
                .thenReturn(adapter);
        when(adapter.type())
                .thenReturn("psql");
        when(adapter.isBackupRestoreSupported())
                .thenReturn(true);
        //then
        dbBackupV2Service.initializeFullBackupStructure(databaseList, backupRequest);

        //check
        Backup backup = backupRepository.findById(backupName);
        assertEquals(backupName, backup.getName());

        List<LogicalBackup> logicalBackups = logicalBackupRepository.getByBackupName(backupName);

        LogicalBackup logicalBackupFirst = logicalBackups.stream()
                .filter(lb -> lb.getBackupDatabases().stream()
                        .anyMatch(backupDb -> databaseFirstName.equals(backupDb.getName())))
                .findFirst().orElseThrow();
        LogicalBackup logicalBackupSecond = logicalBackups.stream()
                .filter(lb -> lb.getBackupDatabases().stream()
                        .anyMatch(backupDb -> databaseSecondName.equals(backupDb.getName())))
                .findFirst().orElseThrow();


        assertEquals(2, logicalBackups.size());
        assertEquals(databaseFirstName, logicalBackupFirst.getBackupDatabases().getFirst().getName());
        assertEquals(databaseSecondName, logicalBackupSecond.getBackupDatabases().getFirst().getName());

        assertEquals(classifierFirst, logicalBackupFirst.getBackupDatabases().getFirst().getClassifiers().getFirst());
        assertEquals(classifierSecond, logicalBackupSecond.getBackupDatabases().getFirst().getClassifiers().getFirst());

        BackupDatabase.User user = BackupDatabase.User.builder()
                .name("user")
                .role("role")
                .build();

        assertEquals(user, logicalBackupFirst.getBackupDatabases().getFirst().getUsers().getFirst());
        assertEquals(databaseFirst.getSettings(), logicalBackupFirst.getBackupDatabases().getFirst().getSettings());
        assertEquals(databaseSecond.getSettings(), logicalBackupSecond.getBackupDatabases().getFirst().getSettings());

        BackupExternalDatabase backupExternalDatabase = backup.getExternalDatabases().getFirst();

        assertEquals(databaseThirdName, backupExternalDatabase.getName());
        assertEquals("postgresql", backupExternalDatabase.getType());
        assertEquals(List.of(classifierThird), backupExternalDatabase.getClassifiers());
    }

    @Test
    void checkBackupsAsync_shouldNotRunInParallelAcrossNodes() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger executions = new AtomicInteger();

        LockProvider pod1LockProvider = new JdbcTemplateLockProvider(dataSource);
        LockProvider pod2LockProvider = new JdbcTemplateLockProvider(dataSource);

        LockConfiguration config = new LockConfiguration(
                Instant.now(),
                "checkBackupsAsync",
                Duration.ofMinutes(5),
                Duration.ofMinutes(1)
        );

        Runnable pod1Job = () -> {
            assertTrue(pod1LockProvider.lock(config).isPresent(), "pod1LockProvider don`t present");
            dbBackupV2Service.checkBackupsAsync();
            executions.incrementAndGet();
            latch.countDown();
        };

        Runnable pod2Job = () -> {
            assertTrue(pod2LockProvider.lock(config).isPresent(), "pod2LockProvider don`t present");
            dbBackupV2Service.checkBackupsAsync();
            executions.incrementAndGet();
            latch.countDown();
        };

        executor.submit(pod1Job);
        executor.submit(pod2Job);

        latch.await(2, SECONDS);

        assertEquals(1, executions.get());
    }

    @Test
    void trackAndAggregate() {
        String backupName = "backupName";
        String adapterIdFirst = "some-first-adapter-id";
        String adapterIdSecond = "some-second-adpater-id";
        String logicalBackupName1 = "mock-first-name";
        String logicalBackupName2 = "mock-second-name";

        LogicalBackupAdapterResponse adapterResponse = new LogicalBackupAdapterResponse(
                "inProgress",
                null,
                logicalBackupName1,
                LocalDateTime.now(),
                LocalDateTime.now(),
                "storageName",
                "blobPath",
                List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .status("completed")
                                .databaseName("db1")
                                .size(2)
                                .build(),
                        LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .status("notStarted")
                                .databaseName("db2")
                                .size(1)
                                .build()
                )
        );
        LogicalBackupAdapterResponse adapterResponse2 = new LogicalBackupAdapterResponse(
                "inProgress",
                null,
                logicalBackupName2,
                LocalDateTime.now(),
                LocalDateTime.now(),
                "storageName",
                "blobPath",
                List.of(LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .status("completed")
                                .databaseName("db1")
                                .size(2)
                                .build(),
                        LogicalBackupAdapterResponse.BackupDatabaseResponse.builder()
                                .status("notStarted")
                                .databaseName("db2")
                                .size(1)
                                .build()
                )
        );

        LogicalBackup logicalBackup = new LogicalBackup();
        logicalBackup.setLogicalBackupName(logicalBackupName1);
        logicalBackup.setStatus(BackupTaskStatus.IN_PROGRESS);
        logicalBackup.setAdapterId(adapterIdFirst);
        logicalBackup.setType("postgresql");
        logicalBackup.setBackupDatabases(List.of(BackupDatabase.builder()
                        .name("db1")
                        .users(List.of())
                        .classifiers(List.of())
                        .build(),
                BackupDatabase.builder()
                        .name("db2")
                        .users(List.of())
                        .classifiers(List.of())
                        .build()));

        LogicalBackup logicalBackup1 = new LogicalBackup();
        logicalBackup1.setStatus(BackupTaskStatus.IN_PROGRESS);
        logicalBackup1.setLogicalBackupName(logicalBackupName2);
        logicalBackup1.setAdapterId(adapterIdSecond);
        logicalBackup1.setType("postgresql");
        logicalBackup1.setBackupDatabases(List.of(BackupDatabase.builder()
                        .name("db1")
                        .users(List.of())
                        .classifiers(List.of())
                        .build(),
                BackupDatabase.builder()
                        .name("db2")
                        .users(List.of())
                        .classifiers(List.of())
                        .build()));

        List<LogicalBackup> logicalBackups = List.of(logicalBackup, logicalBackup1);

        Backup backup = createBackup(backupName, logicalBackups);
        logicalBackup.setBackup(backup);
        logicalBackup1.setBackup(backup);
        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapterIdFirst))
                .thenReturn(adapter);
        when(adapter.trackBackupV2(any(), any(), any()))
                .thenReturn(adapterResponse);

        DbaasAdapter adapter1 = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapterIdSecond))
                .thenReturn(adapter1);
        when(adapter1.trackBackupV2(any(), any(), any()))
                .thenReturn(adapterResponse2);


        dbBackupV2Service.trackAndAggregate(backup);

        Backup expectedBackup = backupRepository.findById(backupName);

        assertNotNull(expectedBackup);
        assertEquals(1, backup.getAttemptCount());
        assertEquals(BackupStatus.IN_PROGRESS, expectedBackup.getStatus());
        assertEquals(4, expectedBackup.getTotal());
        assertEquals(6, expectedBackup.getSize());
        assertEquals(2, expectedBackup.getCompleted());

        String aggregatedErrorMsg = logicalBackups.stream()
                .filter(lb -> lb.getErrorMessage() != null && !lb.getErrorMessage().isBlank())
                .map(lb -> String.format("LogicalBackup %s failed: %s",
                        lb.getLogicalBackupName(),
                        lb.getErrorMessage()))
                .collect(Collectors.joining("; "));

        assertEquals(aggregatedErrorMsg, expectedBackup.getErrorMessage());
    }

    @Test
    void trackAndAggregate_backupAttemptExceeded_aggregatorMustBeFailed() {
        String backupName = "backupName";

        Backup backup = createBackup(backupName, List.of());
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
    void updateAggregatedStatus_shouldReturnProceeding_containAllStatuses() {
        String backupName = "backupName";
        String adapterIdFirst = "some-first-adapter-id";
        String adapterIdSecond = "some-second-adpater-id";
        String logicalBackupName1 = "mock-first-name";
        String logicalBackupName2 = "mock-second-name";

        LogicalBackup logicalBackup = new LogicalBackup();
        logicalBackup.setLogicalBackupName(logicalBackupName1);
        logicalBackup.setAdapterId(adapterIdFirst);
        logicalBackup.setStatus(BackupTaskStatus.IN_PROGRESS);
        logicalBackup.setType("postgresql");
        logicalBackup.setBackupDatabases(List.of(BackupDatabase.builder()
                        .name("db1")
                        .status(BackupTaskStatus.COMPLETED)
                        .size(2)
                        .users(List.of())
                        .classifiers(List.of())
                        .build(),
                BackupDatabase.builder()
                        .name("db2")
                        .status(BackupTaskStatus.COMPLETED)
                        .size(1)
                        .users(List.of())
                        .classifiers(List.of())
                        .build()));

        LogicalBackup logicalBackup1 = new LogicalBackup();
        logicalBackup1.setLogicalBackupName(logicalBackupName2);
        logicalBackup1.setStatus(BackupTaskStatus.IN_PROGRESS);
        logicalBackup1.setAdapterId(adapterIdSecond);
        logicalBackup1.setType("postgresql");
        logicalBackup1.setBackupDatabases(List.of(BackupDatabase.builder()
                        .name("db1")
                        .status(BackupTaskStatus.IN_PROGRESS)
                        .size(1)
                        .users(List.of())
                        .classifiers(List.of())
                        .build(),
                BackupDatabase.builder()
                        .name("db2")
                        .status(BackupTaskStatus.IN_PROGRESS)
                        .size(2)
                        .users(List.of())
                        .classifiers(List.of())
                        .build()));

        List<LogicalBackup> logicalBackups = List.of(logicalBackup, logicalBackup1);

        Backup backup = createBackup(backupName, logicalBackups);

        dbBackupV2Service.updateAggregatedStatus(backup);

        String aggregatedErrorMsg = logicalBackups.stream()
                .filter(lb -> lb.getErrorMessage() != null && !lb.getErrorMessage().isBlank())
                .map(lb -> String.format("LogicalBackup %s failed: %s",
                        lb.getLogicalBackupName(),
                        lb.getErrorMessage()))
                .collect(Collectors.joining("; "));

        //check
        assertEquals(BackupStatus.IN_PROGRESS, backup.getStatus());
        assertEquals(4, backup.getTotal());
        assertEquals(2, backup.getCompleted());
        assertEquals(6, backup.getSize());
        assertEquals(aggregatedErrorMsg, backup.getErrorMessage());
    }

    @Test
    void aggregateStatus_shouldReturnInProgress_whenContainAllStatuses() {
        Set<BackupTaskStatus> statusSet = Set.of(BackupTaskStatus.NOT_STARTED, BackupTaskStatus.IN_PROGRESS, BackupTaskStatus.FAILED, BackupTaskStatus.COMPLETED);
        BackupStatus backupStatus = dbBackupV2Service.aggregateBackupStatus(statusSet);

        assertNotNull(backupStatus);
        assertEquals(BackupStatus.IN_PROGRESS, backupStatus);
    }

    @Test
    void aggregateStatus_shouldReturnInProgress_whenInputInProgressFailedCompleted() {
        Set<BackupTaskStatus> statusSet = Set.of(BackupTaskStatus.IN_PROGRESS, BackupTaskStatus.FAILED, BackupTaskStatus.COMPLETED);
        BackupStatus backupStatus = dbBackupV2Service.aggregateBackupStatus(statusSet);

        assertNotNull(backupStatus);
        assertEquals(BackupStatus.IN_PROGRESS, backupStatus);
    }

    @Test
    void aggregateStatus_shouldReturnInProgress_whenInputNotStartedFailedCompleted() {
        Set<BackupTaskStatus> statusSet = Set.of(BackupTaskStatus.NOT_STARTED, BackupTaskStatus.FAILED, BackupTaskStatus.COMPLETED);
        BackupStatus backupStatus = dbBackupV2Service.aggregateBackupStatus(statusSet);

        assertNotNull(backupStatus);
        assertEquals(BackupStatus.IN_PROGRESS, backupStatus);
    }

    @Test
    void aggregateStatus_shouldReturnFailed_whenInputFailedCompleted() {
        Set<BackupTaskStatus> statusSet = Set.of(BackupTaskStatus.FAILED, BackupTaskStatus.COMPLETED);
        BackupStatus backupStatus = dbBackupV2Service.aggregateBackupStatus(statusSet);

        assertNotNull(backupStatus);
        assertEquals(BackupStatus.FAILED, backupStatus);
    }

    @Test
    void aggregateStatus_shouldReturnCompleted_whenInputCompleted() {
        Set<BackupTaskStatus> statusSet = Set.of(BackupTaskStatus.COMPLETED);
        BackupStatus backupStatus = dbBackupV2Service.aggregateBackupStatus(statusSet);

        assertNotNull(backupStatus);
        assertEquals(BackupStatus.COMPLETED, backupStatus);
    }

    @Test
    void getCurrentStatus_throwException() {
        String backupName = "backupName";
        assertThrows(BackupNotFoundException.class,
                () -> dbBackupV2Service.getCurrentStatus(backupName));
    }

    @Test
    void deleteBackup() {
        String backupName = "backupName";
        List<LogicalBackup> logicalBackups = generateLogicalBackups(2);
        Backup backup = createBackup(backupName, logicalBackups);
        backup.setStatus(BackupStatus.COMPLETED);
        backupRepository.save(backup);

        logicalBackups.forEach(db -> db.setBackup(backup));
        logicalBackupRepository.persist(logicalBackups);

        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        DbaasAdapter adapter1 = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById("0"))
                .thenReturn(adapter);
        when(physicalDatabasesService.getAdapterById("1"))
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
    void deleteBackup_adapterThrowException() {
        String backupName = "backupName";
        boolean force = true;
        List<LogicalBackup> logicalBackups = generateLogicalBackups(2);
        Backup backup = createBackup(backupName, logicalBackups);
        backup.setStatus(BackupStatus.IN_PROGRESS);
        backupRepository.save(backup);

        logicalBackups.forEach(db -> db.setBackup(backup));
        logicalBackupRepository.persist(logicalBackups);

        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        DbaasAdapter adapter1 = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById("0"))
                .thenReturn(adapter);
        when(physicalDatabasesService.getAdapterById("1"))
                .thenReturn(adapter1);

        doThrow(new IllegalResourceStateException(backupName, Source.builder().build()))
                .when(adapter1).deleteBackupV2(any());

        assertThrows(UnprocessableEntityException.class,
                () -> dbBackupV2Service.deleteBackup(backupName, force));
    }

    @Test
    void getBackupMetadata() {
        String backupName = "backupName";
        List<LogicalBackup> logicalBackups = generateLogicalBackups(1);
        Backup backup = createBackup(backupName, logicalBackups);
        backup.setStatus(BackupStatus.COMPLETED);
        backup.setTotal(1);
        backup.setCompleted(1);
        backup.setSize(1L);

        logicalBackups.forEach(lb -> lb.setBackup(backup));

        backupRepository.save(backup);

        BackupResponse response = dbBackupV2Service.getBackupMetadata(backupName);

        assertNotNull(response);
        assertEquals("backupName", response.getBackupName());
        assertEquals("storagename", response.getStorageName());
        assertEquals("path", response.getBlobPath());
        assertEquals(ExternalDatabaseStrategy.SKIP, response.getExternalDatabaseStrategy());
        assertFalse(response.isIgnoreNotBackupableDatabases());

        BackupStatusResponse status = new BackupStatusResponse(
                response.getStatus(),
                response.getTotal(),
                response.getCompleted(),
                response.getSize(),
                response.getErrorMessage());

        assertEquals(BackupStatus.COMPLETED, status.getStatus());
        assertEquals(1, status.getTotal());
        assertEquals(1, status.getCompleted());
        assertEquals(1, status.getSize());
        assertNull(status.getErrorMessage());

        List<LogicalBackupResponse> logicalBackupsResponse = response.getLogicalBackups();
        assertEquals(1, logicalBackupsResponse.size());

        LogicalBackupResponse logicalBackup = logicalBackupsResponse.getFirst();
        assertEquals("test-type", logicalBackup.getType());
        assertEquals(BackupTaskStatus.NOT_STARTED, logicalBackup.getStatus());

        List<BackupDatabaseResponse> dbStatuses =
                logicalBackup.getBackupDatabases();
        assertEquals(2, dbStatuses.size());

        assertEquals("db1-0", dbStatuses.getFirst().getName());
        assertEquals(BackupTaskStatus.COMPLETED, dbStatuses.getFirst().getStatus());
        assertEquals(1, dbStatuses.getFirst().getDuration());

        assertEquals("db2-0", dbStatuses.get(1).getName());
        assertEquals(BackupTaskStatus.COMPLETED, dbStatuses.get(1).getStatus());

        List<BackupDatabaseResponse> dbs = logicalBackup.getBackupDatabases();
        assertEquals(2, dbs.size());

        BackupDatabaseResponse db1 = dbs.getFirst();
        assertEquals("db1-0", db1.getName());
        assertEquals("{key=value}", db1.getResources().toString());
        assertEquals("role", db1.getUsers().getFirst().getRole());
        assertEquals("name", db1.getUsers().getFirst().getName());
    }

    @Test
    void getBackupMetadata_ForStatusNotCompletedBackupMetadata() {
        String backupName = "backupName";
        List<LogicalBackup> logicalBackups = generateLogicalBackups(1);
        Backup backup = createBackup(backupName, logicalBackups);
        backup.setStatus(BackupStatus.IN_PROGRESS);
        backup.setTotal(1);
        backup.setCompleted(1);
        backup.setSize(1L);

        logicalBackups.forEach(lb -> lb.setBackup(backup));

        backupRepository.save(backup);

        assertThrows(UnprocessableEntityException.class,
                () -> dbBackupV2Service.getBackupMetadata(backupName));
    }

    @Test
    void uploadBackupMetadata() {
        //when
        String namespace = "namespace";
        String backupName = "backupName";

        BackupResponse expected = generateBackupResponse(backupName, namespace);

        dbBackupV2Service.uploadBackupMetadata(expected);

        Backup actual = backupRepository.findById(backupName);
        assertEquals(actual.getName(), expected.getBackupName());
        assertEquals(actual.getStorageName(), expected.getStorageName());
        assertEquals(actual.getBlobPath(), expected.getBlobPath());
        assertEquals(actual.getExternalDatabaseStrategy(), expected.getExternalDatabaseStrategy());

        LogicalBackupResponse expectedLogicalBackup = expected.getLogicalBackups().getFirst();
        LogicalBackup actualLogicalBackup = actual.getLogicalBackups().getFirst();

        assertEquals(actualLogicalBackup.getLogicalBackupName(), expectedLogicalBackup.getLogicalBackupName());
        assertNotNull(actualLogicalBackup.getAdapterId());
        assertEquals(actualLogicalBackup.getType(), expectedLogicalBackup.getType());

        BackupDatabaseResponse expectedBackupDatabase = expectedLogicalBackup.getBackupDatabases().getFirst();
        BackupDatabase actualBackupDatabase = actualLogicalBackup.getBackupDatabases().getFirst();
        assertEquals(actualBackupDatabase.getName(), expectedBackupDatabase.getName());
        assertEquals(actualBackupDatabase.getSettings(), expectedBackupDatabase.getSettings());
        assertEquals(actualBackupDatabase.getResources(), expectedBackupDatabase.getResources());

        BackupExternalDatabaseResponse backupExternalDatabaseResponse = expected.getExternalDatabases().getFirst();
        BackupExternalDatabase backupExternalDatabase = actual.getExternalDatabases().getFirst();
        assertEquals(backupExternalDatabaseResponse.getName(), backupExternalDatabase.getName());
        assertEquals(backupExternalDatabaseResponse.getType(), backupExternalDatabase.getType());
        assertEquals(backupExternalDatabaseResponse.getClassifiers(), backupExternalDatabase.getClassifiers());
    }

    @Test
    void uploadBackupMetadata_BackupAlreadyExists() {
        String backupName = "backupName";
        String namespace = "namespace";

        Backup backup = createBackup(backupName, List.of());
        backupRepository.save(backup);

        BackupResponse backupResponse = generateBackupResponse(backupName, namespace);

        assertThrows(IllegalResourceStateException.class,
                () -> dbBackupV2Service.uploadBackupMetadata(backupResponse));
    }

    @Test
    void restore() {
        String backupName = "backupName";
        String oldNamespace1 = "old-ns1";
        String newNamespace1 = "new-ns1";
        String oldNamespace2 = "old-ns2";
        String newNamespace2 = "new-ns2";
        String logicalBackupName1 = "logicalBackupName1";
        String logicalBackupName2 = "logicalBackupName2";
        String storageName = "storageName";
        String blobPath = "blobPath";
        String adapterId1 = "adapter1";
        String adapterId2 = "adapter2";
        String db1 = "db1";
        String db2 = "db2";
        String db3 = "db3";
        String postgresql = "postgresql";
        String mongoDb = "mongoDb";

        SortedMap<String, Object> map1 = new TreeMap<>();
        map1.put("namespace", oldNamespace1);
        map1.put("microserviceName", "microserviceName");

        SortedMap<String, Object> map2 = new TreeMap<>();
        map2.put("namespace", oldNamespace2);
        map2.put("microserviceName", "microserviceName");

        BackupDatabase backupDatabase1 = BackupDatabase.builder()
                .name(db1)
                .resources(Map.of())
                .settings(Map.of())
                .users(List.of(BackupDatabase.User.builder()
                        .name("name")
                        .role("role")
                        .build()))
                .classifiers(List.of(map1))
                .build();

        BackupDatabase backupDatabase2 = BackupDatabase.builder()
                .name(db2)
                .resources(Map.of())
                .settings(Map.of())
                .users(List.of(BackupDatabase.User.builder()
                        .name("name")
                        .role("role")
                        .build()))
                .classifiers(List.of(map2))
                .build();

        BackupDatabase backupDatabase3 = BackupDatabase.builder()
                .name(db3)
                .resources(Map.of())
                .settings(Map.of())
                .users(List.of(BackupDatabase.User.builder()
                        .name("name")
                        .role("role")
                        .build()))
                .classifiers(List.of(map2))
                .build();

        LogicalBackup logicalBackup1 = LogicalBackup.builder()
                .logicalBackupName(logicalBackupName1)
                .type(postgresql)
                .backupDatabases(List.of(backupDatabase1, backupDatabase2))
                .build();
        LogicalBackup logicalBackup2 = LogicalBackup.builder()
                .logicalBackupName(logicalBackupName2)
                .type(mongoDb)
                .backupDatabases(List.of(backupDatabase3))
                .build();

        backupDatabase1.setLogicalBackup(logicalBackup1);
        backupDatabase2.setLogicalBackup(logicalBackup1);
        backupDatabase3.setLogicalBackup(logicalBackup2);

        Backup backup = Backup.builder()
                .name(backupName)
                .storageName(storageName)
                .blobPath(blobPath)
                .externalDatabaseStrategy(ExternalDatabaseStrategy.SKIP)
                .logicalBackups(List.of(logicalBackup1, logicalBackup2))
                .status(BackupStatus.COMPLETED)
                .build();
        logicalBackup1.setBackup(backup);
        logicalBackup2.setBackup(backup);

        backupRepository.save(backup);

        LogicalRestoreAdapterResponse response1 = new LogicalRestoreAdapterResponse(
                "completed",
                null,
                logicalBackupName1,
                LocalDateTime.now(),
                LocalDateTime.now(),
                storageName,
                blobPath,
                List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                                .status("completed")
                                .previousDatabaseName(db1)
                                .path("path")
                                .databaseName("newDb1")
                                .duration(1)
                                .build(),
                        LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                                .status("completed")
                                .previousDatabaseName(db2)
                                .path("path")
                                .databaseName("newDb2")
                                .duration(1)
                                .build())
        );

        LogicalRestoreAdapterResponse response2 = new LogicalRestoreAdapterResponse(
                "completed",
                null,
                logicalBackupName2,
                LocalDateTime.now(),
                LocalDateTime.now(),
                storageName,
                blobPath,
                List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                        .status("completed")
                        .previousDatabaseName(db3)
                        .path("path")
                        .databaseName("newDb3")
                        .duration(1)
                        .build())
        );

        Mapping mapping = new Mapping();
        mapping.setNamespaces(Map.of(
                oldNamespace1, newNamespace1,
                oldNamespace2, newNamespace2
        ));

        Filter filter = new Filter();
        filter.setNamespace(List.of("old-ns2"));
        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setFilter(List.of(filter));

        RestoreRequest restoreRequest = new RestoreRequest();
        restoreRequest.setRestoreName("restoreName");
        restoreRequest.setMapping(mapping);
        restoreRequest.setStorageName(storageName);
        restoreRequest.setBlobPath(blobPath);
        restoreRequest.setExternalDatabaseStrategy(ExternalDatabaseStrategy.FAIL);
        restoreRequest.setFilterCriteria(filterCriteria);

        ExternalAdapterRegistrationEntry adapter1 = new ExternalAdapterRegistrationEntry();
        adapter1.setAdapterId(adapterId1);
        PhysicalDatabase physicalDatabase1 = new PhysicalDatabase();
        physicalDatabase1.setAdapter(adapter1);
        physicalDatabase1.setType(postgresql);

        ExternalAdapterRegistrationEntry adapter2 = new ExternalAdapterRegistrationEntry();
        adapter2.setAdapterId(adapterId2);
        PhysicalDatabase physicalDatabase2 = new PhysicalDatabase();
        physicalDatabase2.setAdapter(adapter2);
        physicalDatabase2.setType(mongoDb);

        when(balancingRulesService.applyBalancingRules(eq(logicalBackup1.getType()), any(), eq((String) map1.get("microserviceName"))))
                .thenReturn(physicalDatabase1);
        when(balancingRulesService.applyBalancingRules(eq(logicalBackup2.getType()), any(), eq((String) map2.get("microserviceName"))))
                .thenReturn(physicalDatabase2);

        DbaasAdapter dbaasAdapter = Mockito.mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAdapterById(adapterId1))
                .thenReturn(dbaasAdapter);
        when(dbaasAdapter.restoreV2(any(), anyBoolean(), any()))
                .thenReturn(response1);
        when(dbaasAdapter.isBackupRestoreSupported())
                .thenReturn(true);

        DbaasAdapter dbaasAdapter2 = Mockito.mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAdapterById(adapterId2))
                .thenReturn(dbaasAdapter2);
        when(dbaasAdapter2.isBackupRestoreSupported())
                .thenReturn(true);
        when(dbaasAdapter2.restoreV2(any(), anyBoolean(), any()))
                .thenReturn(response2);

        RestoreResponse response = dbBackupV2Service.restore(backupName, restoreRequest, false);

        assertNotNull(response);
        assertEquals("restoreName", response.getRestoreName());
        assertEquals(backupName, response.getBackupName());
        assertEquals(storageName, response.getStorageName());
        assertEquals(blobPath, response.getBlobPath());
        assertEquals(RestoreStatus.COMPLETED, response.getStatus());
        assertNotNull(response.getMapping());
        assertNotNull(response.getFilterCriteria());
        assertEquals(Map.of(
                oldNamespace2, newNamespace2,
                oldNamespace1, newNamespace1
        ), response.getMapping().getNamespaces());

        assertEquals(2, response.getLogicalRestores().size());

        LogicalRestoreResponse lrr1 = response.getLogicalRestores().stream()
                .filter(lr -> adapterId1.equals(lr.getAdapterId()))
                .findFirst()
                .orElse(null);
        assertNotNull(lrr1);
        assertEquals(logicalBackupName1, lrr1.getLogicalRestoreName());
        assertEquals(postgresql, lrr1.getType());
        assertEquals(RestoreTaskStatus.COMPLETED, lrr1.getStatus());
        assertEquals(1, lrr1.getRestoreDatabases().size());
        RestoreDatabaseResponse rdb1 = lrr1.getRestoreDatabases().getFirst();
        assertEquals("newDb2", rdb1.getName());
        assertEquals(List.of(Map.of("microserviceName", "microserviceName", "namespace", newNamespace2)), rdb1.getClassifiers());
        assertEquals(RestoreTaskStatus.COMPLETED, rdb1.getStatus());

        LogicalRestoreResponse lrr2 = response.getLogicalRestores().stream()
                .filter(lr -> adapterId2.equals(lr.getAdapterId()))
                .findFirst()
                .orElse(null);
        assertNotNull(lrr2);
        assertEquals(logicalBackupName2, lrr2.getLogicalRestoreName());
        assertEquals(mongoDb, lrr2.getType());
        assertEquals(RestoreTaskStatus.COMPLETED, lrr2.getStatus());
        assertEquals(1, lrr2.getRestoreDatabases().size());
        RestoreDatabaseResponse rdb2 = lrr2.getRestoreDatabases().getFirst();
        assertEquals("newDb3", rdb2.getName());
        assertEquals(List.of(Map.of("microserviceName", "microserviceName", "namespace", newNamespace2)), rdb2.getClassifiers());
        assertEquals(RestoreTaskStatus.COMPLETED, rdb2.getStatus());

        Restore restore = restoreRepository.findById("restoreName");

        assertNotNull(restore);
        assertEquals(storageName, restore.getStorageName());
        assertEquals(blobPath, restore.getBlobPath());
        assertEquals(2, restore.getLogicalRestores().size());

        LogicalRestore logicalRestore1 = restore.getLogicalRestores().stream()
                .filter(lr -> lr.getAdapterId().equals(adapterId1))
                .findFirst()
                .orElse(null);
        assertNotNull(logicalRestore1);
        assertEquals(1, logicalRestore1.getRestoreDatabases().size());
        assertEquals(RestoreTaskStatus.COMPLETED, logicalRestore1.getStatus());

        LogicalRestore logicalRestore2 = restore.getLogicalRestores().stream()
                .filter(lr -> lr.getAdapterId().equals(adapterId2))
                .findFirst()
                .orElse(null);
        assertNotNull(logicalRestore2);
        assertEquals(1, logicalRestore2.getRestoreDatabases().size());
        assertEquals(RestoreTaskStatus.COMPLETED, logicalRestore2.getStatus());

        SortedMap<String, Object> expectedClassifier2 = new TreeMap<>();
        expectedClassifier2.put("namespace", newNamespace2);
        expectedClassifier2.put("microserviceName", "microserviceName");

        RestoreDatabase restoreDatabase2 = logicalRestore1.getRestoreDatabases().stream()
                .filter(rd -> rd.getBackupDatabase().getName().equals(backupDatabase2.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(restoreDatabase2);
        assertEquals("newDb2", restoreDatabase2.getName());


        assertEquals(List.of(expectedClassifier2), restoreDatabase2.getClassifiers());

        RestoreDatabase restoreDatabase3 = logicalRestore2.getRestoreDatabases().getFirst();
        assertNotNull(restoreDatabase3);
        assertNotNull(restoreDatabase3.getLogicalRestore());
        assertNotNull(restoreDatabase3.getBackupDatabase());
        assertEquals("newDb3", restoreDatabase3.getName());

        SortedMap<String, Object> expectedClassifier3 = new TreeMap<>();
        expectedClassifier3.put("namespace", newNamespace2);
        expectedClassifier3.put("microserviceName", "microserviceName");

        assertEquals(List.of(expectedClassifier3), restoreDatabase3.getClassifiers());

        verify(balancingRulesService, times(1)).applyBalancingRules(eq(logicalBackup1.getType()), any(), eq((String) map1.get("microserviceName")));
        verify(balancingRulesService, times(1)).applyBalancingRules(eq(logicalBackup2.getType()), any(), eq((String) map2.get("microserviceName")));
        verify(physicalDatabasesService, times(2)).getAdapterById(adapterId1);
        verify(physicalDatabasesService, times(2)).getAdapterById(adapterId2);
        verify(dbaasAdapter).restoreV2(any(), anyBoolean(), any());
        verify(dbaasAdapter2).restoreV2(any(), anyBoolean(), any());
    }

    @Test
    void getAllDbByFilter_RestorePart() {
        String namespace = "namespace";
        String wrongNamespace = "wrongNamespace";

        SortedMap<String, Object> classifier1 = new TreeMap<>();
        classifier1.put("namespace", namespace);
        classifier1.put("microserviceName", "microserviceName1");
        SortedMap<String, Object> classifier2 = new TreeMap<>();
        classifier2.put("namespace", wrongNamespace);
        classifier2.put("microserviceName", "microserviceName2");

        BackupDatabase backupDatabase1 = BackupDatabase.builder()
                .name("db1")
                .classifiers(List.of(classifier1, classifier2))
                .users(List.of())
                .settings(Map.of())
                .resources(Map.of())
                .build();


        BackupDatabase backupDatabase2 = BackupDatabase.builder()
                .name("db2")
                .classifiers(List.of(classifier2))
                .users(List.of())
                .settings(Map.of())
                .resources(Map.of())
                .build();

        Filter filter = new Filter();
        filter.setNamespace(List.of(namespace));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setFilter(List.of(filter));

        List<BackupDatabaseDelegate> result =
                dbBackupV2Service.getAllDbByFilter(List.of(backupDatabase1, backupDatabase2), filterCriteria);

        assertEquals(1, result.size());
        BackupDatabaseDelegate delegate = result.get(0);

        assertEquals("db1", delegate.backupDatabase().getName());

        assertEquals(1, delegate.classifiers().size());

        SortedMap<String, Object> classifier = delegate.classifiers().get(0);
        assertEquals(namespace, classifier.get("namespace"));
        assertEquals("microserviceName1", classifier.get("microserviceName"));
    }

    @Test
    void getAllDbByFilter_RestorePart_whenPassedSeveralNamespaces_returnFunctionalityNotImplemented() {
        Filter filter = new Filter();
        filter.setNamespace(List.of("namespace1", "namespace2"));
        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setFilter(List.of(filter));

        FunctionalityNotImplemented ex = assertThrows(FunctionalityNotImplemented.class,
                () -> dbBackupV2Service.getAllDbByFilter(filterCriteria));

        assertEquals("The requested functionality is not implemented: backup by several namespace", ex.getDetail());
    }

    @Test
    void getAllDbByFilter_RestorePart_whenFilterByMicroservice_returnFunctionalityNotImplemented() {
        Filter filter = new Filter();
        filter.setMicroserviceName(List.of("microserviceName"));
        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setFilter(List.of(filter));

        FunctionalityNotImplemented ex = assertThrows(FunctionalityNotImplemented.class,
                () -> dbBackupV2Service.getAllDbByFilter(filterCriteria));

        assertEquals("The requested functionality is not implemented: backup by microservice", ex.getDetail());
    }

    @Test
    void initializeFullRestoreStructure_withMapping() {
        String oldNamespace1 = "old-ns1";
        String newNamespace1 = "new-ns1";
        String oldNamespace2 = "old-ns2";
        String newNamespace2 = "new-ns2";
        String adapterId1 = "adapterId1";
        String adapterId2 = "adapterId2";

        SortedMap<String, Object> classifier1 = new TreeMap<>();
        classifier1.put("namespace", oldNamespace1);
        classifier1.put("microserviceName", "microserviceName1");
        SortedMap<String, Object> classifier2 = new TreeMap<>();
        classifier2.put("namespace", oldNamespace2);
        classifier2.put("microserviceName", "microserviceName2");

        BackupDatabase backupDatabase1 = BackupDatabase.builder()
                .name("db1")
                .classifiers(List.of(classifier1, classifier2))
                .users(List.of())
                .settings(Map.of())
                .resources(Map.of())
                .build();


        BackupDatabase backupDatabase2 = BackupDatabase.builder()
                .name("db2")
                .classifiers(List.of(classifier2))
                .users(List.of())
                .settings(Map.of())
                .resources(Map.of())
                .build();

        LogicalBackup logicalBackup1 = LogicalBackup.builder()
                .logicalBackupName("lb1")
                .type("postgresql")
                .backupDatabases(List.of(backupDatabase1))
                .build();

        LogicalBackup logicalBackup2 = LogicalBackup.builder()
                .logicalBackupName("lb2")
                .type("mongodb")
                .backupDatabases(List.of(backupDatabase2))
                .build();

        backupDatabase1.setLogicalBackup(logicalBackup1);
        backupDatabase2.setLogicalBackup(logicalBackup2);

        Backup backup = new Backup();
        backup.setName("backup-name");
        backup.setLogicalBackups(List.of(logicalBackup1, logicalBackup2));
        backup.setStorageName("storageName");
        backup.setBlobPath("blobpath");
        backup.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);

        logicalBackup1.setBackup(backup);
        logicalBackup2.setBackup(backup);

        backupRepository.save(backup);

        Mapping mapping = new Mapping();
        mapping.setNamespaces(Map.of(
                oldNamespace1, newNamespace1,
                oldNamespace2, newNamespace2
        ));

        RestoreRequest restoreRequest = new RestoreRequest();
        restoreRequest.setMapping(mapping);
        restoreRequest.setStorageName("storage");
        restoreRequest.setBlobPath("blobPath");
        restoreRequest.setFilterCriteria(new FilterCriteria());

        ExternalAdapterRegistrationEntry adapter1 = new ExternalAdapterRegistrationEntry();
        adapter1.setAdapterId(adapterId1);
        PhysicalDatabase physicalDatabase1 = new PhysicalDatabase();
        physicalDatabase1.setAdapter(adapter1);

        ExternalAdapterRegistrationEntry adapter2 = new ExternalAdapterRegistrationEntry();
        adapter2.setAdapterId(adapterId2);
        PhysicalDatabase physicalDatabase2 = new PhysicalDatabase();
        physicalDatabase2.setAdapter(adapter2);

        when(balancingRulesService.applyBalancingRules(eq(logicalBackup1.getType()), any(), eq((String) classifier1.get("microserviceName"))))
                .thenReturn(physicalDatabase1);
        when(balancingRulesService.applyBalancingRules(eq(logicalBackup2.getType()), any(), eq((String) classifier2.get("microserviceName"))))
                .thenReturn(physicalDatabase2);

        DbaasAdapter adapter3 = Mockito.mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAdapterById(eq(adapterId2)))
                .thenReturn(adapter3);
        when(adapter3.isBackupRestoreSupported())
                .thenReturn(true);

        DbaasAdapter adapter4 = Mockito.mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAdapterById(eq(adapterId1)))
                .thenReturn(adapter4);
        when(adapter4.isBackupRestoreSupported())
                .thenReturn(true);

        Restore restore = dbBackupV2Service.initializeFullRestoreStructure(backup, restoreRequest);

        assertNotNull(restore);
        assertEquals(backup, restore.getBackup());
        assertEquals("storage", restore.getStorageName());
        assertEquals("blobPath", restore.getBlobPath());
        assertEquals(2, restore.getLogicalRestores().size());

        LogicalRestore logicalRestore1 = restore.getLogicalRestores().stream()
                .filter(lr -> lr.getAdapterId().equals(adapterId1))
                .findFirst()
                .orElseThrow();
        assertEquals(adapterId1, logicalRestore1.getAdapterId());
        assertEquals(1, logicalRestore1.getRestoreDatabases().size());
        assertEquals(backupDatabase1, logicalRestore1.getRestoreDatabases().getFirst().getBackupDatabase());

        SortedMap<String, Object> expectedClassifier1 = new TreeMap<>(classifier1);
        expectedClassifier1.put("namespace", newNamespace1);
        SortedMap<String, Object> expectedClassifier2 = new TreeMap<>(classifier2);
        expectedClassifier2.put("namespace", newNamespace2);

        assertEquals(List.of(expectedClassifier1, expectedClassifier2), logicalRestore1.getRestoreDatabases().getFirst().getClassifiers());

        LogicalRestore logicalRestore2 = restore.getLogicalRestores().stream()
                .filter(lr -> lr.getAdapterId().equals(adapterId2))
                .findFirst()
                .orElseThrow();
        assertEquals(adapterId2, logicalRestore2.getAdapterId());
        assertEquals(1, logicalRestore2.getRestoreDatabases().size());
        assertEquals(backupDatabase2, logicalRestore2.getRestoreDatabases().getFirst().getBackupDatabase());


        assertEquals(List.of(expectedClassifier2), logicalRestore2.getRestoreDatabases().getFirst().getClassifiers());

        verify(balancingRulesService, times(1)).applyBalancingRules(eq(logicalBackup1.getType()), any(), eq((String) classifier1.get("microserviceName")));
        verify(balancingRulesService, times(1)).applyBalancingRules(eq(logicalBackup2.getType()), any(), eq((String) classifier2.get("microserviceName")));
    }

    @Test
    void initializeFullRestoreStructure_withoutMapping() {
        String oldNamespace1 = "old-ns1";
        String oldNamespace2 = "old-ns2";
        String adapterId1 = "adapterId1";
        String adapterId2 = "adapterId2";

        SortedMap<String, Object> classifier1 = new TreeMap<>();
        classifier1.put("namespace", oldNamespace1);
        classifier1.put("microserviceName", "microserviceName1");
        SortedMap<String, Object> classifier2 = new TreeMap<>();
        classifier2.put("namespace", oldNamespace2);
        classifier2.put("microserviceName", "microserviceName2");

        BackupDatabase backupDatabase1 = BackupDatabase.builder()
                .name("db1")
                .classifiers(List.of(classifier1))
                .users(List.of())
                .settings(Map.of())
                .resources(Map.of())
                .build();


        BackupDatabase backupDatabase2 = BackupDatabase.builder()
                .name("db2")
                .classifiers(List.of(classifier2))
                .users(List.of())
                .settings(Map.of())
                .resources(Map.of())
                .build();

        LogicalBackup logicalBackup1 = LogicalBackup.builder()
                .logicalBackupName("lb1")
                .type("postgresql")
                .backupDatabases(List.of(backupDatabase1))
                .build();

        LogicalBackup logicalBackup2 = LogicalBackup.builder()
                .logicalBackupName("lb2")
                .type("mongodb")
                .backupDatabases(List.of(backupDatabase2))
                .build();


        backupDatabase1.setLogicalBackup(logicalBackup1);
        backupDatabase2.setLogicalBackup(logicalBackup2);

        Backup backup = new Backup();
        backup.setName("backup-name");
        backup.setLogicalBackups(List.of(logicalBackup1, logicalBackup2));
        backup.setBlobPath("blobPath");
        backup.setStorageName("storageName");
        backup.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);

        logicalBackup1.setBackup(backup);
        logicalBackup2.setBackup(backup);

        backupRepository.save(backup);

        RestoreRequest restoreRequest = new RestoreRequest();
        restoreRequest.setMapping(null); // No mapping
        restoreRequest.setStorageName("storage");
        restoreRequest.setBlobPath("blobPath");
        restoreRequest.setFilterCriteria(new FilterCriteria());

        ExternalAdapterRegistrationEntry adapter1 = new ExternalAdapterRegistrationEntry();
        adapter1.setAdapterId(adapterId1);
        PhysicalDatabase physicalDatabase1 = new PhysicalDatabase();
        physicalDatabase1.setAdapter(adapter1);

        ExternalAdapterRegistrationEntry adapter2 = new ExternalAdapterRegistrationEntry();
        adapter2.setAdapterId(adapterId2);
        PhysicalDatabase physicalDatabase2 = new PhysicalDatabase();
        physicalDatabase2.setAdapter(adapter2);

        when(balancingRulesService.applyBalancingRules(eq(logicalBackup1.getType()), any(), eq((String) classifier1.get("microserviceName"))))
                .thenReturn(physicalDatabase1);
        when(balancingRulesService.applyBalancingRules(eq(logicalBackup2.getType()), any(), eq((String) classifier2.get("microserviceName"))))
                .thenReturn(physicalDatabase2);

        DbaasAdapter adapter3 = Mockito.mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAdapterById(eq(adapterId2)))
                .thenReturn(adapter3);
        when(adapter3.isBackupRestoreSupported())
                .thenReturn(true);

        DbaasAdapter adapter4 = Mockito.mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAdapterById(eq(adapterId1)))
                .thenReturn(adapter4);
        when(adapter4.isBackupRestoreSupported())
                .thenReturn(true);

        List<BackupDatabaseDelegate> filteredBackupDbs = backup.getLogicalBackups().stream().flatMap(logicalBackup -> logicalBackup.getBackupDatabases().stream())
                .map(backupDatabase -> new BackupDatabaseDelegate(
                                backupDatabase,
                                backupDatabase.getClassifiers().stream()
                                        .map(classifier -> (SortedMap<String, Object>) new TreeMap<>(classifier))
                                        .toList()
                        )
                ).toList();

        Restore restore = dbBackupV2Service.initializeFullRestoreStructure(backup, restoreRequest);

        assertNotNull(restore);
        assertEquals(backup, restore.getBackup());
        assertEquals("storage", restore.getStorageName());
        assertEquals("blobPath", restore.getBlobPath());
        assertEquals(2, restore.getLogicalRestores().size());

        LogicalRestore logicalRestore1 = restore.getLogicalRestores().stream()
                .filter(lr -> lr.getAdapterId().equals(adapterId1))
                .findFirst()
                .orElseThrow();
        assertEquals(adapterId1, logicalRestore1.getAdapterId());
        assertEquals(1, logicalRestore1.getRestoreDatabases().size());
        assertEquals(backupDatabase1, logicalRestore1.getRestoreDatabases().getFirst().getBackupDatabase());

        assertEquals(List.of(classifier1), logicalRestore1.getRestoreDatabases().getFirst().getClassifiers());

        LogicalRestore logicalRestore2 = restore.getLogicalRestores().stream()
                .filter(lr -> lr.getAdapterId().equals(adapterId2))
                .findFirst()
                .orElseThrow();
        assertEquals(adapterId2, logicalRestore2.getAdapterId());
        assertEquals(1, logicalRestore2.getRestoreDatabases().size());
        assertEquals(backupDatabase2, logicalRestore2.getRestoreDatabases().getFirst().getBackupDatabase());

        assertEquals(List.of(classifier2), logicalRestore2.getRestoreDatabases().getFirst().getClassifiers());

        verify(balancingRulesService, times(1)).applyBalancingRules(eq(logicalBackup1.getType()), any(), eq((String) classifier1.get("microserviceName")));
        verify(balancingRulesService, times(1)).applyBalancingRules(eq(logicalBackup2.getType()), any(), eq((String) classifier2.get("microserviceName")));
    }

    @Test
    void startRestore() {
        String restoreName = "restoreName";
        String adapterId = "adapterId";
        String storageName = "storageName";
        String blobPath = "blobPath";
        String type = "postgresql";
        String restoreDatabaseName1 = "restoreName1";
        String restoreDatabaseName2 = "restoreName2";
        String logicalRestoreName = "logicalRestoreName";

        SortedMap<String, Object> map = new TreeMap<>();
        map.put("namespace", "namespace");
        map.put("microserviceName", "microserviceName");

        BackupDatabase backupDatabase1 = BackupDatabase.builder()
                .name("db1")
                .resources(Map.of())
                .settings(Map.of())
                .users(List.of(BackupDatabase.User.builder()
                        .name("name")
                        .role("role")
                        .build()))
                .classifiers(List.of(map))
                .build();
        BackupDatabase backupDatabase2 = BackupDatabase.builder()
                .name("db2")
                .resources(Map.of())
                .settings(Map.of())
                .users(List.of(BackupDatabase.User.builder()
                        .name("name")
                        .role("role")
                        .build()))
                .classifiers(List.of(map))
                .build();

        LogicalBackup logicalBackup = LogicalBackup.builder()
                .logicalBackupName("logicalBackupName")
                .type("postgresql")
                .backupDatabases(List.of(backupDatabase1, backupDatabase2))
                .build();
        backupDatabase1.setLogicalBackup(logicalBackup);
        backupDatabase2.setLogicalBackup(logicalBackup);

        Backup backup = Backup.builder()
                .name("backupName")
                .storageName(storageName)
                .blobPath(blobPath)
                .externalDatabaseStrategy(ExternalDatabaseStrategy.SKIP)
                .logicalBackups(List.of(logicalBackup))
                .build();
        logicalBackup.setBackup(backup);

        backupRepository.save(backup);

        RestoreDatabase restoreDatabase1 = RestoreDatabase.builder()
                .backupDatabase(backupDatabase1)
                .name(restoreDatabaseName1)
                .classifiers(List.of(map))
                .users(List.of())
                .resources(Map.of())
                .build();
        RestoreDatabase restoreDatabase2 = RestoreDatabase.builder()
                .backupDatabase(backupDatabase2)
                .name(restoreDatabaseName2)
                .classifiers(List.of(map))
                .users(List.of())
                .resources(Map.of())
                .build();

        LogicalRestore logicalRestore = LogicalRestore.builder()
                .adapterId(adapterId)
                .type(type)
                .restoreDatabases(List.of(restoreDatabase1, restoreDatabase2))
                .build();

        restoreDatabase1.setLogicalRestore(logicalRestore);
        restoreDatabase2.setLogicalRestore(logicalRestore);

        Restore restore = Restore.builder()
                .name(restoreName)
                .storageName(storageName)
                .blobPath(blobPath)
                .externalDatabaseStrategy(ExternalDatabaseStrategy.FAIL)
                .backup(backup)
                .logicalRestores(List.of(logicalRestore))
                .build();

        logicalRestore.setRestore(restore);

        LogicalRestoreAdapterResponse response = new LogicalRestoreAdapterResponse(
                "completed",
                null,
                logicalRestoreName,
                LocalDateTime.now(),
                LocalDateTime.now(),
                storageName,
                blobPath,
                List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                                .status("completed")
                                .previousDatabaseName("db1")
                                .path("path")
                                .databaseName("newDb1")
                                .duration(1)
                                .build(),
                        LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                                .status("completed")
                                .previousDatabaseName("db2")
                                .path("path")
                                .databaseName("newDb2")
                                .duration(1)
                                .build()
                )
        );

        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAdapterById(adapterId))
                .thenReturn(adapter);
        when(adapter.restoreV2(any(), anyBoolean(), any()))
                .thenReturn(response);

        dbBackupV2Service.startRestore(restore);

        Restore actualRestore = restoreRepository.findById(restoreName);
        assertNotNull(actualRestore);
        assertEquals(restoreName, actualRestore.getName());
        assertEquals(blobPath, actualRestore.getBlobPath());
        assertEquals(storageName, actualRestore.getStorageName());

        LogicalRestore actualLogicalRestore = actualRestore.getLogicalRestores().getFirst();
        assertNotNull(actualLogicalRestore);
        assertEquals(logicalRestoreName, actualLogicalRestore.getLogicalRestoreName());
        assertEquals(adapterId, actualLogicalRestore.getAdapterId());
        assertEquals(type, actualLogicalRestore.getType());
        assertEquals(2, actualLogicalRestore.getRestoreDatabases().size());

        RestoreDatabase actualRestoreDatabase1 = logicalRestore.getRestoreDatabases()
                .stream().filter(restoreDatabase -> restoreDatabaseName1.equals(restoreDatabase.getName())).toList().getFirst();
        assertNotNull(actualRestoreDatabase1);
        assertEquals(backupDatabase1, actualRestoreDatabase1.getBackupDatabase());
        assertEquals(List.of(map), actualRestoreDatabase1.getClassifiers());

        RestoreDatabase actualRestoreDatabase2 = logicalRestore.getRestoreDatabases()
                .stream().filter(restoreDatabase -> restoreDatabaseName2.equals(restoreDatabase.getName())).toList().getFirst();
        assertNotNull(actualRestoreDatabase2);
        assertEquals(backupDatabase2, actualRestoreDatabase2.getBackupDatabase());
        assertEquals(List.of(map), actualRestoreDatabase2.getClassifiers());

        verify(physicalDatabasesService, times(1)).getAdapterById(adapterId);
        verify(adapter, times(1)).restoreV2(any(), anyBoolean(), any());

    }

    @Test
    void trackAndAggregateRestore() {
        String restoreName = "restoreName";
        String adapterId = "adapterId";
        String storageName = "storageName";
        String blobPath = "blobPath";
        String type = "postgresql";
        String restoreDatabaseName1 = "restoreName1";
        String restoreDatabaseName2 = "restoreName2";
        String logicalRestoreName = "logicalRestoreName";

        SortedMap<String, Object> map = new TreeMap<>();
        map.put("namespace", "namespace");

        BackupDatabase backupDatabase1 = BackupDatabase.builder()
                .name("db1")
                .resources(Map.of())
                .settings(Map.of())
                .users(List.of(BackupDatabase.User.builder()
                        .name("name")
                        .role("role")
                        .build()))
                .classifiers(List.of(map))
                .build();
        BackupDatabase backupDatabase2 = BackupDatabase.builder()
                .name("db2")
                .resources(Map.of())
                .settings(Map.of())
                .users(List.of(BackupDatabase.User.builder()
                        .name("name")
                        .role("role")
                        .build()))
                .classifiers(List.of(map))
                .build();

        LogicalBackup logicalBackup = LogicalBackup.builder()
                .logicalBackupName("logicalBackupName")
                .type("postgresql")
                .backupDatabases(List.of(backupDatabase1, backupDatabase2))
                .build();
        backupDatabase1.setLogicalBackup(logicalBackup);
        backupDatabase2.setLogicalBackup(logicalBackup);

        Backup backup = Backup.builder()
                .name("backupName")
                .storageName(storageName)
                .blobPath(blobPath)
                .externalDatabaseStrategy(ExternalDatabaseStrategy.SKIP)
                .logicalBackups(List.of(logicalBackup))
                .build();
        logicalBackup.setBackup(backup);

        backupRepository.save(backup);

        RestoreDatabase restoreDatabase1 = RestoreDatabase.builder()
                .backupDatabase(backupDatabase1)
                .name(restoreDatabaseName1)
                .classifiers(List.of(map))
                .users(List.of())
                .resources(Map.of())
                .build();
        RestoreDatabase restoreDatabase2 = RestoreDatabase.builder()
                .backupDatabase(backupDatabase2)
                .name(restoreDatabaseName2)
                .classifiers(List.of(map))
                .users(List.of())
                .resources(Map.of())
                .build();

        LogicalRestore logicalRestore = LogicalRestore.builder()
                .adapterId(adapterId)
                .logicalRestoreName(logicalRestoreName)
                .type(type)
                .status(RestoreTaskStatus.IN_PROGRESS)
                .restoreDatabases(List.of(restoreDatabase1, restoreDatabase2))
                .build();

        restoreDatabase1.setLogicalRestore(logicalRestore);
        restoreDatabase2.setLogicalRestore(logicalRestore);

        Restore restore = Restore.builder()
                .name(restoreName)
                .storageName(storageName)
                .blobPath(blobPath)
                .backup(backup)
                .logicalRestores(List.of(logicalRestore))
                .build();

        logicalRestore.setRestore(restore);

        LogicalRestoreAdapterResponse response = new LogicalRestoreAdapterResponse(
                "completed",
                null,
                logicalRestoreName,
                LocalDateTime.now(),
                LocalDateTime.now(),
                storageName,
                blobPath,
                List.of(LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                                .status("completed")
                                .previousDatabaseName(restoreDatabaseName1)
                                .path("path")
                                .databaseName("newDb1")
                                .duration(1)
                                .build(),
                        LogicalRestoreAdapterResponse.RestoreDatabaseResponse.builder()
                                .status("completed")
                                .previousDatabaseName(restoreDatabaseName2)
                                .path("path")
                                .databaseName("newDb2")
                                .duration(1)
                                .build()
                )
        );

        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAdapterById(adapterId))
                .thenReturn(adapter);
        when(adapter.trackRestoreV2(logicalRestoreName, restore.getStorageName(), restore.getBlobPath()))
                .thenReturn(response);

        dbBackupV2Service.trackAndAggregateRestore(restore);

        Restore expected = restoreRepository.findById(restoreName);
        assertNotNull(restore);
        assertEquals(1, restore.getAttemptCount());
        assertNotNull(restore.getStatus());

        assertEquals(RestoreStatus.COMPLETED, restore.getStatus());
        assertEquals(2, restore.getTotal());
        assertEquals(2, restore.getCompleted());
        assertTrue(restore.getErrorMessage().isBlank());

        LogicalRestore expectedLogical = expected.getLogicalRestores().getFirst();
        assertNotNull(expectedLogical);
        assertNotNull(expectedLogical.getStatus());
        assertEquals(RestoreTaskStatus.COMPLETED, expectedLogical.getStatus());

    }

    @Test
    void trackAndAggregateRestore_attemptExceeded() {
        String restoreName = "name";
        Restore restore = Restore.builder()
                .name(restoreName)
                .blobPath("blobPath")
                .externalDatabaseStrategy(ExternalDatabaseStrategy.FAIL)
                .storageName("storageName")
                .attemptCount(21)
                .logicalRestores(List.of())
                .status(RestoreStatus.IN_PROGRESS)
                .build();

        dbBackupV2Service.trackAndAggregateRestore(restore);

        Restore expected = restoreRepository.findById(restoreName);

        assertNotNull(expected);
        assertEquals(21, expected.getAttemptCount());
        assertEquals(RestoreStatus.FAILED, expected.getStatus());
    }

    @Test
    void initializeLogicalDatabasesFromRestore() {
        String adapterId = "adapterId";
        String dbName1 = "dbName1";
        String dbName2 = "dbName2";
        SortedMap<String, Object> classifier1 = new TreeMap<>();
        classifier1.put("namespace", "namespace1");
        classifier1.put("microserviceName", "microserviceName1");
        classifier1.put("scope", "service");

        SortedMap<String, Object> classifier2 = new TreeMap<>();
        classifier2.put("namespace", "namespace2");
        classifier2.put("microserviceName", "microserviceName2");
        classifier2.put("scope", "service");

        RestoreDatabase restoreDatabase1 = RestoreDatabase.builder()
                .name(dbName1)
                .classifiers(List.of(classifier1, classifier2))
                .settings(Map.of(
                        "setting", "setting"
                ))
                .users(List.of(
                        new RestoreDatabase.User(
                                "name1",
                                "role1"
                        )
                ))
                .build();

        SortedMap<String, Object> classifier3 = new TreeMap<>();
        classifier3.put("namespace", "namespace3");
        classifier3.put("microserviceName", "microserviceName3");
        classifier3.put("scope", "service");

        SortedMap<String, Object> classifier4 = new TreeMap<>();
        classifier4.put("namespace", "namespace4");
        classifier4.put("microserviceName", "microserviceName4");
        classifier4.put("scope", "service");

        RestoreDatabase restoreDatabase2 = RestoreDatabase.builder()
                .name(dbName2)
                .classifiers(List.of(classifier3, classifier4))
                .settings(Map.of(
                        "setting", "setting"
                ))
                .users(List.of(
                        new RestoreDatabase.User(
                                "name1",
                                "role1"
                        )
                ))
                .build();

        LogicalRestore logicalRestore = LogicalRestore.builder()
                .logicalRestoreName("logicalRestoreName")
                .adapterId(adapterId)
                .type("type")
                .restoreDatabases(List.of(restoreDatabase1, restoreDatabase2))
                .build();

        Restore restore = Restore.builder()
                .name("restoreName")
                .logicalRestores(List.of(logicalRestore))
                .build();


        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setClassifier(classifier1);
        databaseRegistry.setNamespace("namespace1");
        databaseRegistry.setType("type");

        SortedMap<String, Object> anotherClassifier = new TreeMap<>();
        anotherClassifier.put("namespace", "anotherNamespace");
        anotherClassifier.put("microserviceName", "anotherMicroserviceName");
        anotherClassifier.put("scope", "service");
        anotherClassifier.put("type", "type");

        DatabaseRegistry databaseRegistry1 = new DatabaseRegistry();
        databaseRegistry1.setClassifier(anotherClassifier);
        databaseRegistry1.setType("type");

        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setClassifier(anotherClassifier);
        database.setType("type");
        databaseRegistry.setDatabase(database);
        databaseRegistry1.setDatabase(database);
        database.setDatabaseRegistry(List.of(databaseRegistry1, databaseRegistry));

        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(UUID.randomUUID().toString());

        databaseRegistryDbaasRepository.saveAnyTypeLogDb(databaseRegistry);

        when(physicalDatabasesService.getAdapterById(adapterId))
                .thenReturn(adapter);
        when(physicalDatabasesService.getByAdapterId(adapterId))
                .thenReturn(physicalDatabase);
        when(adapter.identifier())
                .thenReturn(adapterId);

        DbResource dbResource1 = new DbResource();
        dbResource1.setId(UUID.randomUUID());
        dbResource1.setName("name1");
        dbResource1.setKind("kind1");

        EnsuredUser ensuredUser1 = new EnsuredUser(
                "dbName1",
                Map.of(
                        "key", "value"
                ),
                List.of(dbResource1),
                true
        );

        DbResource dbResource2 = new DbResource();
        dbResource2.setId(UUID.randomUUID());
        dbResource2.setName("name2");
        dbResource2.setKind("kind2");

        EnsuredUser ensuredUser2 = new EnsuredUser(
                "dbName2",
                Map.of(
                        "key", "value"
                ),
                List.of(dbResource2),
                true
        );

        when(adapter.ensureUser(any(), any(), any(), any())).thenAnswer(invocation -> {
            String dbName = invocation.getArgument(2, String.class);
            if ("dbName1".equals(dbName)) {
                return ensuredUser1;
            } else if ("dbName2".equals(dbName)) {
                return ensuredUser2;
            }
            return null;
        });

        Map<String, List<EnsuredUser>> dbNameToEnsuredUsers = restore.getLogicalRestores().stream().flatMap(lr -> lr.getRestoreDatabases().stream()
                        .map(rd -> dbBackupV2Service.ensureUsers(lr.getAdapterId(), rd.getName(), rd.getUsers())))
                .flatMap(m -> m.entrySet().stream())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));

        dbBackupV2Service.initializeLogicalDatabasesFromRestore(restore, dbNameToEnsuredUsers);

        List<DatabaseRegistry> databaseRegistries1 = databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace("namespace2");
        Database database1 = databaseRegistries1.getFirst().getDatabase();
        assertNotNull(database1);
        assertEquals(restoreDatabase1.getSettings(), database1.getSettings());
        assertEquals(3, database1.getDatabaseRegistry().size());

        DatabaseRegistry actualRegistry1 = database1.getDatabaseRegistry().stream()
                .filter(registry -> classifier1.equals(registry.getClassifier()))
                .findFirst()
                .orElse(null);
        DatabaseRegistry actualRegistry2 = database1.getDatabaseRegistry().stream()
                .filter(registry -> classifier2.equals(registry.getClassifier()))
                .findFirst()
                .orElse(null);
        DatabaseRegistry actualRegistry3 = database1.getDatabaseRegistry().stream()
                .filter(registry -> anotherClassifier.equals(registry.getClassifier()))
                .findFirst()
                .orElse(null);

        assertNotNull(actualRegistry1);
        assertNotNull(actualRegistry2);
        assertNotNull(actualRegistry3);

        List<DatabaseRegistry> databaseRegistries2 = databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace("namespace3");
        Database database2 = databaseRegistries2.getFirst().getDatabase();
        assertNotNull(database2);
        assertEquals(restoreDatabase2.getSettings(), database2.getSettings());
        assertEquals(2, database2.getDatabaseRegistry().size());

        DatabaseRegistry actualRegistry4 = database2.getDatabaseRegistry().stream()
                .filter(registry -> classifier3.equals(registry.getClassifier()))
                .findFirst()
                .orElse(null);
        DatabaseRegistry actualRegistry5 = database2.getDatabaseRegistry().stream()
                .filter(registry -> classifier4.equals(registry.getClassifier()))
                .findFirst()
                .orElse(null);

        assertNotNull(actualRegistry4);
        assertNotNull(actualRegistry5);
    }

    private BackupResponse generateBackupResponse(String backupName, String namespace) {
        SortedMap<String, Object> sortedMap = new TreeMap<>();
        sortedMap.put("key-first", Map.of("inner-key", "inner-value"));
        sortedMap.put("key-second", Map.of("inner-key", "inner-value"));

        BackupDatabaseResponse backupDatabaseResponse = new BackupDatabaseResponse(
                "backup-database",
                List.of(sortedMap),
                Map.of("settings-key", "settings-value"),
                List.of(BackupDatabaseResponse.User.builder()
                        .name("name")
                        .role("role")
                        .build()),
                Map.of("key", "value"),
                true,
                BackupTaskStatus.COMPLETED,
                1,
                1,
                "path",
                null,
                LocalDateTime.now()
        );

        LogicalBackupResponse logicalBackupResponse = new LogicalBackupResponse(
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
        filterCriteria.setFilter(List.of(filter));

        SortedMap<String, Object> map = new TreeMap<>();
        map.put("key", "value");

        BackupExternalDatabaseResponse backupExternalDatabase = new BackupExternalDatabaseResponse();
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

    private List<LogicalBackup> generateLogicalBackups(int count) {
        List<LogicalBackup> logicalBackups = new ArrayList<>();

        for (int i = 0; i < count; i++) {

            LogicalBackup logicalBackup = new LogicalBackup();
            logicalBackup.setLogicalBackupName(null);
            logicalBackup.setAdapterId(String.valueOf(i));
            logicalBackup.setType("test-type");

            logicalBackup.setStatus(BackupTaskStatus.NOT_STARTED);

            SortedMap<String, Object> sortedMap = new TreeMap<>();
            sortedMap.put("key-first", Map.of("inner-key", "inner-value"));
            sortedMap.put("key-second", Map.of("inner-key", "inner-value"));

            BackupDatabase backupDatabase1 = new BackupDatabase();
            backupDatabase1.setLogicalBackup(logicalBackup);
            backupDatabase1.setName("db1-" + i);
            backupDatabase1.setSettings(Map.of("key", Map.of("inner-key", "inner-value")));
            backupDatabase1.setClassifiers(List.of(sortedMap));
            backupDatabase1.setUsers(List.of(BackupDatabase.User.builder()
                    .name("name")
                    .role("role")
                    .build()));
            backupDatabase1.setResources(Map.of("key", "value"));
            backupDatabase1.setStatus(BackupTaskStatus.COMPLETED);
            backupDatabase1.setSize(1);
            backupDatabase1.setDuration(1L);
            backupDatabase1.setPath("path");

            BackupDatabase backupDatabase2 = new BackupDatabase();
            backupDatabase2.setLogicalBackup(logicalBackup);
            backupDatabase2.setName("db2-" + i);
            backupDatabase2.setSettings(Map.of("key", Map.of("inner-key", "inner-value")));
            backupDatabase2.setClassifiers(List.of(sortedMap));
            backupDatabase2.setUsers(List.of(BackupDatabase.User.builder()
                    .name("name")
                    .role("role")
                    .build()));
            backupDatabase2.setResources(Map.of("key", "value"));
            backupDatabase2.setStatus(BackupTaskStatus.COMPLETED);
            backupDatabase2.setDuration(1L);
            backupDatabase2.setSize(1);
            backupDatabase1.setPath("path");

            backupDatabase1.setLogicalBackup(logicalBackup);
            backupDatabase2.setLogicalBackup(logicalBackup);
            logicalBackup.setBackupDatabases(List.of(backupDatabase1, backupDatabase2));

            logicalBackups.add(logicalBackup);
        }

        return logicalBackups;
    }

    private static @NotNull List<Database> getDatabases(String dbName, String namespace) {
        List<Database> databases = new ArrayList<>();
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", namespace);

        DbState dbState = new DbState();
        dbState.setId(UUID.randomUUID());
        dbState.setState(AbstractDbState.DatabaseStateStatus.CREATED);
        dbState.setDatabaseState(AbstractDbState.DatabaseStateStatus.CREATED);
        dbState.setDescription("descripton");
        dbState.setPodName("podName");

        for (int i = 0; i < 4; i++) {
            int k = i % 2 == 0 ? 1 : 2;

            Database database = new Database();
            database.setAdapterId(String.valueOf(k));
            database.setName(dbName + i);
            database.setDbState(dbState);

            DatabaseRegistry databaseRegistry = new DatabaseRegistry();
            databaseRegistry.setNamespace(namespace);
            databaseRegistry.setDatabase(database);
            databaseRegistry.setClassifier(classifier);
            database.setDatabaseRegistry(new ArrayList<>());
            database.getDatabaseRegistry().add(databaseRegistry);
            database.setSettings(new HashMap<>());
            databaseRegistry.setConnectionProperties(List.of());
            databaseRegistry.setResources(List.of());
            databases.add(database);
        }
        return databases;
    }


    private static Backup createBackup(String name, List<LogicalBackup> logicalBackups) {
        FilterCriteriaEntity filterCriteria = FilterCriteriaEntity.builder().build();
        Backup backup = new Backup();
        backup.setName(name);
        backup.setLogicalBackups(logicalBackups);
        backup.setFilterCriteria(filterCriteria);
        backup.setBlobPath("path");
        backup.setStorageName("storagename");
        backup.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);
        return backup;
    }

    private static BackupRequest createBackupRequest(String backupName, String namespace) {
        Filter filter = new Filter();
        filter.setNamespace(List.of(namespace));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setFilter(List.of(filter));

        BackupRequest dto = new BackupRequest();
        dto.setFilterCriteria(filterCriteria);
        dto.setBackupName(backupName);
        dto.setExternalDatabaseStrategy(ExternalDatabaseStrategy.FAIL);
        dto.setBlobPath("blobPath");
        dto.setStorageName("storageName");
        dto.setIgnoreNotBackupableDatabases(true);
        return dto;
    }
}
