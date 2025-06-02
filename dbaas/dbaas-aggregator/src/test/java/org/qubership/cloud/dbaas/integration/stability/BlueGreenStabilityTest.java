package org.qubership.cloud.dbaas.integration.stability;


import com.google.common.base.Strings;
import org.qubership.cloud.context.propagation.core.ContextManager;
import org.qubership.cloud.dbaas.controller.BlueGreenControllerV1;
import org.qubership.cloud.dbaas.dto.bluegreen.BgStateRequest;
import org.qubership.cloud.dbaas.dto.declarative.DatabaseDeclaration;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.CreatedDatabaseV3;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.ActionTrackDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import org.qubership.cloud.dbaas.service.*;
import org.qubership.cloud.dbaas.service.processengine.tasks.*;
import org.qubership.cloud.framework.contexts.xrequestid.XRequestIdContextObject;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.qubership.core.scheduler.po.model.pojo.TaskInstanceImpl;
import org.qubership.core.scheduler.po.task.TaskState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InMemoryLogHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;

import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.LogContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.LogRecord;

import static org.qubership.cloud.dbaas.Constants.ACTIVE_STATE;
import static org.qubership.cloud.dbaas.Constants.CANDIDATE_STATE;
import static org.qubership.cloud.dbaas.Constants.IDLE_STATE;
import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.qubership.cloud.framework.contexts.xrequestid.XRequestIdContextObject.X_REQUEST_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@Slf4j
@TestProfile(BlueGreenStabilityTest.ProdModeFalseProfile.class)
class BlueGreenStabilityTest {

