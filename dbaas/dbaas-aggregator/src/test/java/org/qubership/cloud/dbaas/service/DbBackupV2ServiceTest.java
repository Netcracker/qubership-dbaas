package org.qubership.cloud.dbaas.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.qubership.cloud.dbaas.dto.backupV2.*;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.backupV2.*;
import org.qubership.cloud.dbaas.exceptions.BackupExecutionException;
import org.qubership.cloud.dbaas.exceptions.DBBackupValidationException;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BackupDatabaseRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BackupRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.LogicalBackupRepository;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Slf4j
@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class DbBackupV2ServiceTest {

    @InjectMock
    private DatabaseDbaasRepository databaseDbaasRepository;

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


    @BeforeAll
    static void globalSetUp() {
        DbBackupV2Service.TRACK_DELAY_MS = 0;
    }

    @AfterEach
    void setUp() {
        backupDatabaseRepository.deleteAll();
        logicalBackupRepository.deleteAll();
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

        LogicalBackupStatus logicalBackupStatusSuccess = new LogicalBackupStatus();
        logicalBackupStatusSuccess.setDatabases(constructInnerDbs());
        logicalBackupStatusSuccess.setStatus(Status.COMPLETED);

        LogicalBackupStatus logicalBackupStatusFail = new LogicalBackupStatus();
        logicalBackupStatusFail.setDatabases(constructInnerDbs());
        logicalBackupStatusFail.setStatus(Status.FAILED);

        LogicalBackupStatus logicalBackupStatusProceeding = new LogicalBackupStatus();
        logicalBackupStatusProceeding.setDatabases(constructInnerDbs());
        logicalBackupStatusProceeding.getDatabases().getFirst().setDuration("1");
        logicalBackupStatusProceeding.setStatus(Status.IN_PROGRESS);

        LogicalBackupStatus logicalBackupStatusProceeding2 = new LogicalBackupStatus();
        logicalBackupStatusProceeding2.setDatabases(constructInnerDbs());
        logicalBackupStatusProceeding2.getDatabases().getFirst().setDuration("2");
        logicalBackupStatusProceeding2.setStatus(Status.IN_PROGRESS);

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
                .thenReturn(logicalBackupNameOne);
        when(adapterTwo.backupV2(any()))
                .thenReturn(logicalBackupNameTwo);

        when(adapterOne.trackBackupV2(eq(logicalBackupNameOne)))
                .thenReturn(logicalBackupStatusSuccess);
        when(adapterTwo.trackBackupV2(eq(logicalBackupNameTwo)))
                .thenReturn(logicalBackupStatusProceeding)
                .thenReturn(logicalBackupStatusProceeding2)
                .thenReturn(logicalBackupStatusFail);

        log.info(logicalBackupStatusSuccess.toString());
        log.info(logicalBackupStatusFail.toString());

        dbBackupV2Service.backup(backupRequest);

        List<LogicalBackup> logicalBackups = logicalBackupRepository.getByBackupName(backupName);

        LogicalBackup logicalBackupFirst = logicalBackups.getFirst();
        LogicalBackup logicalBackupLast = logicalBackups.getLast();
        log.info(logicalBackups.toString());
        log.info(logicalBackupFirst.toString());
        log.info(logicalBackupLast.toString());

        assertEquals(2, logicalBackups.size());
        assertTrue(
                logicalBackupNameOne.equals(logicalBackupFirst.getLogicalBackupName()) ||
                        logicalBackupNameTwo.equals(logicalBackupFirst.getLogicalBackupName()),
                "Logical backup name should match one of the expected names"
        );
        assertEquals(3, logicalBackupFirst.getStatus().getDatabases().size());
        assertEquals(3, logicalBackupLast.getStatus().getDatabases().size());

        Backup backup = backupRepository.findById(backupName);

        assertEquals(Status.FAILED, backup.getStatus().getStatus());
        assertEquals(6, backup.getStatus().getSize());

        List<BackupDatabase> backupDatabaseList = backupDatabaseRepository.findAll().stream().toList();

        long innerDbOneCount = backupDatabaseList.stream()
                .filter(b -> b.getLogicalBackup().getLogicalBackupName().equals(logicalBackupNameOne)).count();
        long innerDbLastCount = backupDatabaseList.stream()
                .filter(b -> b.getLogicalBackup().getLogicalBackupName().equals(logicalBackupNameTwo)).count();

        assertEquals(6, backupDatabaseList.size());
        assertEquals(3, innerDbOneCount);
        assertEquals(3, innerDbLastCount);

        Mockito.verify(adapterOne, times(1)).backupV2(any());
        Mockito.verify(adapterTwo, times(1)).backupV2(any());

        Mockito.verify(adapterOne, times(1)).trackBackupV2(any());
        Mockito.verify(adapterTwo, times(3)).trackBackupV2(any());
    }

    @Test
    void backup_shouldReturnBadRequest_namespaceReturnEmptyList() {
        BackupRequest backupRequest = createBackupRequest("test-backup", "test-namespace");
        assertThrows(BackupExecutionException.class, () ->
                dbBackupV2Service.backup(backupRequest));
    }

    @Test
    void backup__backupNameAlreadyExist() {
        String namespace = "test-namespace";
        String backupName = "test-backup";

        BackupRequest backupRequest = createBackupRequest(backupName, namespace);

        Backup backup = createBackup(backupName, List.of());
        backupRepository.save(backup);

        when(dbBackupV2Service.getAllDbByNamespace(namespace))
                .thenReturn(List.of(new Database()));

        assertThrows(DBBackupValidationException.class, () ->
                dbBackupV2Service.backup(backupRequest));
    }

    @Test
    void backup_mustThrowException_whenTrackBackupReturnException() {
        String namespace = "test-namespace";
        String adapterId = "1";
        String logicalBackupName = "logicalBackupName1";
        String backupName = "backup123";
        String dbName = "db-name";

        BackupRequest backupRequest = createBackupRequest(backupName, namespace);

        Database database = new Database();
        database.setAdapterId(adapterId);
        database.setName(dbName);

        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setNamespace(namespace);
        databaseRegistry.setDatabase(database);

        database.setDatabaseRegistry(new ArrayList<>());
        database.getDatabaseRegistry().add(databaseRegistry);
        database.setSettings(new HashMap<>());
        databaseRegistry.setConnectionProperties(List.of());
        databaseRegistry.setResources(List.of());

        DbaasAdapter adapterOne = mock(DbaasAdapter.class);

        when(databaseDbaasRepository.findAnyLogDbTypeByNamespace(namespace))
                .thenReturn(List.of(database));

        when(physicalDatabasesService.getAdapterById(adapterId))
                .thenReturn(adapterOne);

        when(adapterOne.type()).thenReturn("PGSQL");
        when(adapterOne.backupV2(any()))
                .thenReturn(logicalBackupName);
        when(adapterOne.trackBackupV2(eq(logicalBackupName)))
                .thenThrow(new WebApplicationException("Timeout"));

        dbBackupV2Service.backup(backupRequest);

        List<LogicalBackup> logicalBackups = logicalBackupRepository.getByBackupName(backupName);
        LogicalBackup logicalBackupFirst = logicalBackups.getFirst();

        assertEquals(Status.FAILED, logicalBackupFirst.getStatus().getStatus());
        assertTrue(logicalBackupFirst.getStatus().getErrorMessage().contains("Timeout"));

        Mockito.verify(adapterOne, times(1)).backupV2(any());
        Mockito.verify(adapterOne, times(3)).trackBackupV2(any());
    }

    @Test
    void startBackup_whenValidInput_thenBackupIsInitialized() {
        //when
        int logicalBackupCount = 5;
        List<LogicalBackup> logicalBackups = generateLogicalBackups(logicalBackupCount);

        Backup backup = createBackup("name", logicalBackups);

        DbaasAdapter adapter = mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAdapterById(any()))
                .thenReturn(adapter);

        for (int i = 0; i < logicalBackupCount; i++) {
            List<String> dbNames = logicalBackups.get(i).getBackupDatabases().stream()
                    .map(BackupDatabase::getName).toList();

            when(adapter.backupV2(dbNames))
                    .thenReturn("backup" + logicalBackups.get(i).getAdapterId());
        }

        //then
        dbBackupV2Service.startBackup(backup);

        //check
        List<LogicalBackup> logicalBackupFromRepository = logicalBackupRepository.findAll().stream().toList();
        LogicalBackup logicalBackup = logicalBackupFromRepository.getFirst();

        assertEquals(logicalBackupCount, logicalBackupFromRepository.size());

        for (int i = 0; i < logicalBackupFromRepository.size(); i++) {
            assertEquals("backup" + logicalBackup.getAdapterId(), logicalBackup.getLogicalBackupName());
            List<String> dbNames = logicalBackups.get(i).getBackupDatabases().stream()
                    .map(BackupDatabase::getName).toList();
            Mockito.verify(adapter, times(1))
                    .backupV2(dbNames);
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

        List<Database> databaseList = List.of(databaseFirst, databaseSecond);
        String backupName = "test-backup";

        BackupRequest backupRequest = createBackupRequest(backupName, "namespace");

        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(any()))
                .thenReturn(adapter);
        when(adapter.type())
                .thenReturn("psql");

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
    }

    @Test
    void updateAggregatedStatus_shouldReturnProceeding_containAllStatuses() {
        String logicalBackupName = "mock-logical-backup-name";
        String adapterId = "some-adapter-id";

        LogicalBackupStatus.Database db1 = LogicalBackupStatus.Database.builder()
                .databaseName("db1")
                .status(Status.COMPLETED)
                .size(1)
                .duration("1")
                .path("somepath")
                .build();

        LogicalBackupStatus.Database db2 = LogicalBackupStatus.Database.builder()
                .databaseName("db2")
                .status(Status.FAILED)
                .size(1)
                .duration("1")
                .errorMessage("errorMessage")
                .path("somepath")
                .build();

        LogicalBackupStatus.Database db3 = LogicalBackupStatus.Database.builder()
                .databaseName("db3")
                .status(Status.IN_PROGRESS)
                .size(1)
                .duration("1")
                .path("somepath")
                .build();

        LogicalBackupStatus logicalBackupStatus = LogicalBackupStatus.builder()
                .status(Status.PENDING)
                .errorMessage("errorMessage")
                .databases(List.of(db1, db2, db3))
                .build();

        LogicalBackup logicalBackup = new LogicalBackup();
        logicalBackup.setLogicalBackupName(logicalBackupName);
        logicalBackup.setAdapterId(adapterId);
        logicalBackup.setStatus(logicalBackupStatus);
        logicalBackup.setBackupDatabases(List.of(BackupDatabase.builder().id(UUID.randomUUID()).build()));

        LogicalBackup logicalBackup1 = new LogicalBackup();
        logicalBackup1.setLogicalBackupName(logicalBackupName);
        logicalBackup1.setStatus(logicalBackupStatus);
        logicalBackup1.setBackupDatabases(List.of(BackupDatabase.builder().id(UUID.randomUUID()).build()));

        Backup backup = createBackup("name", List.of(logicalBackup, logicalBackup1));

        dbBackupV2Service.updateAggregatedStatus(backup);

        BackupStatus backupStatus = backup.getStatus();
        //check
        assertEquals(Status.PENDING, backupStatus.getStatus());
        assertEquals(2, backupStatus.getTotal());
        assertEquals(2, backupStatus.getCompleted());
        assertEquals(6, backupStatus.getSize());

    }

    @Test
    void aggregateStatus_shouldReturnProceeding_whenContainAllStatuses() {
        Set<Status> statuses = Set.of(Status.NOT_STARTED, Status.PENDING, Status.IN_PROGRESS, Status.FAILED, Status.COMPLETED);
        Status status = dbBackupV2Service.aggregateStatus(statuses);

        assertNotNull(status);
        assertEquals(Status.IN_PROGRESS, status);
    }

    @Test
    void aggregateStatus_shouldReturnProceeding_whenInputPendingFailSuccess() {
        Set<Status> statuses = Set.of(Status.PENDING, Status.FAILED, Status.COMPLETED);
        Status status = dbBackupV2Service.aggregateStatus(statuses);

        assertNotNull(status);
        assertEquals(Status.IN_PROGRESS, status);
    }

    @Test
    void aggregateStatus_shouldReturnProceeding_whenInputInProgressFailSuccess() {
        Set<Status> statuses = Set.of(Status.IN_PROGRESS, Status.FAILED, Status.COMPLETED);
        Status status = dbBackupV2Service.aggregateStatus(statuses);

        assertNotNull(status);
        assertEquals(Status.IN_PROGRESS, status);
    }

    @Test
    void aggregateStatus_shouldReturnProceeding_whenInputNotStartedFailSuccess() {
        Set<Status> statuses = Set.of(Status.NOT_STARTED, Status.FAILED, Status.COMPLETED);
        Status status = dbBackupV2Service.aggregateStatus(statuses);

        assertNotNull(status);
        assertEquals(Status.IN_PROGRESS, status);
    }

    @Test
    void aggregateStatus_shouldReturnProceeding_whenInputFailSuccess() {
        Set<Status> statuses = Set.of(Status.FAILED, Status.COMPLETED);
        Status status = dbBackupV2Service.aggregateStatus(statuses);

        assertNotNull(status);
        assertEquals(Status.FAILED, status);
    }

    @Test
    void aggregateStatus_shouldReturnProceeding_whenInputSuccess() {
        Set<Status> statuses = Set.of(Status.COMPLETED);
        Status status = dbBackupV2Service.aggregateStatus(statuses);

        assertNotNull(status);
        assertEquals(Status.COMPLETED, status);
    }

    @Test
    void trackStatus_DuringOngoingBackup_statusExists() throws InterruptedException {
        String namespace = "test-namespace";
        String adapterId = "1";
        String backupName = "backup123";
        String logicalBackupName = "logicalBackupName1";

        BackupRequest backupRequest = createBackupRequest(backupName, namespace);

        SortedMap<String, Object> sortedMap = new TreeMap<>();
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setClassifier(sortedMap);

        Database database = new Database();
        database.setName("db1");
        database.setAdapterId(adapterId);
        database.setDatabaseRegistry(List.of(databaseRegistry));
        database.setConnectionProperties(List.of(Map.of()));


        List<Database> databases = List.of(database);
        when(databaseDbaasRepository.findAnyLogDbTypeByNamespace(namespace))
                .thenReturn(databases);

        DbaasAdapter adapter = mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAdapterById(adapterId)).thenReturn(adapter);
        when(adapter.type()).thenReturn("postgresql");
        when(adapter.backupV2(any())).thenReturn(logicalBackupName);

        LogicalBackupStatus inProgressStatus = new LogicalBackupStatus();
        inProgressStatus.setStatus(Status.IN_PROGRESS);
        inProgressStatus.setDatabases(List.of(
                new LogicalBackupStatus.Database("db1",
                        Status.IN_PROGRESS,
                        1, null,
                        null,
                        null)));

        LogicalBackupStatus successStatus = new LogicalBackupStatus();
        successStatus.setStatus(Status.COMPLETED);
        successStatus.setDatabases(List.of(
                new LogicalBackupStatus.Database("db1",
                        Status.COMPLETED,
                        1, null,
                        null,
                        null)));

        CountDownLatch trackStarted = new CountDownLatch(1);
        CountDownLatch continueTrack = new CountDownLatch(1);
        CountDownLatch inProgressStarted = new CountDownLatch(1);
        CountDownLatch continueInProgress = new CountDownLatch(1);

        when(adapter.trackBackupV2(logicalBackupName))
                .thenAnswer(invocation -> {
                    trackStarted.countDown();
                    continueTrack.await();
                    return inProgressStatus;
                })
                .thenAnswer(invocation -> {
                    inProgressStarted.countDown();
                    continueInProgress.await();
                    return successStatus;
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> dbBackupV2Service.backup(backupRequest));

        trackStarted.await();

        BackupStatusResponse expectedNotStarted = dbBackupV2Service.getCurrentStatus(backupName);
        assertEquals(Status.NOT_STARTED, expectedNotStarted.getStatus());

        continueTrack.countDown();
        inProgressStarted.await();

        BackupStatusResponse expectedProceeding = dbBackupV2Service.getCurrentStatus(backupName);
        assertEquals(Status.IN_PROGRESS, expectedProceeding.getStatus());

        continueInProgress.countDown();

        executor.shutdown();
        executor.awaitTermination(3, TimeUnit.SECONDS);

        Backup byId = backupRepository.findById(backupName);
        assertEquals(Status.COMPLETED, byId.getStatus().getStatus());

        BackupStatusResponse expectedSuccess = dbBackupV2Service.getCurrentStatus(backupName);
        assertEquals(Status.COMPLETED, expectedSuccess.getStatus());
    }

    @Test
    void getBackupMetadata() {
        String backupName = "backupName";
        List<LogicalBackup> logicalBackups = generateLogicalBackups(1);
        Backup backup = createBackup(backupName, logicalBackups);

        logicalBackups.forEach(lb -> lb.setBackup(backup));

        backupRepository.save(backup);

        BackupResponse response = dbBackupV2Service.getBackupMetadata(backupName);

        assertNotNull(response);
        assertEquals("backupName", response.getBackupName());
        assertEquals("storagename", response.getStorageName());
        assertEquals("path", response.getBlobPath());
        assertEquals(ExternalDatabaseStrategy.SKIP, response.getExternalDatabaseStrategy());
        assertFalse(response.isIgnoreNotBackupableDatabases());

        BackupStatusResponse status = response.getStatus();
        assertEquals(Status.COMPLETED, status.getStatus());
        assertEquals(1, status.getTotal());
        assertEquals(1, status.getCompleted());
        assertEquals(1, status.getSize());
        assertNull(status.getErrorMessage());

        List<LogicalBackupResponse> logicalBackupsResponse = response.getLogicalBackups();
        assertEquals(1, logicalBackupsResponse.size());

        LogicalBackupResponse logicalBackup = logicalBackupsResponse.getFirst();
        assertEquals("test-type", logicalBackup.getType());
        assertEquals(Status.NOT_STARTED, logicalBackup.getStatus().getStatus());

        List<LogicalBackupStatusResponse.Database> dbStatuses =
                logicalBackup.getStatus().getDatabases();
        assertEquals(2, dbStatuses.size());

        assertEquals("db1-0", dbStatuses.getFirst().getDatabaseName());
        assertEquals(Status.COMPLETED, dbStatuses.getFirst().getStatus());
        assertEquals("duration", dbStatuses.getFirst().getDuration());

        assertEquals("db2-0", dbStatuses.get(1).getDatabaseName());
        assertEquals(Status.COMPLETED, dbStatuses.get(1).getStatus());

        List<BackupDatabaseResponse> dbs = logicalBackup.getBackupDatabases();
        assertEquals(2, dbs.size());

        BackupDatabaseResponse db1 = dbs.getFirst();
        assertEquals("db1-0", db1.getName());
        assertFalse(db1.isExternallyManaged());
        assertEquals("{key=value}", db1.getResources().toString());
        assertEquals("role", db1.getUsers().getFirst().getRole());
        assertEquals("name", db1.getUsers().getFirst().getName());


        log.info(response.toString());
    }

    @Test
    void uploadBackupMetadata() {
        //when
        String namespace = "namespace";
        String backupName = "backupName";

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
                true
        );

        LogicalBackupStatusResponse logicalBackupStatusResponse = new LogicalBackupStatusResponse(
                Status.COMPLETED,
                null,
                null,
                null,
                List.of(new LogicalBackupStatusResponse.Database(
                        "db1",
                        Status.COMPLETED,
                        1,
                        "duration",
                        "path",
                        null
                ))
        );

        LogicalBackupResponse logicalBackupResponse = new LogicalBackupResponse(
                "logicalBackupName",
                "adapterID",
                "type",
                logicalBackupStatusResponse,
                List.of(backupDatabaseResponse)
        );

        BackupStatusResponse backupStatusResponse = new BackupStatusResponse(
                Status.COMPLETED,
                1,
                1,
                1L,
                null
        );

        Filter filter = new Filter();
        filter.setNamespace(List.of(namespace));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setFilter(List.of(filter));

        BackupResponse backupResponse = new BackupResponse();
        backupResponse.setBackupName(backupName);
        backupResponse.setStatus(backupStatusResponse);
        backupResponse.setLogicalBackups(List.of(logicalBackupResponse));
        backupResponse.setBlobPath("BlobPath");
        backupResponse.setStorageName("storageName");
        backupResponse.setFilterCriteria(filterCriteria);
        backupResponse.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);
        backupResponse.setIgnoreNotBackupableDatabases(true);

        dbBackupV2Service.uploadBackupMetadata(backupResponse);

        Backup backup = backupRepository.findById(backupName);
        assertEquals(backup.getName(), backupResponse.getBackupName());
        assertEquals(backup.getStorageName(), backupResponse.getStorageName());
        assertEquals(backup.getBlobPath(), backupResponse.getBlobPath());
        assertEquals(backup.getExternalDatabaseStrategy(), backupResponse.getExternalDatabaseStrategy());


        LogicalBackup logicalBackup = backup.getLogicalBackups().getFirst();

        assertEquals(logicalBackup.getLogicalBackupName(), logicalBackupResponse.getLogicalBackupName());
        assertNull(logicalBackup.getAdapterId());
        assertEquals(logicalBackup.getType(), logicalBackupResponse.getType());

        BackupDatabase backupDatabase = logicalBackup.getBackupDatabases().getFirst();
        assertEquals(backupDatabase.getName(), backupDatabaseResponse.getName());
        assertEquals(backupDatabase.getSettings(), backupDatabaseResponse.getSettings());
        assertEquals(backupDatabase.getResources(), backupDatabaseResponse.getResources());
    }

    private List<LogicalBackup> generateLogicalBackups(int count) {
        List<LogicalBackup> logicalBackups = new ArrayList<>();

        for (int i = 0; i < count; i++) {

            LogicalBackup logicalBackup = new LogicalBackup();
            logicalBackup.setLogicalBackupName(null);
            logicalBackup.setAdapterId(String.valueOf(i));
            logicalBackup.setType("test-type");

            LogicalBackupStatus status = new LogicalBackupStatus();
            status.setStatus(Status.NOT_STARTED);

            LogicalBackupStatus.Database db1 = LogicalBackupStatus.Database.builder()
                    .databaseName("db1-" + i)
                    .status(Status.COMPLETED)
                    .size(1)
                    .duration("duration")
                    .path("path")
                    .build();
            LogicalBackupStatus.Database db2 = LogicalBackupStatus.Database.builder()
                    .databaseName("db2-" + i)
                    .status(Status.COMPLETED)
                    .size(1)
                    .duration("duration")
                    .path("path")
                    .build();

            status.setDatabases(List.of(db1, db2));
            logicalBackup.setStatus(status);

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
            backupDatabase1.setExternallyManageable(false);

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
            backupDatabase2.setExternallyManageable(false);

            backupDatabase1.setLogicalBackup(logicalBackup);
            backupDatabase2.setLogicalBackup(logicalBackup);
            logicalBackup.setBackupDatabases(List.of(backupDatabase1, backupDatabase2));

            logicalBackups.add(logicalBackup);
        }

        return logicalBackups;
    }

    private static @NotNull List<Database> getDatabases(String dbName, String namespace) {
        List<Database> databases = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            int k = i % 2 == 0 ? 1 : 2;

            Database database = new Database();
            database.setAdapterId(String.valueOf(k));
            database.setName(dbName + i);

            DatabaseRegistry databaseRegistry = new DatabaseRegistry();
            databaseRegistry.setNamespace(namespace);
            databaseRegistry.setDatabase(database);

            database.setDatabaseRegistry(new ArrayList<>());
            database.getDatabaseRegistry().add(databaseRegistry);
            database.setSettings(new HashMap<>());
            databaseRegistry.setConnectionProperties(List.of());
            databaseRegistry.setResources(List.of());
            databases.add(database);
        }
        return databases;
    }

    private static List<LogicalBackupStatus.Database> constructInnerDbs() {
        List<LogicalBackupStatus.Database> list = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            LogicalBackupStatus.Database innerDb = LogicalBackupStatus.Database.builder()
                    .databaseName(UUID.randomUUID().toString())
                    .size(1)
                    .status(Status.COMPLETED)
                    .build();
            list.add(innerDb);
        }
        return list;
    }

    private static Backup createBackup(String name, List<LogicalBackup> logicalBackups) {
        Backup backup = new Backup();
        backup.setName(name);
        backup.setLogicalBackups(logicalBackups);
        backup.setStatus(BackupStatus.builder()
                .status(Status.COMPLETED)
                .total(1)
                .completed(1)
                .size(1L)
                .build());
        backup.setFilters("\"er\"");
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
        return dto;
    }
}
