package org.qubership.cloud.dbaas.integration.stability;

import com.google.common.base.Strings;
import org.qubership.cloud.dbaas.dto.bluegreen.BgStateRequest;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import org.qubership.cloud.dbaas.service.BlueGreenService;
import org.qubership.cloud.dbaas.service.DbaasAdapterRESTClientV2;
import org.qubership.cloud.dbaas.service.PhysicalDatabasesService;
import org.qubership.cloud.dbaas.service.ProcessService;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.qubership.core.scheduler.po.task.TaskState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.*;

import static org.qubership.cloud.dbaas.Constants.*;
import static org.qubership.cloud.dbaas.entity.shared.AbstractDbState.DatabaseStateStatus.CREATED;
import static org.qubership.cloud.dbaas.entity.shared.AbstractDbState.DatabaseStateStatus.ORPHAN;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@Slf4j
class BlueGreenProdModeStabilityTest {

    private static final String POSTGRESQL = "postgresql";
    public static final String NS_1 = "ns1";
    public static final String NS_2 = "ns2";
    public static final String NS_C = "controller";

    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    ProcessService processService;
    @Inject
    DatabaseDbaasRepository databaseDbaasRepository;
    @Inject
    BlueGreenService blueGreenService;
    @Inject
    DatabasesRepository databasesRepository;