    public static final String NS_1 = "ns1";
    public static final String NS_2 = "ns2";
    public static final String NS_C = "controller";
    private static final String POSTGRESQL = "postgresql";
    private final static RetryPolicy<Object> OPERATION_STATUS_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofSeconds(1)).withMaxDuration(Duration.ofMinutes(5));

    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    DatabaseDbaasRepository databaseDbaasRepository;
    @Inject
    ProcessService processService;
    @Inject
    BlueGreenService blueGreenService;
    @Inject
    BlueGreenControllerV1 blueGreenControllerV1;
    @Inject
    DatabasesRepository databasesRepository;
    @Inject
    DeclarativeDbaasCreationService declarativeDbaasCreationService;
    @Inject
    AdapterActionTrackerClient adapterActionTrackerClient;
    @InjectMock
    PhysicalDatabasesService physicalDatabasesService;
    @InjectMock
    BalancingRulesService balancingRulesService;
    @InjectMock
    AggregatedDatabaseAdministrationService aggregatedDatabaseAdministrationService;
    @InjectMock
    ActionTrackDbaasRepository actionTrackDbaasRepository;

    private static BgStateRequest.BGStateNamespace createBgStateNamespace(String state, String namespace, String version) {
        BgStateRequest.BGStateNamespace bgNamespace1 = new BgStateRequest.BGStateNamespace();
        bgNamespace1.setState(state);
        bgNamespace1.setName(namespace);
        bgNamespace1.setVersion(version);
        return bgNamespace1;
    }

    @NotNull
    private static SortedMap<String, Object> getClassifier() {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("test-key", "test-val");
        classifier.put("scope", "service");
        classifier.put("microserviceName", "testName");
        return classifier;
    }

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
        QuarkusTransaction.requiringNew().run(() -> {
            databaseDbaasRepository.deleteAll(databaseDbaasRepository.findAnyLogDbTypeByNamespace(NS_1));
            databaseDbaasRepository.deleteAll(databaseDbaasRepository.findAnyLogDbTypeByNamespace(NS_2));
            declarativeDbaasCreationService.deleteDeclarativeConfigurationByNamespace(NS_1);
            declarativeDbaasCreationService.deleteDeclarativeConfigurationByNamespace(NS_2);
        });
        if (blueGreenService.getBgDomainContains(NS_1).isPresent()) {
            blueGreenService.destroyDomain(Set.of(NS_1, NS_2));
        }
    }

    @Test
    void testRequestIdPropagationToTasks() {
        DbaasAdapterRESTClientV2 adapter = Mockito.mock(DbaasAdapterRESTClientV2.class);
        when(adapter.identifier()).thenReturn("adapter-id");
        doNothing().when(adapter).dropDatabase(any(DatabaseRegistry.class));

        CreatedDatabaseV3 createdDatabaseV3 = mock(CreatedDatabaseV3.class);
        doReturn(createdDatabaseV3).when(adapter).createDatabaseV3(any(), any());
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));
        when(physicalDatabasesService.getAdapterById("adapter-id")).thenReturn(adapter);

        PhysicalDatabase physicalDatabase = mock(PhysicalDatabase.class);

        ExternalAdapterRegistrationEntry externalAdapter = mock(ExternalAdapterRegistrationEntry.class);
        when(externalAdapter.getAdapterId()).thenReturn("adapter-id");
        when(physicalDatabase.getAdapter()).thenReturn(externalAdapter);
        doReturn(physicalDatabase).when(balancingRulesService).applyNamespaceBalancingRule(anyString(), anyString());

        when(aggregatedDatabaseAdministrationService.createDatabaseFromRequest(any(), any(), any(), any(), any()))
                .thenReturn(Response.ok().build());

        BgStateRequest bgStateRequest = new BgStateRequest();
        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        BgStateRequest.BGStateNamespace bgNamespace1 = createBgStateNamespace(ACTIVE_STATE, NS_1, "v1");
        BgStateRequest.BGStateNamespace bgNamespace2 = createBgStateNamespace(IDLE_STATE, NS_2, null);
        bgState.setControllerNamespace(NS_C);
        bgState.setOriginNamespace(bgNamespace1);
        bgState.setPeerNamespace(bgNamespace2);
        bgStateRequest.setBGState(bgState);
        QuarkusTransaction.requiringNew().run(() -> {
            createVersionedDatabase(NS_1, "v1");
            blueGreenService.initBgDomain(bgStateRequest);
        });
        InMemoryLogHandler inMemoryLogHandler = new InMemoryLogHandler(logRecord -> true);
        LogContext.getLogContext().getLogger(UpdateBgStateTask.class.getName()).addHandler(inMemoryLogHandler);

        ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject("test_requestId"));

        bgState.getOriginNamespace().setState(ACTIVE_STATE);
        bgState.getPeerNamespace().setState(CANDIDATE_STATE);

        ProcessInstanceImpl processInstance = QuarkusTransaction.requiringNew().call(() -> blueGreenService.warmup(bgState));
        Failsafe.with(OPERATION_STATUS_RETRY_POLICY).run(() -> {
            ProcessInstanceImpl process = processService.getProcess(processInstance.getId());
            assertEquals(TaskState.COMPLETED, process.getState());
        });

        List<LogRecord> records = inMemoryLogHandler.getRecords();
        assertFalse(records.isEmpty());
        records.forEach(record -> assertEquals("test_requestId", ((ExtLogRecord) record).getMdc("requestId")));
    }

    @Test
    void testBlueGreenCommitInNonProd() {
        // Test scenario: initBGDomain -> Warmup -> Commit
        DbaasAdapterRESTClientV2 adapter = Mockito.mock(DbaasAdapterRESTClientV2.class);
        when(adapter.identifier()).thenReturn("adapter-id");
        doNothing().when(adapter).dropDatabase(any(DatabaseRegistry.class));

        CreatedDatabaseV3 createdDatabaseV3 = mock(CreatedDatabaseV3.class);
        doReturn(createdDatabaseV3).when(adapter).createDatabaseV3(any(), any());
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));
        when(physicalDatabasesService.getAdapterById("adapter-id")).thenReturn(adapter);

        PhysicalDatabase physicalDatabase = mock(PhysicalDatabase.class);

        ExternalAdapterRegistrationEntry externalAdapter = mock(ExternalAdapterRegistrationEntry.class);
        when(externalAdapter.getAdapterId()).thenReturn("adapter-id");
        when(physicalDatabase.getAdapter()).thenReturn(externalAdapter);
        doReturn(physicalDatabase).when(balancingRulesService).applyNamespaceBalancingRule(anyString(), anyString());

        when(aggregatedDatabaseAdministrationService.createDatabaseFromRequest(any(), any(), any(), any(), any()))
                .thenReturn(Response.ok().build());

        BgStateRequest bgStateRequest = new BgStateRequest();
        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        BgStateRequest.BGStateNamespace bgNamespace1 = createBgStateNamespace(ACTIVE_STATE, NS_1, "v1");
        BgStateRequest.BGStateNamespace bgNamespace2 = createBgStateNamespace(IDLE_STATE, NS_2, null);
        bgState.setControllerNamespace(NS_C);
        bgState.setOriginNamespace(bgNamespace1);
        bgState.setPeerNamespace(bgNamespace2);
        bgStateRequest.setBGState(bgState);

        UUID databaseId = QuarkusTransaction.requiringNew().call(() -> {
            // create static database in NS-1, will be shared to NS-2 after warmup
            Database database = createStaticDatabase(NS_1, null, null);
            // create versioned database in NS-1
            createVersionedDatabase(NS_1, "v1");
            blueGreenService.initBgDomain(bgStateRequest);
            return database.getId();
        });

        // warmup execution
        bgState.getOriginNamespace().setState(ACTIVE_STATE);
        bgState.getPeerNamespace().setState(CANDIDATE_STATE);
        ProcessInstanceImpl processInstance = QuarkusTransaction.requiringNew().call(() -> blueGreenService.warmup(bgState));

        Failsafe.with(OPERATION_STATUS_RETRY_POLICY).run(() -> {
            ProcessInstanceImpl process = processService.getProcess(processInstance.getId());
            assertEquals(TaskState.COMPLETED, process.getState());
        });

        QuarkusTransaction.requiringNew().run(() -> {
            // create versioned database in NS-2
            createVersionedDatabase(NS_2, "v2");
            // create static database in NS-2, belongs only to NS-2
            createStaticDatabase(NS_2, "logicalDbName", "static");
            // create second static database in NS-2, and share it to NS-1
            Database sharedFromCandidateStaticDatabase = createStaticDatabase(NS_2, "extra", "field");
            Database database = databasesRepository.findByIdOptional(sharedFromCandidateStaticDatabase.getId()).get();
            DatabaseRegistry activeDatabaseRegistry = createDatabaseRegistry(NS_1);
            activeDatabaseRegistry.getClassifier().put("extra", "field");
            activeDatabaseRegistry.setDatabase(database);
            database.getDatabaseRegistry().add(activeDatabaseRegistry);
            databaseRegistryDbaasRepository.saveAnyTypeLogDb(activeDatabaseRegistry);
        });
        // commit execution
        bgState.getOriginNamespace().setState(ACTIVE_STATE);
        bgState.getPeerNamespace().setState(IDLE_STATE);
        QuarkusTransaction.requiringNew().run(() -> {
            blueGreenService.commit(bgStateRequest);
        });

        Failsafe.with(OPERATION_STATUS_RETRY_POLICY).run(() -> {
            // check that without PROD mode all required databases were deleted
            List<DatabaseRegistry> databaseRegistriesNs2 = databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(NS_2);
            assertEquals(2, databaseRegistriesNs2.size(), "Only shared static databases should be kept in idle namespace in dev mode");
        });

        // check that commit doesn't affect NS-1
        List<DatabaseRegistry> databaseRegistriesNs1 = databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(NS_1);
        Assertions.assertEquals(3, databaseRegistriesNs1.size());
        Optional<DatabaseRegistry> versionedDatabaseRegistry = databaseRegistriesNs1.stream()
                .filter(dbr -> dbr.getClassifier().containsKey("logicalDbName")).findFirst();
        assertTrue(versionedDatabaseRegistry.isPresent());

        long staticDatabaseRegistryCount = databaseRegistriesNs1.stream()
                .filter(dbr -> !dbr.getClassifier().containsKey("logicalDbName")).count();
        assertEquals(2, staticDatabaseRegistryCount);

        // check that without PROD mode all required databases were deleted
        List<DatabaseRegistry> databaseRegistriesNs2 = databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(NS_2);
        Assertions.assertEquals(2, databaseRegistriesNs2.size());

        List<Database> idleDatabase = databasesRepository.findByNamespace(NS_2);
        Assertions.assertEquals(1, idleDatabase.size(), "There should be 1 shared to active namespace database in idle namespace in dev mode");
        BgDomain domain = blueGreenService.getDomain(NS_1);
        log.debug("founded bgDomain {}", domain.getNamespaces());
        Optional<BgNamespace> activeBgNs = domain.getNamespaces().stream().filter(bgns -> ACTIVE_STATE.equals(bgns.getState())).findFirst();
        assertTrue(activeBgNs.isPresent());
        assertEquals(NS_1, activeBgNs.get().getNamespace());

        Optional<BgNamespace> idleBgNs = domain.getNamespaces().stream().filter(bgns -> IDLE_STATE.equals(bgns.getState())).findFirst();
        assertTrue(idleBgNs.isPresent());
        assertEquals(NS_2, idleBgNs.get().getNamespace());
    }

    @Test
    @Transactional
    void testBlueGreenTerminate() throws InterruptedException {
        // Test scenario: initBGDomain -> Warmup -> Terminate
        DbaasAdapterRESTClientV2 adapter = Mockito.mock(DbaasAdapterRESTClientV2.class);
        when(adapter.identifier()).thenReturn("adapter-id");
        doNothing().when(adapter).dropDatabase(any(DatabaseRegistry.class));
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));

        // create static database in NS-1
        createStaticDatabase(NS_1, null, null);
        // create versioned database in NS-1
        createVersionedDatabase(NS_1, "v1");

        BgStateRequest bgStateRequest = new BgStateRequest();
        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        BgStateRequest.BGStateNamespace bgNamespace1 = createBgStateNamespace(ACTIVE_STATE, NS_1, "v1");
        BgStateRequest.BGStateNamespace bgNamespace2 = createBgStateNamespace(IDLE_STATE, NS_2, null);
        bgState.setControllerNamespace(NS_C);
        bgState.setOriginNamespace(bgNamespace1);
        bgState.setPeerNamespace(bgNamespace2);
        bgStateRequest.setBGState(bgState);

        blueGreenService.initBgDomain(bgStateRequest);

        // warmup execution
        bgState.getOriginNamespace().setState(ACTIVE_STATE);
        bgState.getPeerNamespace().setState(CANDIDATE_STATE);
        ProcessInstanceImpl processInstance = blueGreenService.warmup(bgState);
        Thread.sleep(110);
        processService.terminateProcess(processInstance.getId());
        processInstance = processService.getProcess(processInstance.getId());
        assertEquals(TaskState.TERMINATED, processInstance.getState());
    }

    @Test
    @Transactional
    void testBlueGreenWarmupAllTaskTypes() throws InterruptedException {
        DbaasAdapterRESTClientV2 adapter = Mockito.mock(DbaasAdapterRESTClientV2.class);
        when(adapter.identifier()).thenReturn("adapter-id");
        doNothing().when(adapter).dropDatabase(any(DatabaseRegistry.class));
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));

        // DBs for new and clone
        createVersionedDatabase(NS_1, "v1");
        createVersionedDatabase(NS_1, "v1", "toClone", "clone");

        // init domain
        BgStateRequest bgStateRequest = new BgStateRequest();
        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        BgStateRequest.BGStateNamespace bgNamespace1 = createBgStateNamespace(ACTIVE_STATE, NS_1, "v1");
        BgStateRequest.BGStateNamespace bgNamespace2 = createBgStateNamespace(IDLE_STATE, NS_2, null);
        bgState.setControllerNamespace(NS_C);
        bgState.setOriginNamespace(bgNamespace1);
        bgState.setPeerNamespace(bgNamespace2);
        bgStateRequest.setBGState(bgState);
        blueGreenService.initBgDomain(bgStateRequest);

        // warmup execution
        bgState.getOriginNamespace().setState(ACTIVE_STATE);
        bgState.getPeerNamespace().setState(CANDIDATE_STATE);
        String processId = blueGreenService.warmup(bgState).getId();
        Thread.sleep(110);
        processService.terminateProcess(processId);

        ProcessInstanceImpl processInstance = processService.getProcess(processId);
        List<TaskInstanceImpl> tasks = processInstance.getTasks();
        assertEquals(5, tasks.size());
        assertTrue(tasks.stream().anyMatch(task -> NewDatabaseTask.class.getName().equals(task.getType())));
        assertTrue(tasks.stream().anyMatch(task -> BackupDatabaseTask.class.getName().equals(task.getType())));
        assertTrue(tasks.stream().anyMatch(task -> RestoreDatabaseTask.class.getName().equals(task.getType())));
        assertTrue(tasks.stream().anyMatch(task -> DeleteBackupTask.class.getName().equals(task.getType())));
        assertTrue(tasks.stream().anyMatch(task -> UpdateBgStateTask.class.getName().equals(task.getType())));
    }


    @SneakyThrows
    @Test
    @Disabled("PDCLFRM-4919")
    void testTerminateAsyncStability() {
        DbaasAdapterRESTClientV2 adapter = Mockito.mock(DbaasAdapterRESTClientV2.class);
        when(adapter.identifier()).thenReturn("adapter-id");
        doNothing().when(adapter).dropDatabase(any(DatabaseRegistry.class));
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));

        when(aggregatedDatabaseAdministrationService.createDatabaseFromRequest(any(), eq(NS_2), any(), any(), any()))
                .thenReturn(Response.ok().build());

        // create versioned database in NS-1
        createVersionedDatabase(NS_1, "v1");

        BgStateRequest bgStateRequest = new BgStateRequest();
        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        BgStateRequest.BGStateNamespace bgNamespace1 = createBgStateNamespace(ACTIVE_STATE, NS_1, "v1");
        BgStateRequest.BGStateNamespace bgNamespace2 = createBgStateNamespace(IDLE_STATE, NS_2, null);
        bgState.setControllerNamespace(NS_C);
        bgState.setOriginNamespace(bgNamespace1);
        bgState.setPeerNamespace(bgNamespace2);
        bgStateRequest.setBGState(bgState);

        blueGreenService.initBgDomain(bgStateRequest);

        TransferQueue<String> transferQueue = new LinkedTransferQueue<>();

        ExecutorService exService = Executors.newFixedThreadPool(2);

        int numberToProduceConsume = 500;

        WarmupAsync warmupAsync = new WarmupAsync(transferQueue, numberToProduceConsume);
        TerminateAsync terminateAsync = new TerminateAsync(transferQueue, numberToProduceConsume);

        exService.execute(warmupAsync);
        exService.execute(terminateAsync);

        exService.awaitTermination(numberToProduceConsume * 250, TimeUnit.MILLISECONDS);
        exService.shutdown();

        verify(aggregatedDatabaseAdministrationService, times(0)).createDatabaseFromRequest(any(), eq(NS_2), any(), any(), any());
        assertEquals(numberToProduceConsume, warmupAsync.numberOfProducedMessages.intValue());
        assertEquals(numberToProduceConsume, terminateAsync.numberOfConsumedMessages.intValue());
    }

    private Database createStaticDatabase(String namespace, String extraKey, String extraField) {
        DatabaseRegistry databaseRegistry = createDatabase(namespace);
        if (!Strings.isNullOrEmpty(extraField) && !Strings.isNullOrEmpty(extraKey)) {
            databaseRegistry.getClassifier().put(extraKey, extraField);
        }
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(databaseRegistry);
        return databaseRegistry.getDatabase();
    }

    private void createVersionedDatabase(String namespace, String version) {
        createVersionedDatabase(namespace, version, "config", "new");
    }

    private void createVersionedDatabase(String namespace, String version, String customVal, String approach) {
        DatabaseDeclaration config = createDeclarativeDatabaseCreationConfiguration("testName");
        config.getClassifierConfig().getClassifier().put("logicalDbName", customVal);
        DatabaseDeclaration.VersioningConfig versioningConfig = new DatabaseDeclaration.VersioningConfig();
        versioningConfig.setApproach(approach);
        config.setVersioningConfig(versioningConfig);
        declarativeDbaasCreationService.saveNewDatabaseConfig(NS_1, "testName", config);

        databaseRegistryDbaasRepository.saveAnyTypeLogDb(createVersionedDatabaseRegistry(namespace, version, customVal));
    }

    private DatabaseRegistry createVersionedDatabaseRegistry(String namespace, String version, String customVal) {
        DatabaseRegistry versionedDatabaseRegistry2 = createDatabase(namespace);
        versionedDatabaseRegistry2.getClassifier().put("logicalDbName", customVal);
        Database versionedDatabase2 = versionedDatabaseRegistry2.getDatabase();
        versionedDatabase2.setBgVersion(version);
        return versionedDatabaseRegistry2;
    }

    private DatabaseDeclaration createDeclarativeDatabaseCreationConfiguration(String microserviceName) {
        DatabaseDeclaration result = new DatabaseDeclaration();
        result.setType("postgresql");

        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("scope", "service");
        classifier.put("test-key", "test-val");
        classifier.put("microserviceName", microserviceName);

        result.setClassifierConfig(new DatabaseDeclaration.ClassifierConfig(classifier));
        return result;
    }

    private DatabaseRegistry createDatabase(String namespace) {
        SortedMap<String, Object> classifier = getClassifier();
        classifier.put("namespace", namespace);

        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setClassifier(classifier);
        database.setType(POSTGRESQL);
        database.setAdapterId("adapter-id");
        database.setNamespace(namespace);
        database.setConnectionProperties(List.of(new HashMap<>() {{
            put("username", "user");
            put(ROLE, Role.ADMIN.toString());
        }}));

        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        DatabaseRegistry databaseRegistry = createDatabaseRegistry();
        databaseRegistry.setDatabase(database);
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType(POSTGRESQL);
        databaseRegistry.setAdapterId("adapter-id");
        databaseRegistry.setNamespace(namespace);
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);

        DbResource resource = new DbResource("someKind", "someName");
        List<DbResource> resources = new ArrayList<>();
        resources.add(resource);
        database.setResources(resources);
        database.setName("exact-classifier-match-test-db");
        database.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        return databaseRegistry;
    }

    private DatabaseRegistry createDatabaseRegistry() {
        return createDatabaseRegistry("test-namespace");
    }

    private DatabaseRegistry createDatabaseRegistry(String namespace) {
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setClassifier(getClassifier());
        databaseRegistry.setType(POSTGRESQL);
        databaseRegistry.setNamespace(namespace);
        databaseRegistry.getClassifier().put("namespace", namespace);
        return databaseRegistry;
    }

    @NoArgsConstructor
    protected static final class ProdModeFalseProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> properties = new HashMap<>();
            properties.put("dbaas.production.mode", "false");
            properties.put("quarkus.log.category.\"org.qubership.cloud.dbaas.service.processengine.tasks\".level", "debug");
            return properties;
        }
    }

    @Data
    @RequiredArgsConstructor
    private class WarmupAsync implements Runnable {
        public AtomicInteger numberOfProducedMessages = new AtomicInteger();
        @NotNull
        private TransferQueue<String> transferQueue;
        @NotNull
        private int numberOfMessagesToConsume;

        @SneakyThrows
        @Override
        public void run() {
            // warmup execution
            BgStateRequest.BGState bgState = new BgStateRequest.BGState();
            BgStateRequest.BGStateNamespace bgNamespace1 = createBgStateNamespace(ACTIVE_STATE, NS_1, "v1");
            BgStateRequest.BGStateNamespace bgNamespace2 = createBgStateNamespace(CANDIDATE_STATE, NS_2, "v2");
            bgState.setOriginNamespace(bgNamespace1);
            bgState.setPeerNamespace(bgNamespace2);
            for (int i = 0; i < numberOfMessagesToConsume; i++) {
                Assertions.assertEquals(0, databaseDbaasRepository.findAnyLogDbTypeByNamespace(NS_2).size());
                ProcessInstanceImpl processInstance = blueGreenService.warmup(bgState);
                transferQueue.tryTransfer(processInstance.getId(), 4000, TimeUnit.MILLISECONDS);
                numberOfProducedMessages.incrementAndGet();
                Thread.sleep(10);
            }
        }
    }

    @Data
    @RequiredArgsConstructor
    private class TerminateAsync implements Runnable {
        public AtomicInteger numberOfConsumedMessages = new AtomicInteger();
        @NotNull
        private TransferQueue<String> transferQueue;
        @NotNull
        private Integer numberOfMessagesToConsume;

        @SneakyThrows
        @Override
        public void run() {
            // warmup execution
            for (int i = 0; i < numberOfMessagesToConsume; i++) {
                String processId = null;
                try {
                    processId = transferQueue.take();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                processService.terminateProcess(processId);
                ProcessInstanceImpl processInstance = processService.getProcess(processId);
                assertEquals(TaskState.TERMINATED, processInstance.getState());
                numberOfConsumedMessages.incrementAndGet();
                Thread.sleep(50);
            }
        }
    }
}
