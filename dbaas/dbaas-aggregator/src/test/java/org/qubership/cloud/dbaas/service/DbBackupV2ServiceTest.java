package org.qubership.cloud.dbaas.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.backupV2.*;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BackupDatabaseRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BackupRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.LogicalBackupRepository;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Slf4j
@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class DbBackupV2ServiceTest {

    @InjectMock
    private DatabaseDbaasRepository databaseDbaasRepository;

    @Inject
    private BackupRepository backupRepository;

    @InjectSpy
    private LogicalBackupRepository logicalBackupRepository;

    @InjectMock
    private PhysicalDatabasesService physicalDatabasesService;

    @Inject
    private BackupDatabaseRepository backupDatabaseRepository;

    @Inject
    private DbBackupV2Service dbBackupV2Service;


    @AfterEach
    @Transactional
    void setUp(){
        backupDatabaseRepository.deleteAll();
        logicalBackupRepository.deleteAll();
        backupRepository.deleteAll();
    }

    @Test
    void backup_FailTest() {
        String namespace = "test-namespace";
        String adapterOneName = "1";
        String adapterTwoName = "2";
        String dbName = "db-name";
        String logicalBackupNameOne = "logicalBackupName1";
        String logicalBackupNameTwo = "logicalBackupName2";
        String backupName = "backup123";

        List<Database> registries = getDatabases(dbName, namespace);

        LogicalBackupStatus logicalBackupStatusSuccess = new LogicalBackupStatus();
        logicalBackupStatusSuccess.setDatabases(constructInnerDbs());
        logicalBackupStatusSuccess.setStatus(Status.SUCCESS);

        LogicalBackupStatus logicalBackupStatusFail = new LogicalBackupStatus();
        logicalBackupStatusFail.setDatabases(constructInnerDbs());
        logicalBackupStatusFail.setStatus(Status.FAIL);

        LogicalBackupStatus logicalBackupStatusProceeding = new LogicalBackupStatus();
        logicalBackupStatusProceeding.setDatabases(constructInnerDbs());
        logicalBackupStatusProceeding.getDatabases().getFirst().setDuration("1");
        logicalBackupStatusProceeding.setStatus(Status.PROCEEDING);

        LogicalBackupStatus logicalBackupStatusProceeding2 = new LogicalBackupStatus();
        logicalBackupStatusProceeding2.setDatabases(constructInnerDbs());
        logicalBackupStatusProceeding2.getDatabases().getFirst().setDuration("2");
        logicalBackupStatusProceeding2.setStatus(Status.PROCEEDING);

        DbaasAdapter adapterOne = mock(DbaasAdapter.class);
        DbaasAdapter adapterTwo = mock(DbaasAdapter.class);

        when(databaseDbaasRepository.findAnyLogDbTypeByNamespace(namespace))
                .thenReturn(registries);

        when(physicalDatabasesService.getAdapterById(adapterOneName))
                .thenReturn(adapterOne);
        when(physicalDatabasesService.getAdapterById(adapterTwoName))
                .thenReturn(adapterTwo);

        when(adapterOne.type()).thenReturn("PGSQL");
        when(adapterTwo.type()).thenReturn("MONGODB");

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

        dbBackupV2Service.backup(namespace, backupName);

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

        assertEquals(Status.FAIL, backup.getStatus().getStatus());
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
    void backup_mustThrowException_whenTrackBackupReturnException(){
        String namespace = "test-namespace";
        String adapterId = "1";
        String logicalBackupName = "logicalBackupName1";
        String backupName = "backup123";
        String dbName = "db-name";

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

        dbBackupV2Service.backup(namespace, backupName);

        List<LogicalBackup> logicalBackups = logicalBackupRepository.getByBackupName(backupName);

        LogicalBackup logicalBackupFirst = logicalBackups.getFirst();

        assertEquals(Status.FAIL, logicalBackupFirst.getStatus().getStatus());
        assertTrue(logicalBackupFirst.getStatus().getErrorMessage().contains("Timeout"));

        Mockito.verify(adapterOne, times(1)).backupV2(any());
        Mockito.verify(adapterOne, times(3)).trackBackupV2(any());
    }

    @Test
    void startBackup_whenValidInput_thenBackupIsInitialized() {
        //when
        int logicalBackupCount = 5;
        List<LogicalBackup> logicalBackups = generateLogicalBackups(logicalBackupCount);

        Backup backup = new Backup();
        backup.setLogicalBackups(logicalBackups);

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

        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);

        when(physicalDatabasesService.getAdapterById(any()))
                .thenReturn(adapter);
        when(adapter.type())
                .thenReturn("psql");

        //then
        dbBackupV2Service.initializeFullBackupStructure(databaseList, backupName);

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
    void updateAggregatedStatus_shouldReturnProceeding_whenStatusesContainNotStartedProceedingFailSuccess() {
        //when
        List<LogicalBackup> logicalBackupList = generateLogicalBackupsWithStatuses(4, Set.of(Status.NOT_STARTED, Status.PROCEEDING, Status.FAIL, Status.SUCCESS));
        String backupName = "test-backup";

        Backup backup = new Backup();
        backup.setName(backupName);
        backup.setLogicalBackups(logicalBackupList);
        //then
        dbBackupV2Service.updateAggregatedStatus(backup);

        //check
        assertEquals(Status.PROCEEDING, backup.getStatus().getStatus());

    }

    @Test
    void aggregateStatus_shouldReturnProceeding_whenInputNotStartedProceedingFailSuccess() {
        //when
        Set<Status> statuses = Set.of(Status.NOT_STARTED, Status.PROCEEDING, Status.FAIL, Status.SUCCESS);

        //then
        Status status = dbBackupV2Service.aggregateStatus(statuses);

        assertNotNull(status);
        assertEquals(Status.PROCEEDING, status);
    }

    @Test
    void aggregateStatus_shouldReturnProceeding_whenInputNotStartedFailSuccess() {
        //when
        Set<Status> statuses = Set.of(Status.NOT_STARTED, Status.FAIL, Status.SUCCESS);

        //then
        Status status = dbBackupV2Service.aggregateStatus(statuses);

        assertNotNull(status);
        assertEquals(Status.PROCEEDING, status);
    }

    @Test
    void aggregateStatus_shouldReturnProceeding_whenInputProceedingFailSuccess() {
        //when
        Set<Status> statuses = Set.of(Status.PROCEEDING, Status.FAIL, Status.SUCCESS);

        //then
        Status status = dbBackupV2Service.aggregateStatus(statuses);

        assertNotNull(status);
        assertEquals(Status.PROCEEDING, status);
    }

    @Test
    void aggregateStatus_shouldReturnProceeding_whenInputFailSuccess() {
        //when
        Set<Status> statuses = Set.of(Status.FAIL, Status.SUCCESS);

        //then
        Status status = dbBackupV2Service.aggregateStatus(statuses);

        assertNotNull(status);
        assertEquals(Status.FAIL, status);
    }

    @Test
    void aggregateStatus_shouldReturnProceeding_whenInputSuccess() {
        //when
        Set<Status> statuses = Set.of(Status.SUCCESS);

        //then
        Status status = dbBackupV2Service.aggregateStatus(statuses);

        assertNotNull(status);
        assertEquals(Status.SUCCESS, status);
    }


    private List<LogicalBackup> generateLogicalBackupsWithStatuses(int count, Set<Status> statuses) {
        List<Status> statusList = new ArrayList<>(statuses);
        List<LogicalBackup> logicalBackups = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Status currentStatus = statusList.get(i % statusList.size());

            LogicalBackup logicalBackup = LogicalBackup.builder()
                    .id(UUID.randomUUID())
                    .status(LogicalBackupStatus.builder()
                            .status(currentStatus)
                            .databases(List.of(LogicalBackupStatus.Database.builder()
                                    .databaseName("db-" + i)
                                    .size(1)
                                    .build()))
                            .build())
                    .build();

            logicalBackups.add(logicalBackup);
        }

        return logicalBackups;
    }

    private List<LogicalBackup> generateLogicalBackups(int count) {
        List<LogicalBackup> logicalBackups = new ArrayList<>();

        for (int i = 0; i < count; i++) {

            LogicalBackup logicalBackup = new LogicalBackup();
            logicalBackup.setId(UUID.randomUUID());
            logicalBackup.setLogicalBackupName(null);
            logicalBackup.setAdapterId(String.valueOf(i));
            logicalBackup.setType("test-type");

            LogicalBackupStatus status = new LogicalBackupStatus();
            status.setStatus(Status.NOT_STARTED);

            LogicalBackupStatus.Database db1 = LogicalBackupStatus.Database.builder()
                    .databaseName("db1-" + i)
                    .build();
            LogicalBackupStatus.Database db2 = LogicalBackupStatus.Database.builder()
                    .databaseName("db2-" + i)
                    .build();

            status.setDatabases(List.of(db1, db2));
            logicalBackup.setStatus(status);

            BackupDatabase backupDatabase1 = new BackupDatabase();
            backupDatabase1.setId(UUID.randomUUID());
            backupDatabase1.setLogicalBackup(logicalBackup);
            backupDatabase1.setName("db1-" + i);
            backupDatabase1.setSettings(Map.of());
            backupDatabase1.setClassifiers(List.of());
            backupDatabase1.setUsers(List.of());
            backupDatabase1.setResources("{}");
            backupDatabase1.setExternallyManageable(false);


            backupDatabase1.setClassifiers(List.of());

            BackupDatabase backupDatabase2 = new BackupDatabase();
            backupDatabase2.setId(UUID.randomUUID());
            backupDatabase2.setLogicalBackup(logicalBackup);
            backupDatabase2.setName("db2-" + i);
            backupDatabase2.setSettings(Map.of());
            backupDatabase2.setUsers(List.of());
            backupDatabase2.setResources("{}");
            backupDatabase2.setClassifiers(List.of());
            backupDatabase2.setExternallyManageable(false);

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
                    .status(Status.SUCCESS)
                    .build();
            list.add(innerDb);
        }
        return list;
    }
}
