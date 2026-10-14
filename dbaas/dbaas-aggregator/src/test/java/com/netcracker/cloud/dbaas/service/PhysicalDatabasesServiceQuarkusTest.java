package com.netcracker.cloud.dbaas.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.netcracker.cloud.dbaas.DatabaseType;
import com.netcracker.cloud.dbaas.dto.EnsuredUser;
import com.netcracker.cloud.dbaas.dto.HttpBasicCredentials;
import com.netcracker.cloud.dbaas.dto.role.Role;
import com.netcracker.cloud.dbaas.dto.v3.Metadata;
import com.netcracker.cloud.dbaas.dto.v3.PhysicalDatabaseRegistryRequestV3;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabase;
import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabaseInstruction;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.PhysicalDatabaseInstructionRepository;
import com.netcracker.cloud.dbaas.utils.DatabaseBuilder;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Stream;

import static com.netcracker.cloud.dbaas.Constants.ROLE;
import static com.netcracker.cloud.dbaas.DbaasApiPath.VERSION_2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class PhysicalDatabasesServiceQuarkusTest {
    private static final long MIGRATION_TIMEOUT_MS = 30_000;

    @Inject
    InstructionService instructionService;
    @InjectSpy
    LockService lockService;
    @InjectSpy
    PhysicalDatabasesService physicalDatabasesService;
    @InjectSpy
    AsyncOperations asyncOperations;
    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    DatabaseDbaasRepository databaseDbaasRepository;
    @Inject
    PhysicalDatabaseInstructionRepository physicalDatabaseInstructionRepository;
    @Inject
    PhysicalDatabaseDbaasRepository physicalDatabaseDbaasRepository;
    @InjectMock
    PasswordEncryption encryption;

    @AfterEach
    void cleanUp() {
        databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType().forEach(databaseRegistryDbaasRepository::delete);
        QuarkusTransaction.requiringNew().run(physicalDatabaseInstructionRepository::deleteAll);
    }

    @Test
    void testRegisterPhysicalDatabase_processInternalMigration() throws JsonProcessingException {

        PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        String physicalDatabaseId = physicalDatabase.getPhysicalDatabaseIdentifier();
        physicalDatabase.setRoles(physicalDatabase.getRoles().subList(0, 1));
        physicalDatabaseDbaasRepository.save(physicalDatabase);

        List<Database> logicalDatabases = Stream.generate(() -> new DatabaseBuilder().physicalDatabaseId(physicalDatabaseId).adapterId(physicalDatabase.getAdapter().getAdapterId()).registry().build())
                .limit(3)
                .toList();
        logicalDatabases.forEach(db -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(db.getDatabaseRegistry().getFirst()));

        DbaasAdapter adapter = mock(DbaasAdapter.class);
        doReturn(adapter)
                .when(physicalDatabasesService)
                .getAdapterByPhysDbId(physicalDatabaseId);
        when(adapter.ensureUser(isNull(), isNull(), anyString(), anyString())).thenAnswer(inv -> {
            String role = inv.getArgument(3);
            String username = inv.getArgument(2) + "-" + role;
            return new EnsuredUser(username, new HashMap<>(Map.of(ROLE, role, "username", username)),
                    List.of(new DbResource("user", username)), true);
        });


        PhysicalDatabaseRegistryRequestV3 requestV3 = registrationRequest();
        physicalDatabasesService.registerPhysicalDatabase(requestV3, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, true);

        verify(lockService, times(1)).getKeepAliveLock(any());
        verify(lockService, timeout(MIGRATION_TIMEOUT_MS)).unlock(any());
        PhysicalDatabaseInstruction leftInstruction = physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId);
        assertNull(leftInstruction, () -> "Instruction is not deleted, failure: " + leftInstruction.getFailure());
        List<String> expectedRoles = requestV3.getMetadata().getSupportedRoles().stream().sorted().toList();
        for (Database db : logicalDatabases) {
            List<String> actualRoles = databaseDbaasRepository.findById(db.getId()).orElseThrow()
                    .getConnectionProperties().stream()
                    .map(cp -> (String) cp.get(ROLE))
                    .sorted()
                    .toList();
            assertEquals(expectedRoles, actualRoles, "Roles of logical database " + db.getName());
        }
        // TODO assertFalse(lockService.isLocked(any()), "Migration lock is still held");
        // TODO assert PhysicalDatabase aligned with requested one
        // TODO assert lock.unlock()
    }


    private PhysicalDatabaseRegistryRequestV3 registrationRequest() {
        List<String> requiredRoles = new ArrayList<>(Arrays.asList(Role.ADMIN.toString(), "rw", "ro", "streaming"));

        PhysicalDatabaseRegistryRequestV3 request = new PhysicalDatabaseRegistryRequestV3();
        request.setAdapterAddress("http://test-adapter:8080");
        request.setHttpBasicCredentials(new HttpBasicCredentials("test-username", "test-password"));
        request.setStatus("running");
        request.setMetadata(new Metadata(VERSION_2, requiredRoles, Map.of("multiusers", true)));
        return request;
    }

    private PhysicalDatabase getPhysicalDatabaseSample() {
        String physicalDatabaseId = "phDbId" + UUID.randomUUID();
        return physicalDatabasesService.physicalDatabaseRegistration(physicalDatabaseId, DatabaseType.POSTGRESQL.toString(), registrationRequest());
    }

    // TODO test for assert that extend didn't used during registration
}