    @InjectMock
    PhysicalDatabasesService physicalDatabasesService;

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
        });
        if (blueGreenService.getBgDomainContains(NS_1).isPresent()) {
            blueGreenService.destroyDomain(Set.of(NS_1, NS_2));
        }
    }

    private final static RetryPolicy<Object> OPERATION_STATUS_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofSeconds(1)).withMaxDuration(Duration.ofMinutes(2));

    @Test
    void testBlueGreenCommitAndCleanupInProd() {
        /// Test scenario: initBGDomain -> Warmup -> Commit -> CleanupOrphans
        DbaasAdapterRESTClientV2 adapter = Mockito.mock(DbaasAdapterRESTClientV2.class);
        when(adapter.identifier()).thenReturn("adapter-id");
        doNothing().when(adapter).dropDatabase(any(DatabaseRegistry.class));
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));

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
            // commit execution
            bgState.getOriginNamespace().setState(ACTIVE_STATE);
            bgState.getPeerNamespace().setState(IDLE_STATE);
            blueGreenService.commit(bgStateRequest);
        });
        // check that in PROD mode doesn't delete any databases
        List<DatabaseRegistry> databaseRegistriesNs2 = databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(NS_2);
        assertEquals(4, databaseRegistriesNs2.size(), "Databases should not be deleted in prod mode");
        assertEquals(2, databaseRegistriesNs2.stream().filter(o -> o.getClassifier().containsKey("MARKED_FOR_DROP")).count());
        assertEquals(2, databaseRegistriesNs2.stream().filter(o -> !o.getClassifier().containsKey("MARKED_FOR_DROP")).count());

        // check that commit doesn't affect NS-1
        List<DatabaseRegistry> databaseRegistriesNs1 = databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(NS_1);
        Assertions.assertEquals(3, databaseRegistriesNs1.size());
        Optional<DatabaseRegistry> versionedDatabaseRegistry = databaseRegistriesNs1.stream()
                .filter(dbr -> dbr.getClassifier().containsKey("logicalDbName")).findFirst();
        assertTrue(versionedDatabaseRegistry.isPresent());

        long staticDatabaseRegistryCount = databaseRegistriesNs1.stream()
                .filter(dbr -> !dbr.getClassifier().containsKey("logicalDbName")).count();
        assertEquals(2, staticDatabaseRegistryCount);

        // check that in PROD mode doesn't delete any databases
        List<Database> idleDatabase = databasesRepository.findByNamespace(NS_2);
        Assertions.assertEquals(3, idleDatabase.size(), "Databases should not be deleted in prod mode");
        idleDatabase.stream().filter(db -> db.getBgVersion() != null).forEach(db -> {
            Assertions.assertEquals(ORPHAN, db.getDbState().getDatabaseState());
            List<DatabaseRegistry> registry = db.getDatabaseRegistry();
            assertEquals(1, registry.size());
            assertTrue(registry.get(0).getClassifier().containsKey("MARKED_FOR_DROP"));
        });
        Optional<Database> removedStaticDb = idleDatabase.stream().filter(db -> db.getBgVersion() == null && ORPHAN.equals(db.getDbState().getDatabaseState())).findFirst();
        assertTrue(removedStaticDb.isPresent());
        assertEquals(1, removedStaticDb.get().getDatabaseRegistry().size());
        assertTrue(removedStaticDb.get().getDatabaseRegistry().get(0).getClassifier().containsKey("MARKED_FOR_DROP"));
        Optional<Database> keptStaticDb = idleDatabase.stream().filter(db -> db.getBgVersion() == null && CREATED.equals(db.getDbState().getDatabaseState())).findFirst();
        assertTrue(keptStaticDb.isPresent());
        assertEquals(2, keptStaticDb.get().getDatabaseRegistry().size());
        assertFalse(keptStaticDb.get().getDatabaseRegistry().get(0).getClassifier().containsKey("MARKED_FOR_DROP"));

        BgDomain domain = blueGreenService.getDomain(NS_1);
        log.debug("founded bgDomain {}", domain.getNamespaces());
        Optional<BgNamespace> activeBgNs = domain.getNamespaces().stream().filter(bgns -> ACTIVE_STATE.equals(bgns.getState())).findFirst();
        assertTrue(activeBgNs.isPresent());
        assertEquals(NS_1, activeBgNs.get().getNamespace());

        Optional<BgNamespace> idleBgNs = domain.getNamespaces().stream().filter(bgns -> IDLE_STATE.equals(bgns.getState())).findFirst();
        assertTrue(idleBgNs.isPresent());
        assertEquals(NS_2, idleBgNs.get().getNamespace());

        blueGreenService.dropOrphanDatabases(List.of(NS_1, NS_2), true);
        Failsafe.with(OPERATION_STATUS_RETRY_POLICY).run(() -> {
            List<Database> idleDatabaseAfterDrop = databasesRepository.findByNamespace(NS_2);
            Assertions.assertEquals(1, idleDatabaseAfterDrop.size());
        });
        List<Database> activeDatabaseAfterDrop = databasesRepository.findByNamespace(NS_1);
        Assertions.assertEquals(2, activeDatabaseAfterDrop.size());

        List<DatabaseRegistry> idleDatabaseRegistriesNs2 = databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(NS_2);
        assertEquals(2, idleDatabaseRegistriesNs2.size(), "Only shared static databases should be kept");
    }

    private Database createVersionedDatabase(String namespace, String version) {
        DatabaseRegistry versionedDatabaseRegistry1 = createDatabase(namespace);
        versionedDatabaseRegistry1.getClassifier().put("logicalDbName", "config");
        Database versionedDatabase1 = versionedDatabaseRegistry1.getDatabase();
        versionedDatabase1.setBgVersion(version);
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(versionedDatabaseRegistry1);
        return versionedDatabaseRegistry1.getDatabase();
    }

    private Database createStaticDatabase(String namespace, String extraKey, String extraField) {
        DatabaseRegistry databaseRegistry = createDatabase(namespace);
        if (!Strings.isNullOrEmpty(extraField) && !Strings.isNullOrEmpty(extraKey)) {
            databaseRegistry.getClassifier().put(extraKey, extraField);
        }
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(databaseRegistry);
        return databaseRegistry.getDatabase();
    }

    private static BgStateRequest.BGStateNamespace createBgStateNamespace(String state, String namespace, String version) {
        BgStateRequest.BGStateNamespace bgNamespace1 = new BgStateRequest.BGStateNamespace();
        bgNamespace1.setState(state);
        bgNamespace1.setName(namespace);
        bgNamespace1.setVersion(version);
        return bgNamespace1;
    }

    private DatabaseRegistry createDatabase(String namespace) {
        SortedMap<String, Object> classifier = getClassifier();
        classifier.put("namespace", namespace);

        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setClassifier(classifier);
        database.setType(POSTGRESQL);
        database.setNamespace(namespace);
        database.setConnectionProperties(Arrays.asList(new HashMap<>() {{
            put("username", "user");
            put(ROLE, Role.ADMIN.toString());
        }}));

        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        DatabaseRegistry databaseRegistry = createDatabaseRegistry();
        databaseRegistry.setDatabase(database);
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType(POSTGRESQL);
        databaseRegistry.setNamespace(namespace);
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);

        DbResource resource = new DbResource("someKind", "someName");
        List<DbResource> resources = new ArrayList<>();
        resources.add(resource);
        database.setResources(resources);
        database.setName("exact-classifier-match-test-db");
        database.setAdapterId("adapter-id");
        database.setDbState(new DbState(CREATED));
        return databaseRegistry;
    }

    @NotNull
    private static SortedMap<String, Object> getClassifier() {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("test-key", "test-val");
        classifier.put("scope", "service");
        return classifier;
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
}
