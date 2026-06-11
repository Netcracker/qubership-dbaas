package com.netcracker.cloud.dbaas.service;


import com.netcracker.cloud.dbaas.dto.EnsuredUser;
import com.netcracker.cloud.dbaas.dto.role.Role;
import com.netcracker.cloud.dbaas.dto.v3.PasswordChangeRequestV3;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.DbState;
import com.netcracker.cloud.dbaas.entity.shared.AbstractDbState;
import com.netcracker.cloud.dbaas.exceptions.PasswordChangeFailedException;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.enums.OperatorEventType;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies that a successful rotation in a partial-batch is committed to the DB even when a
 * later database in the same batch causes PasswordChangeFailedException (which rolls back the
 * outer transaction).  The REQUIRES_NEW sub-transaction in PasswordRotationCommitService must
 * survive that outer rollback.
 */
@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class DBaaServiceQuarkusTest {

    private static final String NAMESPACE = "rotation-tx-test-ns";
    private static final String DB_TYPE = "mongodb";
    private static final String ADAPTER_ID_1 = "adapter-1";
    private static final String ADAPTER_ID_2 = "adapter-2";
    private static final String PHYS_DB_ID = "phys-db-id";

    @Inject
    DBaaService dBaaService;

    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @InjectMock
    PhysicalDatabasesService physicalDatabasesService;

    @InjectMock
    OperatorEventOutboxWriter operatorEventOutboxWriter;

    @AfterEach
    void cleanUp() {
        databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType()
                .stream()
                .filter(dbr -> NAMESPACE.equals(dbr.getNamespace()))
                .forEach(databaseRegistryDbaasRepository::delete);
    }


    @Test
    void partialBatchFailure_successfulRotationCommittedDespiteOuterRollback() {
        // --- arrange databases -------------------------------------------------
        String oldUsername1 = "old-user-1";
        String oldUsername2 = "old-user-2";
        String newUsername1 = "new-user-1";
        String msName1 = "ms-rotation-1";
        String msName2 = "ms-rotation-2";

        SortedMap<String, Object> classifier1 = buildClassifier(NAMESPACE, msName1);
        SortedMap<String, Object> classifier2 = buildClassifier(NAMESPACE, msName2);

        DatabaseRegistry registry1 = buildRegistry(classifier1, ADAPTER_ID_1, oldUsername1, "db-name-1");
        DatabaseRegistry registry2 = buildRegistry(classifier2, ADAPTER_ID_2, oldUsername2, "db-name-2");

        databaseRegistryDbaasRepository.saveAll(List.of(registry1, registry2));

        // --- arrange adapters --------------------------------------------------
        DbaasAdapter adapter1 = Mockito.mock(DbaasAdapter.class);
        DbaasAdapter adapter2 = Mockito.mock(DbaasAdapter.class);

        Mockito.when(adapter1.identifier()).thenReturn(ADAPTER_ID_1);
        Mockito.when(adapter2.identifier()).thenReturn(ADAPTER_ID_2);
        Mockito.when(adapter1.isUsersSupported()).thenReturn(true);
        Mockito.when(adapter2.isUsersSupported()).thenReturn(true);

        Map<String, Object> newCp1 = new HashMap<>();
        newCp1.put("username", newUsername1);
        newCp1.put("role", Role.ADMIN.toString());

        doReturn(buildEnsuredUser(newCp1)).when(adapter1).ensureUser(any(), any(), any(), any());
        doThrow(new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR)).when(adapter2).ensureUser(any(), any(), any(), any());

        Mockito.when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter1, adapter2));

        // encryption is mocked — encryptPassword/deletePassword are no-ops,
        // so connectionProperties remain in plain form after commitRotation persists them

        // --- act ---------------------------------------------------------------
        PasswordChangeRequestV3 request = new PasswordChangeRequestV3();
        request.setType(DB_TYPE);
        // null classifier → namespace-wide batch

        PasswordChangeFailedException thrown = assertThrows(
                PasswordChangeFailedException.class,
                () -> dBaaService.changeUserPassword(request, NAMESPACE)
        );

        // --- assert response ---------------------------------------------------
        assertEquals(1, thrown.getResponse().getChanged().size());
        assertEquals(1, thrown.getResponse().getFailed().size());
        assertEquals(500, thrown.getStatus());

        // --- assert transaction isolation: db1 persisted, db2 unchanged --------
        Optional<DatabaseRegistry> persistedReg1 =
                databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier1, DB_TYPE);
        assertTrue(persistedReg1.isPresent());
        Map<String, Object> cp1 = getConnectionProperties(persistedReg1.get(), Role.ADMIN.toString());
        assertEquals(newUsername1, cp1.get("username"),
                "db1 must have the new username committed by REQUIRES_NEW transaction");

        Optional<DatabaseRegistry> persistedReg2 =
                databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier2, DB_TYPE);
        assertTrue(persistedReg2.isPresent());
        Map<String, Object> cp2 = getConnectionProperties(persistedReg2.get(), Role.ADMIN.toString());
        assertEquals(oldUsername2, cp2.get("username"),
                "db2 must still have the old username — its adapter call threw before commitRotation");
    }

    @Test
    void multiRoleDatabase_enqueueCalledOncePerDatabase() {
        SortedMap<String, Object> classifier = buildClassifier(NAMESPACE, "ms-multi-role");
        DatabaseRegistry registry = buildRegistry(classifier, ADAPTER_ID_1, "user-admin", "db-multi");
        registry.getConnectionProperties().add(new HashMap<>() {{
            put("username", "user-rw");
            put("role", "rw");
        }});
        databaseRegistryDbaasRepository.saveInternalDatabase(registry);

        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        Mockito.when(adapter.identifier()).thenReturn(ADAPTER_ID_1);
        Mockito.when(adapter.isUsersSupported()).thenReturn(true);
        doReturn(buildEnsuredUser(Map.of("username", "user-admin", "role", Role.ADMIN.toString())))
                .when(adapter).ensureUser(eq("user-admin"), any(), any(), eq(Role.ADMIN.toString()));
        doReturn(buildEnsuredUser(Map.of("username", "user-rw", "role", "rw")))
                .when(adapter).ensureUser(eq("user-rw"), any(), any(), eq("rw"));
        Mockito.when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));

        dBaaService.performChangePassword(List.of(registry), null);

        verify(operatorEventOutboxWriter, times(1))
                .enqueue(eq(OperatorEventType.ROTATION_OCCURRED), any(), any());
    }

    // ---------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------

    private SortedMap<String, Object> buildClassifier(String namespace, String microserviceName) {
        SortedMap<String, Object> c = new TreeMap<>();
        c.put("namespace", namespace);
        c.put("microserviceName", microserviceName);
        c.put("scope", "service");
        return c;
    }

    private DatabaseRegistry buildRegistry(SortedMap<String, Object> classifier,
                                           String adapterId,
                                           String username,
                                           String dbName) {
        Map<String, Object> cp = new HashMap<>();
        cp.put("username", username);
        cp.put("role", Role.ADMIN.toString());

        DbState state = new DbState();
        state.setState(AbstractDbState.DatabaseStateStatus.CREATED);
        state.setDatabaseState(AbstractDbState.DatabaseStateStatus.CREATED);

        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setAdapterId(adapterId);
        database.setPhysicalDatabaseId(PHYS_DB_ID);
        database.setName(dbName);
        database.setExternallyManageable(false);
        database.setConnectionProperties(new ArrayList<>(List.of(cp)));
        database.setDbState(state);
        database.setClassifier(new TreeMap<>(classifier));
        database.setSettings(new HashMap<>());
        database.setDatabaseRegistry(new ArrayList<>());

        DatabaseRegistry registry = new DatabaseRegistry();
        registry.setId(UUID.randomUUID());
        registry.setNamespace((String) classifier.get("namespace"));
        registry.setClassifier(classifier);
        registry.setType(DB_TYPE);
        registry.setDatabase(database);
        database.getDatabaseRegistry().add(registry);
        return registry;
    }

    private EnsuredUser buildEnsuredUser(Map<String, Object> connectionProperties) {
        EnsuredUser user = new EnsuredUser();
        user.setConnectionProperties(connectionProperties);
        return user;
    }

    private Map<String, Object> getConnectionProperties(DatabaseRegistry registry, String role) {
        return registry.getConnectionProperties().stream()
                .filter(cp -> role.equalsIgnoreCase((String) cp.get("role")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No connectionProperties with role=" + role));
    }
}