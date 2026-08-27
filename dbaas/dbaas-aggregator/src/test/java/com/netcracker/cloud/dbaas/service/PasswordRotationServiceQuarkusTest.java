package com.netcracker.cloud.dbaas.service;


import com.netcracker.cloud.dbaas.dto.EnsuredUser;
import com.netcracker.cloud.dbaas.dto.role.Role;
import com.netcracker.cloud.dbaas.dto.v3.PasswordChangeRequestV3;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.exceptions.PasswordChangeFailedException;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.utils.DatabaseBuilder;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static com.netcracker.cloud.dbaas.utils.DatabaseBuilder.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class PasswordRotationServiceQuarkusTest {

    private static final String ADAPTER_ID_1 = "adapter-1";
    private static final String ADAPTER_ID_2 = "adapter-2";

    @Inject
    PasswordRotationService passwordRotationService;

    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @InjectMock
    PhysicalDatabasesService physicalDatabasesService;

    @AfterEach
    void cleanUp() {
        databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType()
                .stream()
                .filter(dbr -> TEST_NS.equals(dbr.getNamespace()))
                .forEach(databaseRegistryDbaasRepository::delete);
    }

    /**
     * Verifies that when a batch of database-password rotations is processed and adapter-2 throws,
     * the already-committed rotation for database-1 survives.
     */
    @Test
    void partialBatchFailure_successfulRotationPersistedIndependently() {
        // --- arrange databases -------------------------------------------------
        String oldUsername1 = "old-user-1";
        String oldUsername2 = "old-user-2";
        String newUsername1 = "new-user-1";

        DatabaseRegistry registry1 = new DatabaseBuilder()
                .adapterId(ADAPTER_ID_1)
                .connectionProperties(List.of(new HashMap<>(Map.of("username", oldUsername1, "role", Role.ADMIN.toString()))))
                .registry()
                .build().getDatabaseRegistry().getFirst();
        DatabaseRegistry registry2 = new DatabaseBuilder()
                .adapterId(ADAPTER_ID_2)
                .connectionProperties(List.of(new HashMap<>(Map.of("username", oldUsername2, "role", Role.ADMIN.toString()))))
                .registry()
                .build().getDatabaseRegistry().getFirst();

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
        request.setType(PG_TYPE);
        // null classifier → namespace-wide batch

        PasswordChangeFailedException thrown = assertThrows(
                PasswordChangeFailedException.class,
                () -> passwordRotationService.changeUserPassword(request, TEST_NS)
        );

        // --- assert response ---------------------------------------------------
        assertEquals(1, thrown.getResponse().getChanged().size());
        assertEquals(1, thrown.getResponse().getFailed().size());
        assertEquals(500, thrown.getStatus());

        // --- assert transaction isolation: db1 persisted, db2 unchanged --------
        Optional<DatabaseRegistry> persistedReg1 =
                databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(registry1.getClassifier(), PG_TYPE);
        assertTrue(persistedReg1.isPresent());
        Map<String, Object> cp1 = getConnectionProperties(persistedReg1.get(), Role.ADMIN.toString());
        assertEquals(newUsername1, cp1.get("username"),
                "db1 must have the new username committed by its independent transaction");
        assertNotNull(persistedReg1.get().getDatabase().getLastRotatedAt(),
                "db1 must have lastRotatedAt stamped by its independent commitRotation transaction");

        Optional<DatabaseRegistry> persistedReg2 =
                databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(registry2.getClassifier(), PG_TYPE);
        assertTrue(persistedReg2.isPresent());
        Map<String, Object> cp2 = getConnectionProperties(persistedReg2.get(), Role.ADMIN.toString());
        assertEquals(oldUsername2, cp2.get("username"),
                "db2 must still have the old username — its adapter call threw before commitRotation");
    }

    @Test
    void multiRoleDatabase_marksRotatedOncePerDatabase() {
        DatabaseRegistry registry = new DatabaseBuilder()
                .connectionProperties(new ArrayList<>(List.of(
                        new HashMap<>(Map.of("username", "user-admin", "role", Role.ADMIN.toString())),
                        new HashMap<>(Map.of("username", "user-rw", "role", "rw"))
                )))
                .registry()
                .build().getDatabaseRegistry().getFirst();
        databaseRegistryDbaasRepository.saveInternalDatabase(registry);

        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        Mockito.when(adapter.identifier()).thenReturn(POSTGRES_ADAPTER_ID);
        Mockito.when(adapter.isUsersSupported()).thenReturn(true);
        doReturn(buildEnsuredUser(Map.of("username", "user-admin", "role", Role.ADMIN.toString())))
                .when(adapter).ensureUser(eq("user-admin"), any(), any(), eq(Role.ADMIN.toString()));
        doReturn(buildEnsuredUser(Map.of("username", "user-rw", "role", "rw")))
                .when(adapter).ensureUser(eq("user-rw"), any(), any(), eq("rw"));
        Mockito.when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));

        passwordRotationService.performChangePassword(List.of(registry), null);

        DatabaseRegistry persisted = databaseRegistryDbaasRepository
                .getDatabaseByClassifierAndType(registry.getClassifier(), PG_TYPE).orElseThrow();
        assertNotNull(persisted.getDatabase().getLastRotatedAt(),
                "rotation must stamp last_rotated_at on the database");
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
