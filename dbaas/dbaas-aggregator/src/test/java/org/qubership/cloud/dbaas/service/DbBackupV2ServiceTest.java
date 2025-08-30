package org.qubership.cloud.dbaas.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.qubership.cloud.dbaas.dto.backupV2.*;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.ExternalAdapterRegistrationEntry;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.entity.pg.backupV2.*;
import org.qubership.cloud.dbaas.entity.pg.backupV2.LogicalRestore;
import org.qubership.cloud.dbaas.entity.pg.backupV2.RestoreStatus;
import org.qubership.cloud.dbaas.enums.Status;
import org.qubership.cloud.dbaas.exceptions.BackupExecutionException;
import org.qubership.cloud.dbaas.exceptions.DBBackupValidationException;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.*;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;
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

    @InjectMock
    private BalancingRulesService balancingRulesService;

    @Inject
    private RestoreRepository restoreRepository;

    @Inject
    private RestoreDatabaseRepository restoreDatabaseRepository;

    @Inject
    private LogicalRestoreRepository logicalRestoreDatabaseRepository;

    @Inject
    private LockProvider lockProvider;

    @Inject
    @Named("po-datasource")
    private DataSource dataSource;

    @BeforeAll
    static void globalSetUp() {
        DbBackupV2Service.TRACK_DELAY_MS = 0;
    }

    @AfterEach
    void setUp() {
        restoreDatabaseRepository.deleteAll();
        logicalRestoreDatabaseRepository.deleteAll();
        restoreRepository.deleteAll();
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

        assertEquals(2, logicalBackups.size());
        assertTrue(
                logicalBackupNameOne.equals(logicalBackupFirst.getLogicalBackupName()) ||
                        logicalBackupNameTwo.equals(logicalBackupFirst.getLogicalBackupName()),
                "Logical backup name should match one of the expected names"
        );

        Mockito.verify(adapterOne, times(1)).backupV2(any());
        Mockito.verify(adapterTwo, times(1)).backupV2(any());

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
    void findAndStartAggregateBackup_shouldNotRunInParallelAcrossNodes() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger executions = new AtomicInteger();

        LockProvider pod1LockProvider = new JdbcTemplateLockProvider(dataSource);
        LockProvider pod2LockProvider = new JdbcTemplateLockProvider(dataSource);

        LockConfiguration config = new LockConfiguration(
                Instant.now(),
                "findAndStartAggregateBackup",
                Duration.ofMinutes(5),
                Duration.ofMinutes(1)
        );

        Runnable pod1Job = () -> {
            assertTrue(pod1LockProvider.lock(config).isPresent(), "pod1LockProvider don`t present");
            dbBackupV2Service.findAndStartAggregateBackup();
            executions.incrementAndGet();
            latch.countDown();
        };

        Runnable pod2Job = () -> {
            assertTrue(pod2LockProvider.lock(config).isPresent(), "pod2LockProvider don`t present");
            dbBackupV2Service.findAndStartAggregateBackup();
            executions.incrementAndGet();
            latch.countDown();
        };

        executor.submit(pod1Job);
        executor.submit(pod2Job);

        latch.await(5, SECONDS);

        assertEquals(1, executions.get());
    }

    @Test
    void trackAndAggregate() {
        String backupName = "backupName";
        String adapterIdFirst = "some-first-adapter-id";
        String adapterIdSecond = "some-second-adpater-id";

        LogicalBackupStatus.Database db1 = LogicalBackupStatus.Database.builder()
                .databaseName("db1")
                .status(Status.COMPLETED)
                .size(1)
                .duration("1")
                .path("somepath")
                .build();

        LogicalBackupStatus.Database db2 = LogicalBackupStatus.Database.builder()
                .databaseName("db2")
                .status(Status.PENDING)
                .size(1)
                .duration("1")
                .errorMessage("errorMessage")
                .path("somepath")
                .build();

        LogicalBackupStatus.Database db3 = LogicalBackupStatus.Database.builder()
                .databaseName("db3")
                .status(Status.PENDING)
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
        logicalBackup.setLogicalBackupName("mock-first-name");
        logicalBackup.setAdapterId(adapterIdFirst);
        logicalBackup.setType("postgresql");
        logicalBackup.setStatus(new LogicalBackupStatus());
        logicalBackup.setBackupDatabases(List.of(BackupDatabase.builder()
                .users(List.of())
                .classifiers(List.of())
                .build()));

        LogicalBackup logicalBackup1 = new LogicalBackup();
        logicalBackup1.setLogicalBackupName("mock-second-name");
        logicalBackup1.setAdapterId(adapterIdSecond);
        logicalBackup1.setType("postgresql");
        logicalBackup1.setStatus(new LogicalBackupStatus());
        logicalBackup1.setBackupDatabases(List.of(BackupDatabase.builder()
                .classifiers(List.of())
                .users(List.of())
                .build()));

        List<LogicalBackup> logicalBackups = List.of(logicalBackup, logicalBackup1);

        Backup backup = createBackup(backupName, logicalBackups);

        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapterIdFirst))
                .thenReturn(adapter);
        when(adapter.trackBackupV2(any()))
                .thenReturn(logicalBackupStatus);

        DbaasAdapter adapter1 = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(adapterIdSecond))
                .thenReturn(adapter1);
        when(adapter1.trackBackupV2(any()))
                .thenReturn(logicalBackupStatus);


        dbBackupV2Service.trackAndAggregate(backup);

        Backup expectedBackup = backupRepository.findById(backupName);

        assertNotNull(expectedBackup);
        assertEquals(1, backup.getAttemptCount());
        assertNotNull(expectedBackup.getStatus());

        BackupStatus aggregatedStatus = backup.getStatus();

        assertEquals(Status.PENDING, aggregatedStatus.getStatus());
        assertEquals(2, aggregatedStatus.getTotal());
        assertEquals(6, aggregatedStatus.getSize());
        assertEquals(2, aggregatedStatus.getCompleted());

        String aggregatedErrorMsg = logicalBackups.stream()
                .filter(lb -> lb.getStatus().getErrorMessage() != null && !lb.getStatus().getErrorMessage().isBlank())
                .map(lb -> String.format("LogicalBackup %s failed: %s",
                        lb.getLogicalBackupName(),
                        lb.getStatus().getErrorMessage()))
                .collect(Collectors.joining("; "));

        assertEquals(aggregatedErrorMsg, aggregatedStatus.getErrorMessage());
    }

    @Test
    void trackAndAggregate_backupAttemptExceeded_aggregatorMustBeFailed() {
        String backupName = "backupName";

        Backup backup = createBackup(backupName, List.of());
        backup.setStatus(BackupStatus.builder()
                        .status(Status.IN_PROGRESS)
                .build());
        backup.setAttemptCount(21);


        dbBackupV2Service.trackAndAggregate(backup);

        Backup expectedBackup = backupRepository.findById(backupName);

        assertNotNull(expectedBackup);
        assertEquals(21, backup.getAttemptCount());
        assertNotNull(expectedBackup.getStatus());

        BackupStatus aggregatedStatus = backup.getStatus();

        assertEquals(Status.FAILED, aggregatedStatus.getStatus());

        String errorMsg = "The number of attempts exceeded 20";
        assertEquals(errorMsg, aggregatedStatus.getErrorMessage());
    }



    @Test
    void updateAggregatedStatus_shouldReturnProceeding_containAllStatuses() {
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

        LogicalBackupStatus logicalBackupStatus1 = LogicalBackupStatus.builder()
                .status(Status.PENDING)
                .databases(List.of(db1, db2, db3))
                .build();

        LogicalBackup logicalBackup = new LogicalBackup();
        logicalBackup.setLogicalBackupName("mock-first-name");
        logicalBackup.setAdapterId(adapterId);
        logicalBackup.setStatus(logicalBackupStatus);
        logicalBackup.setBackupDatabases(List.of(BackupDatabase.builder().id(UUID.randomUUID()).build()));

        LogicalBackup logicalBackup1 = new LogicalBackup();
        logicalBackup1.setLogicalBackupName("mock-second-name");
        logicalBackup1.setStatus(logicalBackupStatus1);
        logicalBackup1.setBackupDatabases(List.of(BackupDatabase.builder().id(UUID.randomUUID()).build()));

        List<LogicalBackup> logicalBackups = List.of(logicalBackup, logicalBackup1);

        Backup backup = createBackup("name", logicalBackups);

        dbBackupV2Service.updateAggregatedStatus(backup);

        BackupStatus backupStatus = backup.getStatus();
        String aggregatedErrorMsg = logicalBackups.stream()
                .filter(lb -> lb.getStatus().getErrorMessage() != null && !lb.getStatus().getErrorMessage().isBlank())
                .map(lb -> String.format("LogicalBackup %s failed: %s",
                        lb.getLogicalBackupName(),
                        lb.getStatus().getErrorMessage()))
                .collect(Collectors.joining("; "));

        //check
        assertEquals(Status.PENDING, backupStatus.getStatus());
        assertEquals(2, backupStatus.getTotal());
        assertEquals(2, backupStatus.getCompleted());
        assertEquals(6, backupStatus.getSize());
        assertEquals(aggregatedErrorMsg, backupStatus.getErrorMessage());
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
    void getBackupMetadata() {
        String backupName = "backupName";
        List<LogicalBackup> logicalBackups = generateLogicalBackups(1);
        Backup backup = createBackup(backupName, logicalBackups);
        backup.setStatus(BackupStatus.builder()
                        .status(Status.COMPLETED)
                        .total(1)
                        .completed(1)
                        .size(1L)
                .build());
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
        assertNull(actualLogicalBackup.getAdapterId());
        assertEquals(actualLogicalBackup.getType(), expectedLogicalBackup.getType());

        BackupDatabaseResponse expectedBackupDatabase = expectedLogicalBackup.getBackupDatabases().getFirst();
        BackupDatabase actualBackupDatabase = actualLogicalBackup.getBackupDatabases().getFirst();
        assertEquals(actualBackupDatabase.getName(), expectedBackupDatabase.getName());
        assertEquals(actualBackupDatabase.getSettings(), expectedBackupDatabase.getSettings());
        assertEquals(actualBackupDatabase.getResources(), expectedBackupDatabase.getResources());
    }

    @Test
    void uploadBackupMetadata_BackupAlreadyExists() {
        String backupName = "backupName";
        String namespace = "namespace";

        Backup backup = createBackup(backupName, List.of());
        backupRepository.save(backup);

        BackupResponse backupResponse = generateBackupResponse(backupName, namespace);

        assertThrows(DBBackupValidationException.class,
                () -> dbBackupV2Service.uploadBackupMetadata(backupResponse));
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

        BackupDatabase backupDatabase1 = BackupDatabase.builder()
                .name("db1")
                .classifiers(List.of(classifier1))
                .users(List.of())
                .settings(Map.of())
                .resources(Map.of())
                .build();

        SortedMap<String, Object> classifier2 = new TreeMap<>();
        classifier2.put("namespace", oldNamespace2);

        BackupDatabase backupDatabase2 = BackupDatabase.builder()
                .name("db2")
                .classifiers(List.of(classifier2))
                .users(List.of())
                .settings(Map.of())
                .resources(Map.of())
                .build();

        LogicalBackup logicalBackup1 = LogicalBackup.builder()
                .logicalBackupName("lb1")
                .type("postgres")
                .backupDatabases(List.of(backupDatabase1))
                .status(new LogicalBackupStatus())
                .build();

        LogicalBackup logicalBackup2 = LogicalBackup.builder()
                .logicalBackupName("lb2")
                .type("postgres")
                .backupDatabases(List.of(backupDatabase2))
                .status(new LogicalBackupStatus())
                .build();

        Backup backup = new Backup();
        backup.setName("backup-name");
        backup.setLogicalBackups(List.of(logicalBackup1, logicalBackup2));
        backup.setStorageName("storageName");
        backup.setBlobPath("blobpath");
        backup.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);

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

        when(balancingRulesService.applyNamespaceBalancingRule(newNamespace1, "postgres"))
                .thenReturn(physicalDatabase1);
        when(balancingRulesService.applyNamespaceBalancingRule(newNamespace2, "postgres"))
                .thenReturn(physicalDatabase2);


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

        LogicalRestore logicalRestore2 = restore.getLogicalRestores().stream()
                .filter(lr -> lr.getAdapterId().equals(adapterId2))
                .findFirst()
                .orElseThrow();
        assertEquals(adapterId2, logicalRestore2.getAdapterId());
        assertEquals(1, logicalRestore2.getRestoreDatabases().size());
        assertEquals(backupDatabase2, logicalRestore2.getRestoreDatabases().getFirst().getBackupDatabase());

        verify(balancingRulesService, times(1)).applyNamespaceBalancingRule(newNamespace1, "postgres");
        verify(balancingRulesService, times(1)).applyNamespaceBalancingRule(newNamespace2, "postgres");
    }

    @Test
    void initializeFullRestoreStructure_withoutMapping() {
        String oldNamespace1 = "old-ns1";
        String oldNamespace2 = "old-ns2";
        String adapterId1 = "adapterId1";
        String adapterId2 = "adapterId2";

        SortedMap<String, Object> classifier1 = new TreeMap<>();
        classifier1.put("namespace", oldNamespace1);

        BackupDatabase backupDatabase1 = BackupDatabase.builder()
                .name("db1")
                .classifiers(List.of(classifier1))
                .users(List.of())
                .settings(Map.of())
                .resources(Map.of())
                .build();

        SortedMap<String, Object> classifier2 = new TreeMap<>();
        classifier2.put("namespace", oldNamespace2);

        BackupDatabase backupDatabase2 = BackupDatabase.builder()
                .name("db2")
                .classifiers(List.of(classifier2))
                .users(List.of())
                .settings(Map.of())
                .resources(Map.of())
                .build();

        LogicalBackup logicalBackup1 = LogicalBackup.builder()
                .logicalBackupName("lb1")
                .type("postgres")
                .backupDatabases(List.of(backupDatabase1))
                .status(new LogicalBackupStatus())
                .build();

        LogicalBackup logicalBackup2 = LogicalBackup.builder()
                .logicalBackupName("lb2")
                .type("postgres")
                .backupDatabases(List.of(backupDatabase2))
                .status(new LogicalBackupStatus())
                .build();

        Backup backup = new Backup();
        backup.setName("backup-name");
        backup.setLogicalBackups(List.of(logicalBackup1, logicalBackup2));
        backup.setBlobPath("blobPath");
        backup.setStorageName("storageName");
        backup.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);

        backupRepository.save(backup);
        RestoreRequest restoreRequest = new RestoreRequest();
        restoreRequest.setMapping(null);
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

        when(balancingRulesService.applyNamespaceBalancingRule(oldNamespace1, "postgres"))
                .thenReturn(physicalDatabase1);
        when(balancingRulesService.applyNamespaceBalancingRule(oldNamespace2, "postgres"))
                .thenReturn(physicalDatabase2);

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

        LogicalRestore logicalRestore2 = restore.getLogicalRestores().stream()
                .filter(lr -> lr.getAdapterId().equals(adapterId2))
                .findFirst()
                .orElseThrow();
        assertEquals(adapterId2, logicalRestore2.getAdapterId());
        assertEquals(1, logicalRestore2.getRestoreDatabases().size());
        assertEquals(backupDatabase2, logicalRestore2.getRestoreDatabases().getFirst().getBackupDatabase());

        verify(balancingRulesService, times(1)).applyNamespaceBalancingRule(oldNamespace1, "postgres");
        verify(balancingRulesService, times(1)).applyNamespaceBalancingRule(oldNamespace2, "postgres");
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

        BackupDatabase backupDatabase1 = BackupDatabase.builder()
                .name("db1")
                .resources(Map.of())
                .settings(Map.of())
                .users(List.of(BackupDatabase.User.builder()
                        .name("name")
                        .role("role")
                        .build()))
                .classifiers(List.of(map))
                .externallyManageable(true)
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
                .externallyManageable(true)
                .build();

        LogicalBackup logicalBackup = LogicalBackup.builder()
                .logicalBackupName("logicalBackupName")
                .type("postgresql")
                .backupDatabases(List.of(backupDatabase1, backupDatabase2))
                .status(LogicalBackupStatus.builder().build())
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
                .status(LogicalRestoreStatus.builder().build())
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

        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAdapterById(adapterId))
                .thenReturn(adapter);
        when(adapter.restoreV2(any(), anyBoolean(), any(), any(), anyList()))
                .thenReturn(logicalRestoreName);

        dbBackupV2Service.startRestore(restore, true);

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
        verify(adapter, times(1)).restoreV2(any(), anyBoolean(), any(), any(), anyList());

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

        return backupResponse;
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
        backup.setStatus(BackupStatus.builder().build());
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
