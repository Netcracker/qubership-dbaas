package com.netcracker.cloud.dbaas.integration;

import com.netcracker.cloud.dbaas.DatabaseType;
import com.netcracker.cloud.dbaas.dto.RuleType;
import com.netcracker.cloud.dbaas.dto.composite.CompositeStructureDto;
import com.netcracker.cloud.dbaas.entity.pg.*;
import com.netcracker.cloud.dbaas.entity.pg.role.DatabaseRole;
import com.netcracker.cloud.dbaas.entity.pg.rule.PerMicroserviceRule;
import com.netcracker.cloud.dbaas.entity.pg.rule.PerNamespaceRule;
import com.netcracker.cloud.dbaas.entity.shared.AbstractDbState;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.repositories.dbaas.BalancingRulesDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRolesDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.DatabaseDeclarativeConfigRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.LogicalDbOperationErrorRepository;
import com.netcracker.cloud.dbaas.service.*;
import com.netcracker.cloud.dbaas.service.composite.CompositeNamespaceService;
import com.netcracker.cloud.dbaas.utils.DatabaseBuilder;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.ws.rs.ServiceUnavailableException;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.netcracker.cloud.dbaas.Constants.MICROSERVICE_NAME;
import static com.netcracker.cloud.dbaas.service.DeletionService.MARKED_FOR_DROP;
import static com.netcracker.cloud.dbaas.utils.DatabaseBuilder.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class DeletionServiceTest {
    private static final RetryPolicy<Object> ASYNC_DELETION_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofMillis(100)).withMaxDuration(Duration.ofSeconds(5));

    @Inject
    DeletionService deletionService;
    @InjectMock
    DBaaService dbaaService;
    @InjectMock
    PasswordEncryption encryption;
    @Inject
    DatabaseDbaasRepository databaseDbaasRepository;
    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @InjectSpy
    LogicalDbOperationErrorRepository logicalDbOperationErrorRepository;
    @InjectMock
    UserService userService;
    @Inject
    DatabaseDeclarativeConfigRepository declarativeConfigRepository;
    @Inject
    BalancingRulesDbaasRepository balancingRulesDbaasRepository;
    @Inject
    DatabaseRolesDbaasRepository databaseRolesDbaasRepository;
    @Inject
    CompositeNamespaceService compositeNamespaceService;
    @InjectMock
    DbaaSHelper dbaaSHelper;

    DbaasAdapterRESTClientV2 dbaasAdapterRESTClient;
    @Inject
    AsyncOperations asyncOperations;

    @BeforeEach
    public void prepare() {
        dbaasAdapterRESTClient = Mockito.mock(DbaasAdapterRESTClientV2.class);
        when(dbaasAdapterRESTClient.identifier()).thenReturn(POSTGRES_ADAPTER_ID);
        doNothing().when(dbaasAdapterRESTClient).dropDatabase(any(DatabaseRegistry.class));
        when(dbaaService.getAdapter(eq(POSTGRES_ADAPTER_ID))).thenReturn(Optional.of(dbaasAdapterRESTClient));
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
    }

    @AfterEach
    public void cleanup() {
        databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType().forEach(databaseRegistryDbaasRepository::delete);
    }

    @Test
    void testDropRegistry_withDatabase() {
        Database database = new DatabaseBuilder()
                .registry()
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());
        assertEquals(1, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isPresent());

        deletionService.dropRegistrySafe(database.getDatabaseRegistry().getFirst());

        verify(encryption).deletePassword(eq(database));
        verify(userService).deleteDatabaseUsers(eq(database));
        verify(dbaasAdapterRESTClient).dropDatabase(eq(database.getDatabaseRegistry().getFirst()));
        assertEquals(0, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isEmpty());
    }

    @Test
    void testDropRegistry_onlyRegistry() {
        Database database = new DatabaseBuilder()
                .registry()
                .registry()
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());
        assertEquals(2, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isPresent());

        deletionService.dropRegistrySafe(database.getDatabaseRegistry().getFirst());

        verifyNoInteractions(encryption);
        verifyNoInteractions(userService);
        verifyNoInteractions(dbaasAdapterRESTClient);
        assertEquals(1, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isPresent());
    }

    @Test
    void testDropRegistry_force() {
        Database database = new DatabaseBuilder()
                .registry()
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());
        assertEquals(1, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isPresent());

        doThrow(new RuntimeException("some adapter exception")).when(dbaasAdapterRESTClient).dropDatabase(any(DatabaseRegistry.class));

        QuarkusTransaction.requiringNew().run(() -> {
            deletionService.dropRegistrySafe(database.getDatabaseRegistry().getFirst(), false);
        });
        assertEquals(1, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isPresent());

        QuarkusTransaction.requiringNew().run(() -> {
            deletionService.dropRegistrySafe(database.getDatabaseRegistry().getFirst(), true);
        });
        assertEquals(0, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertFalse(databaseDbaasRepository.findById(database.getId()).isPresent());
    }

    @Test
    void testDropRegistry_notExisting() {
        Database database = new DatabaseBuilder()
                .registry()
                .registry()
                .build();
        assertEquals(0, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isEmpty());

        deletionService.dropRegistrySafe(database.getDatabaseRegistry().getFirst());

        verifyNoInteractions(encryption);
        verifyNoInteractions(userService);
        verifyNoInteractions(dbaasAdapterRESTClient);
        assertEquals(0, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isEmpty());
    }

    @Test
    void testDropRegistry_notYetCreated() {
        Database database = new DatabaseBuilder()
                .state(AbstractDbState.DatabaseStateStatus.PROCESSING)
                .adapterId(null)
                .physicalDatabaseId(null)
                .resources(List.of())
                .connectionProperties(List.of())
                .registry()
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());
        assertEquals(1, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isPresent());

        deletionService.dropRegistrySafe(database.getDatabaseRegistry().getFirst());

        verify(encryption).deletePassword(eq(database));
        verify(userService).deleteDatabaseUsers(eq(database));
        verifyNoInteractions(dbaasAdapterRESTClient);
        assertEquals(0, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isEmpty());
    }

    @Test
    void testDropRegistry_external_withDatabase() {
        Database database = new DatabaseBuilder()
                .external(true)
                .registry()
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());
        assertEquals(1, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isPresent());

        deletionService.dropRegistrySafe(database.getDatabaseRegistry().getFirst());

        verify(encryption).deletePassword(eq(database));
        verifyNoInteractions(userService);
        verifyNoInteractions(dbaasAdapterRESTClient);
        assertEquals(0, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isEmpty());
    }

    @Test
    void testDropRegistry_external_onlyRegistry() {
        Database database = new DatabaseBuilder()
                .external(true)
                .registry()
                .registry()
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());
        assertEquals(2, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isPresent());

        deletionService.dropRegistrySafe(database.getDatabaseRegistry().getFirst());

        verifyNoInteractions(encryption);
        verifyNoInteractions(userService);
        verifyNoInteractions(dbaasAdapterRESTClient);
        assertEquals(1, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isPresent());
    }

    @Test
    void testRegisterDeletionError() {
        Database database = new DatabaseBuilder()
                .registry()
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());
        assertEquals(1, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(database.getId()).isPresent());
        doThrow(new ServiceUnavailableException()).when(dbaasAdapterRESTClient).dropDatabase(database.getDatabaseRegistry().getFirst());

        QuarkusTransaction.requiringNew().run(() -> {
            deletionService.dropRegistrySafe(database.getDatabaseRegistry().getFirst());
        });

        assertTrue(databaseDbaasRepository.findById(database.getId()).isPresent());
        verify(logicalDbOperationErrorRepository).persist(any(LogicalDbOperationError.class));
        assertTrue(logicalDbOperationErrorRepository.findAll().stream().anyMatch(error -> error.getDatabase().getId().equals(database.getId())));
        verify(encryption, times(0)).deletePassword(any(Database.class));
        assertEquals(1, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        Database updatedDatabase = databaseDbaasRepository.findById(database.getId()).get();
        assertEquals(AbstractDbState.DatabaseStateStatus.DELETING_FAILED, updatedDatabase.getDbState().getDatabaseState());
    }

    @Test
    void testMarkForDrop() {
        Database database = new DatabaseBuilder()
                .registry()
                .build();
        assertEquals(DbState.DatabaseStateStatus.CREATED, database.getDbState().getDatabaseState());
        assertFalse(DeletionService.isOrphan(database.getDatabaseRegistry().getFirst()));
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());

        deletionService.markRegistryForDrop(database.getDatabaseRegistry().getFirst());

        Database reloadedDatabase = databaseDbaasRepository.findById(database.getId()).orElseThrow();
        assertTrue(DeletionService.isMarkedForDrop(reloadedDatabase.getDatabaseRegistry().getFirst()));
        Assertions.assertEquals(MARKED_FOR_DROP, reloadedDatabase.getDatabaseRegistry().getFirst().getClassifier().get(MARKED_FOR_DROP));
        assertTrue(reloadedDatabase.isMarkedForDrop());
        assertEquals(DbState.DatabaseStateStatus.DELETING, reloadedDatabase.getDbState().getDatabaseState());
    }

    @Test
    void testMarkForDrop_allRegistries() {
        Database database = new DatabaseBuilder()
                .registry()
                .registry()
                .build();
        assertEquals(DbState.DatabaseStateStatus.CREATED, database.getDbState().getDatabaseState());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());

        deletionService.markRegistriesForDrop(TEST_NS, database.getDatabaseRegistry());

        Database reloadedDatabase = databaseDbaasRepository.findById(database.getId()).orElseThrow();
        reloadedDatabase.getDatabaseRegistry().forEach(registry -> {
            assertTrue(DeletionService.isMarkedForDrop(registry));
        });
        assertEquals(DbState.DatabaseStateStatus.DELETING, reloadedDatabase.getDbState().getDatabaseState());
        assertTrue(reloadedDatabase.isMarkedForDrop());
        reloadedDatabase.getDatabaseRegistry().forEach(registry ->
                Assertions.assertEquals(MARKED_FOR_DROP, registry.getClassifier().get(MARKED_FOR_DROP))
        );
    }

    @Test
    void testMarkForDrop_notAllRegistries() {
        Database database = new DatabaseBuilder()
                .registry()
                .registry()
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());
        assertEquals(DbState.DatabaseStateStatus.CREATED, database.getDbState().getDatabaseState());

        deletionService.markRegistryForDrop(database.getDatabaseRegistry().getFirst());

        assertTrue(DeletionService.isMarkedForDrop(database.getDatabaseRegistry().getFirst()));
        assertFalse(DeletionService.isMarkedForDrop(database.getDatabaseRegistry().getLast()));
        assertEquals(DbState.DatabaseStateStatus.CREATED, database.getDbState().getDatabaseState());
        assertFalse(database.isMarkedForDrop());
        assertEquals(MARKED_FOR_DROP, database.getDatabaseRegistry().getFirst().getClassifier().get(MARKED_FOR_DROP));
        assertNull(database.getDatabaseRegistry().getLast().getClassifier().get(MARKED_FOR_DROP));
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    @Test
    void testMarkForDrop_namespace() {
        Database database1 = new DatabaseBuilder()
                .registry()
                .registry(b -> b.namespace(TEST_NS + "_2"))
                .build();
        Database database2 = new DatabaseBuilder()
                .registry()
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database1.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database2.getDatabaseRegistry().getFirst());
        assertEquals(DbState.DatabaseStateStatus.CREATED, database1.getDbState().getDatabaseState());
        assertEquals(DbState.DatabaseStateStatus.CREATED, database2.getDbState().getDatabaseState());

        deletionService.markNamespaceRegistriesForDrop(TEST_NS);

        Database reloadedDatabase1 = databaseDbaasRepository.findById(database1.getId()).get();
        Database reloadedDatabase2 = databaseDbaasRepository.findById(database2.getId()).get();
        assertTrue(DeletionService.isMarkedForDrop(reloadedDatabase1.getDatabaseRegistry().stream().filter(r -> TEST_NS.equals(r.getNamespace())).findFirst().get()));
        assertTrue(DeletionService.isMarkedForDrop(reloadedDatabase2.getDatabaseRegistry().getFirst()));
        assertFalse(DeletionService.isMarkedForDrop(reloadedDatabase1.getDatabaseRegistry().stream().filter(r -> !TEST_NS.equals(r.getNamespace())).findFirst().get()));
        assertEquals(MARKED_FOR_DROP, reloadedDatabase1.getDatabaseRegistry().stream().filter(r -> TEST_NS.equals(r.getNamespace())).findFirst().get().getClassifier().get(MARKED_FOR_DROP));
        assertEquals(MARKED_FOR_DROP, reloadedDatabase2.getDatabaseRegistry().getFirst().getClassifier().get(MARKED_FOR_DROP));
        assertNull(reloadedDatabase1.getDatabaseRegistry().stream().filter(r -> !TEST_NS.equals(r.getNamespace())).findFirst().get().getClassifier().get(MARKED_FOR_DROP));
        assertEquals(DbState.DatabaseStateStatus.DELETING, reloadedDatabase2.getDbState().getDatabaseState());
        assertEquals(DbState.DatabaseStateStatus.CREATED, reloadedDatabase1.getDbState().getDatabaseState());
        assertTrue(reloadedDatabase2.isMarkedForDrop());
        assertFalse(reloadedDatabase1.isMarkedForDrop());
    }

    @Test
    void testMarkForDrop_deleteAndCreate() {
        Database database = new DatabaseBuilder()
                .classifier(Map.of(
                        "scope", "service",
                        "namespace", TEST_NS,
                        "microserviceName", TEST_MS
                ))
                .registry()
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());
        assertEquals(DbState.DatabaseStateStatus.CREATED, database.getDbState().getDatabaseState());

        QuarkusTransaction.requiringNew().run(() -> {
            var currentRegisteredDatabases = databaseRegistryDbaasRepository.findInternalDatabaseRegistryByNamespace(TEST_NS);
            currentRegisteredDatabases = deletionService.markRegistriesForDrop(TEST_NS, currentRegisteredDatabases);
            deletionService.dropRegistriesSafe(TEST_NS, currentRegisteredDatabases);

            Database newDatabase = new DatabaseBuilder()
                    .classifier(Map.of(
                            "scope", "service",
                            "namespace", TEST_NS,
                            "microserviceName", TEST_MS
                    ))
                    .registry()
                    .build();
            databaseRegistryDbaasRepository.saveAll(newDatabase.getDatabaseRegistry());
            assertEquals(DbState.DatabaseStateStatus.CREATED, newDatabase.getDbState().getDatabaseState());
        });
    }

    @Test
    void testMarkAsOrphan() {
        Database database = new DatabaseBuilder()
                .registry()
                .registry()
                .build();
        assertEquals(DbState.DatabaseStateStatus.CREATED, database.getDbState().getDatabaseState());
        assertFalse(DeletionService.isOrphan(database.getDatabaseRegistry().getFirst()));
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());

        deletionService.markDatabaseAsOrphan(database.getDatabaseRegistry().getFirst());

        Database reloadedDatabase = databaseDbaasRepository.findById(database.getId()).orElseThrow();
        reloadedDatabase.getDatabaseRegistry().forEach(registry -> {
            assertTrue(DeletionService.isOrphan(registry));
            Assertions.assertEquals(MARKED_FOR_DROP, registry.getClassifier().get(MARKED_FOR_DROP));
        });
        assertTrue(reloadedDatabase.isMarkedForDrop());
        assertEquals(DbState.DatabaseStateStatus.ORPHAN, reloadedDatabase.getDbState().getDatabaseState());
    }

    @Test
    void testMarkAsOrphan_severalDBs() {
        Database database1 = new DatabaseBuilder()
                .registry()
                .registry()
                .build();
        Database database2 = new DatabaseBuilder()
                .registry()
                .registry()
                .build();
        assertEquals(DbState.DatabaseStateStatus.CREATED, database1.getDbState().getDatabaseState());
        assertEquals(DbState.DatabaseStateStatus.CREATED, database2.getDbState().getDatabaseState());
        assertFalse(DeletionService.isOrphan(database1.getDatabaseRegistry().getFirst()));
        assertFalse(DeletionService.isOrphan(database2.getDatabaseRegistry().getFirst()));
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database1.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database2.getDatabaseRegistry().getFirst());

        deletionService.markDatabasesAsOrphan(List.of(database1.getDatabaseRegistry().getFirst(), database2.getDatabaseRegistry().getFirst()));

        Database reloadedDatabase1 = databaseDbaasRepository.findById(database1.getId()).orElseThrow();
        Database reloadedDatabase2 = databaseDbaasRepository.findById(database2.getId()).orElseThrow();
        reloadedDatabase1.getDatabaseRegistry().forEach(registry -> {
            assertTrue(DeletionService.isOrphan(registry));
            Assertions.assertEquals(MARKED_FOR_DROP, registry.getClassifier().get(MARKED_FOR_DROP));
        });
        reloadedDatabase2.getDatabaseRegistry().forEach(registry -> {
            assertTrue(DeletionService.isOrphan(registry));
            Assertions.assertEquals(MARKED_FOR_DROP, registry.getClassifier().get(MARKED_FOR_DROP));
        });
        assertTrue(reloadedDatabase1.isMarkedForDrop());
        assertTrue(reloadedDatabase2.isMarkedForDrop());
        assertEquals(DbState.DatabaseStateStatus.ORPHAN, reloadedDatabase1.getDbState().getDatabaseState());
        assertEquals(DbState.DatabaseStateStatus.ORPHAN, reloadedDatabase2.getDbState().getDatabaseState());
    }

    @Test
    void testMarkAsOrphan_shouldBeMarkedForDrop() {
        Database database = new DatabaseBuilder()
                .registry()
                .build();
        assertEquals(DbState.DatabaseStateStatus.CREATED, database.getDbState().getDatabaseState());
        assertFalse(DeletionService.isOrphan(database.getDatabaseRegistry().getFirst()));
        assertFalse(DeletionService.isMarkedForDrop(database.getDatabaseRegistry().getFirst()));

        deletionService.markDatabaseAsOrphan(database.getDatabaseRegistry().getFirst());

        assertTrue(DeletionService.isOrphan(database.getDatabaseRegistry().getFirst()));
        assertTrue(DeletionService.isMarkedForDrop(database.getDatabaseRegistry().getFirst()));
    }

    @Test
    void testGetOrphanDatabases() {
        Database orphanDb = new DatabaseBuilder()
                .registry()
                .build();
        deletionService.markDatabaseAsOrphan(orphanDb.getDatabaseRegistry().getFirst());
        Database mfdDb = new DatabaseBuilder()
                .registry()
                .build();
        deletionService.markRegistryForDrop(mfdDb.getDatabaseRegistry().getFirst());

        databaseRegistryDbaasRepository.saveAnyTypeLogDb(orphanDb.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(mfdDb.getDatabaseRegistry().getFirst());
        assertEquals(2, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());

        List<DatabaseRegistry> orphanDatabases = deletionService.getOrphanRegistries(List.of(TEST_NS));
        Assertions.assertEquals(1, orphanDatabases.size());
        Assertions.assertEquals(orphanDb.getDatabaseRegistry().getFirst(), orphanDatabases.getFirst());
    }

    @Test
    void testCleanupMarkedForDropRegistries() {
        Database mfdDatabase = new DatabaseBuilder()
                .registry()
                .build();
        Database justDatabase = new DatabaseBuilder()
                .registry()
                .build();
        Database opensearchMfdDatabase = new DatabaseBuilder()
                .type(DatabaseType.OPENSEARCH.toString())
                .registry()
                .build();
        deletionService.markRegistryForDrop(mfdDatabase.getDatabaseRegistry().getFirst());
        deletionService.markRegistryForDrop(opensearchMfdDatabase.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(mfdDatabase.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(justDatabase.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(opensearchMfdDatabase.getDatabaseRegistry().getFirst());
        assertEquals(3, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());

        CountDownLatch countDownLatch = new CountDownLatch(1);
        asyncOperations.getCleanupExecutor().submit(() -> {
            try {
                //noinspection ResultOfMethodCallIgnored
                countDownLatch.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });
        deletionService.cleanupMarkedForDropRegistries(TEST_NS);

        assertFalse(databaseDbaasRepository.findById(opensearchMfdDatabase.getId()).isPresent(), "OpenSearch database is not removed synchronously");
        countDownLatch.countDown();
        Failsafe.with(ASYNC_DELETION_RETRY_POLICY).run(() -> {
            assertEquals(1, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        });
        verify(encryption).deletePassword(eq(mfdDatabase));
        verify(userService).deleteDatabaseUsers(eq(mfdDatabase));
        verify(dbaasAdapterRESTClient).dropDatabase(eq(mfdDatabase.getDatabaseRegistry().getFirst()));
        verify(encryption).deletePassword(eq(opensearchMfdDatabase));
        verify(userService).deleteDatabaseUsers(eq(opensearchMfdDatabase));
        verify(dbaasAdapterRESTClient).dropDatabase(eq(opensearchMfdDatabase.getDatabaseRegistry().getFirst()));
        verifyNoMoreInteractions(encryption);
        verifyNoMoreInteractions(userService);
        verifyNoMoreInteractions(dbaasAdapterRESTClient);
        assertTrue(databaseDbaasRepository.findById(mfdDatabase.getId()).isEmpty());
        assertFalse(databaseDbaasRepository.findById(justDatabase.getId()).isEmpty());
        assertFalse(databaseRegistryDbaasRepository.findDatabaseRegistryById(justDatabase.getDatabaseRegistry().getFirst().getId()).isEmpty());
    }

    @Test
    void testCleanupOrphanRegistriesAsync() {
        Database mkdDatabase = new DatabaseBuilder()
                .registry()
                .build();
        Database orphanDatabase = new DatabaseBuilder()
                .classifier(MICROSERVICE_NAME, "another")
                .type(DatabaseType.OPENSEARCH.toString())
                .registry()
                .build();
        deletionService.markRegistryForDrop(mkdDatabase.getDatabaseRegistry().getFirst());
        deletionService.markDatabaseAsOrphan(orphanDatabase.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(mkdDatabase.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(orphanDatabase.getDatabaseRegistry().getFirst());
        assertEquals(2, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(mkdDatabase.getId()).isPresent());
        assertTrue(databaseDbaasRepository.findById(orphanDatabase.getId()).isPresent());

        CountDownLatch countDownLatch = new CountDownLatch(1);
        asyncOperations.getCleanupExecutor().submit(() -> {
            try {
                //noinspection ResultOfMethodCallIgnored
                countDownLatch.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });
        deletionService.cleanupOrphanRegistriesAsync(TEST_NS);

        assertTrue(databaseDbaasRepository.findById(orphanDatabase.getId()).isPresent(), "OpenSearch database is removed synchronously");
        countDownLatch.countDown();
        Failsafe.with(ASYNC_DELETION_RETRY_POLICY).run(() -> {
            assertEquals(1, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        });
        assertTrue(databaseRegistryDbaasRepository.findDatabaseRegistryById(mkdDatabase.getDatabaseRegistry().getFirst().getId()).isPresent());
        assertTrue(databaseRegistryDbaasRepository.findDatabaseRegistryById(orphanDatabase.getDatabaseRegistry().getFirst().getId()).isEmpty());
        assertTrue(databaseDbaasRepository.findById(mkdDatabase.getId()).isPresent());
        assertTrue(databaseDbaasRepository.findById(orphanDatabase.getId()).isEmpty());
    }

    @Test
    void testCleanupMarkedForDropRegistriesAsync() {
        Database mkdDatabase = new DatabaseBuilder()
                .registry()
                .build();
        Database orphanDatabase = new DatabaseBuilder()
                .classifier(MICROSERVICE_NAME, "another")
                .registry()
                .build();
        Database opensearchMfdDatabase = new DatabaseBuilder()
                .type(DatabaseType.OPENSEARCH.toString())
                .registry()
                .build();
        deletionService.markRegistryForDrop(mkdDatabase.getDatabaseRegistry().getFirst());
        deletionService.markDatabaseAsOrphan(orphanDatabase.getDatabaseRegistry().getFirst());
        deletionService.markRegistryForDrop(opensearchMfdDatabase.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(mkdDatabase.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(orphanDatabase.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(opensearchMfdDatabase.getDatabaseRegistry().getFirst());
        assertEquals(3, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        assertTrue(databaseDbaasRepository.findById(mkdDatabase.getId()).isPresent());
        assertTrue(databaseDbaasRepository.findById(orphanDatabase.getId()).isPresent());
        assertTrue(databaseDbaasRepository.findById(opensearchMfdDatabase.getId()).isPresent());

        CountDownLatch countDownLatch = new CountDownLatch(1);
        asyncOperations.getCleanupExecutor().submit(() -> {
            try {
                //noinspection ResultOfMethodCallIgnored
                countDownLatch.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });
        deletionService.cleanupMarkedForDropRegistriesAsync(TEST_NS, false);

        assertTrue(databaseDbaasRepository.findById(opensearchMfdDatabase.getId()).isPresent(), "OpenSearch database is removed synchronously");
        countDownLatch.countDown();
        Failsafe.with(ASYNC_DELETION_RETRY_POLICY).run(() -> {
            assertEquals(0, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        });
        assertTrue(databaseRegistryDbaasRepository.findDatabaseRegistryById(mkdDatabase.getDatabaseRegistry().getFirst().getId()).isEmpty());
        assertTrue(databaseRegistryDbaasRepository.findDatabaseRegistryById(orphanDatabase.getDatabaseRegistry().getFirst().getId()).isEmpty());
        assertTrue(databaseDbaasRepository.findById(mkdDatabase.getId()).isEmpty());
        assertTrue(databaseDbaasRepository.findById(orphanDatabase.getId()).isEmpty());
    }

    @Test
    void testCleanupNamespaceResources() {
        DatabaseDeclarativeConfig declarativeConfig = new DatabaseDeclarativeConfig();
        declarativeConfig.setNamespace(TEST_NS);
        declarativeConfig.setLazy(true);
        declarativeConfig.setClassifier(new TreeMap<>());
        declarativeConfig.setType(DatabaseType.POSTGRESQL.toString());
        PerNamespaceRule perNamespaceRule = new PerNamespaceRule();
        perNamespaceRule.setName(UUID.randomUUID().toString());
        perNamespaceRule.setNamespace(TEST_NS);
        perNamespaceRule.setPhysicalDatabaseIdentifier(POSTGRES_PHY_DB_ID);
        perNamespaceRule.setDatabaseType(DatabaseType.POSTGRESQL.toString());
        perNamespaceRule.setRuleType(RuleType.NAMESPACE);
        PerMicroserviceRule perMicroserviceRule = new PerMicroserviceRule();
        perMicroserviceRule.setMicroservice(TEST_MS);
        perMicroserviceRule.setNamespace(TEST_NS);
        perMicroserviceRule.setType(DatabaseType.POSTGRESQL.toString());
        perMicroserviceRule.setCreateDate(new Date());
        perMicroserviceRule.setUpdateDate(new Date());
        perMicroserviceRule.setGeneration(0);
        DatabaseRole databaseRole = new DatabaseRole();
        databaseRole.setMicroserviceName(TEST_MS);
        databaseRole.setNamespace(TEST_NS);
        declarativeConfigRepository.persist(declarativeConfig);
        balancingRulesDbaasRepository.save(perNamespaceRule);
        balancingRulesDbaasRepository.save(perMicroserviceRule);
        databaseRolesDbaasRepository.save(databaseRole);
        assertEquals(1, declarativeConfigRepository.findAllByNamespace(TEST_NS).size());
        assertEquals(1, balancingRulesDbaasRepository.findByNamespace(TEST_NS).size());
        assertEquals(1, balancingRulesDbaasRepository.findPerMicroserviceByNamespace(TEST_NS).size());
        assertEquals(1, databaseRolesDbaasRepository.findAllByMicroserviceNameAndNamespace(TEST_MS, TEST_NS).size());

        deletionService.cleanupNamespaceResources(TEST_NS, false);

        assertEquals(0, declarativeConfigRepository.findAllByNamespace(TEST_NS).size());
        assertEquals(1, balancingRulesDbaasRepository.findByNamespace(TEST_NS).size());
        assertEquals(1, balancingRulesDbaasRepository.findPerMicroserviceByNamespace(TEST_NS).size());
        assertEquals(0, databaseRolesDbaasRepository.findAllByMicroserviceNameAndNamespace(TEST_MS, TEST_NS).size());

        deletionService.cleanupNamespaceResources(TEST_NS, true);

        assertEquals(0, balancingRulesDbaasRepository.findByNamespace(TEST_NS).size());
        assertEquals(0, balancingRulesDbaasRepository.findPerMicroserviceByNamespace(TEST_NS).size());
    }

    @Test
    void testCleanupNamespaceFullAsync() {
        DatabaseDeclarativeConfig declarativeConfig = new DatabaseDeclarativeConfig();
        declarativeConfig.setNamespace(TEST_NS);
        declarativeConfig.setLazy(true);
        declarativeConfig.setClassifier(new TreeMap<>());
        declarativeConfig.setType(DatabaseType.POSTGRESQL.toString());
        PerNamespaceRule perNamespaceRule = new PerNamespaceRule();
        perNamespaceRule.setName(UUID.randomUUID().toString());
        perNamespaceRule.setNamespace(TEST_NS);
        perNamespaceRule.setPhysicalDatabaseIdentifier(POSTGRES_PHY_DB_ID);
        perNamespaceRule.setDatabaseType(DatabaseType.POSTGRESQL.toString());
        perNamespaceRule.setRuleType(RuleType.NAMESPACE);
        PerMicroserviceRule perMicroserviceRule = new PerMicroserviceRule();
        perMicroserviceRule.setMicroservice(TEST_MS);
        perMicroserviceRule.setNamespace(TEST_NS);
        perMicroserviceRule.setType(DatabaseType.POSTGRESQL.toString());
        perMicroserviceRule.setCreateDate(new Date());
        perMicroserviceRule.setUpdateDate(new Date());
        perMicroserviceRule.setGeneration(0);
        DatabaseRole databaseRole = new DatabaseRole();
        databaseRole.setMicroserviceName(TEST_MS);
        databaseRole.setNamespace(TEST_NS);
        CompositeStructureDto compositeStructure = new CompositeStructureDto("aa", new HashSet<>(Set.of(TEST_NS)));
        Database database1 = new DatabaseBuilder()
                .registry()
                .registry(r -> r.namespace(TEST_NS + "_2"))
                .build();
        Database database2 = new DatabaseBuilder()
                .registry()
                .build();
        Database opensearchDatabase = new DatabaseBuilder()
                .type(DatabaseType.OPENSEARCH.toString())
                .registry()
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database1.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database2.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(opensearchDatabase.getDatabaseRegistry().getFirst());
        compositeNamespaceService.saveOrUpdateCompositeStructure(compositeStructure);
        declarativeConfigRepository.persist(declarativeConfig);
        balancingRulesDbaasRepository.save(perNamespaceRule);
        balancingRulesDbaasRepository.save(perMicroserviceRule);
        databaseRolesDbaasRepository.save(databaseRole);
        assertEquals(1, declarativeConfigRepository.findAllByNamespace(TEST_NS).size());
        assertEquals(1, balancingRulesDbaasRepository.findByNamespace(TEST_NS).size());
        assertEquals(1, balancingRulesDbaasRepository.findPerMicroserviceByNamespace(TEST_NS).size());
        assertEquals(1, databaseRolesDbaasRepository.findAllByMicroserviceNameAndNamespace(TEST_MS, TEST_NS).size());
        assertTrue(compositeNamespaceService.getBaselineByNamespace(TEST_NS).isPresent());
        assertTrue(databaseDbaasRepository.findById(database1.getId()).isPresent());
        assertTrue(databaseDbaasRepository.findById(database2.getId()).isPresent());
        assertEquals(3, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());

        assertFalse(deletionService.checkNamespaceAlreadyDropped(TEST_NS));

        CountDownLatch countDownLatch = new CountDownLatch(1);
        asyncOperations.getCleanupExecutor().submit(() -> {
            try {
                //noinspection ResultOfMethodCallIgnored
                countDownLatch.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });
        QuarkusTransaction.requiringNew().run(() -> {
            DeletionService.CleanupResult cleanupResult = deletionService.cleanupNamespaceFullAsync(TEST_NS, false);
            assertEquals(1, cleanupResult.databasesSyncDeletedCount());
            assertEquals(2, cleanupResult.databasesAsyncDeletionScheduledCount());
        });

        assertFalse(databaseDbaasRepository.findById(opensearchDatabase.getId()).isPresent());
        countDownLatch.countDown();
        Failsafe.with(ASYNC_DELETION_RETRY_POLICY).run(() -> {
            assertEquals(0, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        });
        assertEquals(0, declarativeConfigRepository.findAllByNamespace(TEST_NS).size());
        assertEquals(1, balancingRulesDbaasRepository.findByNamespace(TEST_NS).size());
        assertEquals(1, balancingRulesDbaasRepository.findPerMicroserviceByNamespace(TEST_NS).size());
        assertEquals(0, databaseRolesDbaasRepository.findAllByMicroserviceNameAndNamespace(TEST_MS, TEST_NS).size());
        assertFalse(compositeNamespaceService.getBaselineByNamespace(TEST_NS).isPresent());
        assertTrue(databaseDbaasRepository.findById(database1.getId()).isPresent());
        assertFalse(databaseRegistryDbaasRepository.findDatabaseRegistryById(database1.getDatabaseRegistry().getFirst().getId()).isPresent());
        assertTrue(databaseRegistryDbaasRepository.findDatabaseRegistryById(database1.getDatabaseRegistry().getLast().getId()).isPresent());
        assertFalse(databaseDbaasRepository.findById(database2.getId()).isPresent());

        assertFalse(deletionService.checkNamespaceAlreadyDropped(TEST_NS));

        DeletionService.CleanupResult cleanupResult = deletionService.cleanupNamespaceFullAsync(TEST_NS, true);
        assertEquals(0, cleanupResult.databasesSyncDeletedCount());
        assertEquals(0, cleanupResult.databasesAsyncDeletionScheduledCount());

        assertEquals(0, balancingRulesDbaasRepository.findByNamespace(TEST_NS).size());
        assertEquals(0, balancingRulesDbaasRepository.findPerMicroserviceByNamespace(TEST_NS).size());

        assertTrue(deletionService.checkNamespaceAlreadyDropped(TEST_NS));
    }

    @Test
    void testCleanupNamespaceFullAsync_brokenDBs() {
        Database database1 = new DatabaseBuilder()
                .state(AbstractDbState.DatabaseStateStatus.PROCESSING)
                .connectionProperties(List.of())
                .resources(List.of())
                .physicalDatabaseId(null)
                .adapterId(null)
                .registry()
                .build();
        Database database2 = new DatabaseBuilder()
                .registry()
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database1.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database2.getDatabaseRegistry().getFirst());
        assertTrue(databaseDbaasRepository.findById(database1.getId()).isPresent());
        assertTrue(databaseDbaasRepository.findById(database2.getId()).isPresent());
        doNothing().when(dbaasAdapterRESTClient).dropDatabase(argThat(argument -> argument.getId().equals(database1.getDatabaseRegistry().getFirst().getId())));
        doThrow(new RuntimeException("hawwo")).when(dbaasAdapterRESTClient).dropDatabase(argThat(argument -> argument.getId().equals(database2.getDatabaseRegistry().getFirst().getId())));

        assertFalse(deletionService.checkNamespaceAlreadyDropped(TEST_NS));

        deletionService.cleanupNamespaceFullAsync(TEST_NS, true);

        Failsafe.with(ASYNC_DELETION_RETRY_POLICY).run(() -> {
            assertEquals(1, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        });
        assertFalse(databaseDbaasRepository.findById(database1.getId()).isPresent());
        assertTrue(databaseDbaasRepository.findById(database2.getId()).isPresent());
        assertFalse(databaseRegistryDbaasRepository.findDatabaseRegistryById(database1.getDatabaseRegistry().getFirst().getId()).isPresent());
        assertTrue(databaseRegistryDbaasRepository.findDatabaseRegistryById(database2.getDatabaseRegistry().getFirst().getId()).isPresent());
    }

    @Test
    void testCleanupAllLogicalDatabasesInNamespacesByPortionsAsync() {
        Database database = new DatabaseBuilder()
                .registry()
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());
        assertEquals(1, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());

        deletionService.cleanupAllLogicalDatabasesInNamespacesByPortionsAsync(Set.of(TEST_NS));

        Failsafe.with(ASYNC_DELETION_RETRY_POLICY).run(() -> {
            assertEquals(0, databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NS).size());
        });
    }
}
