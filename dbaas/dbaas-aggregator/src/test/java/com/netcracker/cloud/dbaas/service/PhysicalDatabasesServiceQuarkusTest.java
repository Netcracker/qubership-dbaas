package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.DatabaseType;
import com.netcracker.cloud.dbaas.dto.EnsuredUser;
import com.netcracker.cloud.dbaas.dto.HttpBasicCredentials;
import com.netcracker.cloud.dbaas.dto.InstructionType;
import com.netcracker.cloud.dbaas.dto.role.Role;
import com.netcracker.cloud.dbaas.dto.v3.*;
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
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.netcracker.cloud.dbaas.Constants.ROLE;
import static com.netcracker.cloud.dbaas.DbaasApiPath.VERSION_2;
import static jakarta.ws.rs.core.Response.Status.ACCEPTED;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class PhysicalDatabasesServiceQuarkusTest {
    private static final long MIGRATION_TIMEOUT_MS = 30_000;

    private final String adapterAddress = "http://test-adapter-" + UUID.randomUUID().toString().substring(0, 8) + ".test:8080";

    @InjectSpy
    LockService lockService;
    @InjectSpy
    PhysicalDatabasesService physicalDatabasesService;
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
    void testRegisterPhysicalDatabase_internalMigrationTrue() {
        PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        String physicalDatabaseId = physicalDatabase.getPhysicalDatabaseIdentifier();
        physicalDatabase.setRoles(physicalDatabase.getRoles().subList(0, 1));
        assertNotNull(physicalDatabaseDbaasRepository.save(physicalDatabase), "Physical database save method didn't work");

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
            String password = inv.getArgument(2) + "-" + role + "password";
            return new EnsuredUser(username, new HashMap<>(Map.of(ROLE, role, "username", username, "password", password)),
                    List.of(new DbResource("user", username)), true);
        });

        PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase = registrationRequest();
        physicalDatabasesService.registerPhysicalDatabase(requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, true);

        verify(lockService, times(1)).getKeepAliveLock(any());
        verify(lockService, timeout(MIGRATION_TIMEOUT_MS).times(2)).unlock(any());
        assertFalse(lockService.isLocked(physicalDatabaseId), "Physical database registration process still locked");

        PhysicalDatabaseInstruction leftInstruction = physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId);
        assertNull(leftInstruction, () -> "Instruction is not deleted, failure: " + leftInstruction.getFailure());

        List<String> expectedRoles = requestedPhysicalDatabase.getMetadata().getSupportedRoles().stream().sorted().toList();
        for (Database db : logicalDatabases) {
            List<String> actualRoles = databaseDbaasRepository.findById(db.getId()).orElseThrow()
                    .getConnectionProperties().stream()
                    .map(cp -> (String) cp.get(ROLE))
                    .sorted()
                    .toList();
            assertEquals(expectedRoles, actualRoles, "Roles of logical database " + db.getName());
        }

        PhysicalDatabase actualPhysicalDatabase = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        assertNotNull(actualPhysicalDatabase, "Physical database doesn't exist");
        assertEquals(requestedPhysicalDatabase.getLabels(), actualPhysicalDatabase.getLabels());
        assertEquals(requestedPhysicalDatabase.getMetadata().getFeatures(), actualPhysicalDatabase.getFeatures());
        assertEquals(DatabaseType.POSTGRESQL.toString(), actualPhysicalDatabase.getType());
        assertEquals(expectedRoles, actualPhysicalDatabase.getRoles());

        verify(physicalDatabasesService, times(1)).savePhysicalDatabaseWithRoles(any(), any());
        verify(physicalDatabasesService, times(1)).deleteInstruction(any());
        verify(physicalDatabasesService, times(1)).findInstructionByPhyDbId(any());
        verify(physicalDatabasesService, times(1)).saveInstructionIntoDB(any(), anyString(), eq(requestedPhysicalDatabase));
    }

    @Test
    void testRegisterPhysicalDatabase_adapterDrivenMigrationSuccess() {
        PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        String physicalDatabaseId = physicalDatabase.getPhysicalDatabaseIdentifier();
        physicalDatabase.setRoles(new ArrayList<>(List.of(Role.ADMIN.toString())));
        assertNotNull(physicalDatabaseDbaasRepository.save(physicalDatabase), "Physical database save method didn't work");

        List<Database> logicalDatabases = Stream.generate(() -> new DatabaseBuilder().physicalDatabaseId(physicalDatabaseId)
                        .adapterId(physicalDatabase.getAdapter().getAdapterId())
                        .registry()
                        .build())
                .limit(3)
                .toList();
        logicalDatabases.forEach(db -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(db.getDatabaseRegistry().getFirst()));

        assertNull(physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId),
                "Precondition violated: instruction must not exist before the registration");

        PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase = registrationRequest();
        Response response = physicalDatabasesService.registerPhysicalDatabase(
                requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, false);

        assertEquals(ACCEPTED.getStatusCode(), response.getStatus(),
                "Registration must answer 202 Accepted with the migration instruction");

        Map<String, Object> responseEntity = (Map<String, Object>) response.getEntity();
        Instruction instruction = (Instruction) responseEntity.get("instruction");
        assertNotNull(instruction, "Instruction must be present in the 202 response body");
        assertEquals(logicalDatabases.size(), instruction.getAdditionalRoles().size(),
                "All logical databases are expected in the migration portion");
        Set<UUID> portionIds = instruction.getAdditionalRoles().stream().map(AdditionalRoles::getId).collect(Collectors.toSet());
        Set<UUID> logicalDbsId = logicalDatabases.stream().map(Database::getId).collect(Collectors.toSet());
        assertEquals(logicalDbsId, portionIds,
                "Instruction must reference exactly the logical databases that miss the required roles");
        instruction.getAdditionalRoles().forEach(additionalRoles -> assertEquals(
                List.of(Role.ADMIN.toString()), rolesOf(additionalRoles.getConnectionProperties()),
                "Instruction must carry the current roles of logical database " + additionalRoles.getDbName()));

        PhysicalDatabaseInstruction savedInstruction = physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId);
        assertNotNull(savedInstruction, "Instruction must be saved before the adapter starts the migration");
        assertEquals(instruction.getId(), savedInstruction.getId());
        assertEquals(InstructionType.MULTIUSERS_MIGRATION, savedInstruction.getInstructionType());
        assertTrue(savedInstruction.getFailure().isEmpty(), "No failure is expected during adapter driven migration");
        assertEquals(expectedRoles(requestedPhysicalDatabase),
                savedInstruction.getPhysicalDbRegRequest().getMetadata().getSupportedRoles());

        PhysicalDatabase parkedPhysicalDatabase = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        assertEquals(List.of(Role.ADMIN.toString()), parkedPhysicalDatabase.getRoles(),
                "Physical database must keep the old roles until the migration completes");

        Response migrationResult = physicalDatabasesService.handleInstruction(
                instruction.getId(), adapterPortionReport(instruction, requestedPhysicalDatabase));
        assertEquals(OK.getStatusCode(), migrationResult.getStatus(),
                "Adapter-driven migration must be completed with 200 OK");


        for (Database db : logicalDatabases) {
            Database actualDatabase = databaseDbaasRepository.findById(db.getId()).orElseThrow();
            assertEquals(expectedRoles(requestedPhysicalDatabase), rolesOf(actualDatabase.getConnectionProperties()),
                    "Roles of logical database " + db.getName());
            assertTrue(actualDatabase.getResources().stream()
                            .filter(resource -> "user".equals(resource.getKind()))
                            .map(DbResource::getName)
                            .toList()
                            .containsAll(userNames(db.getName(), requestedPhysicalDatabase)),
                    "Resources of logical database " + db.getName() + " must include users for the new roles");
        }

        PhysicalDatabase actualPhysicalDatabase = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        assertNotNull(actualPhysicalDatabase, "Physical database doesn't exist");
        assertEquals(expectedRoles(requestedPhysicalDatabase), actualPhysicalDatabase.getRoles());
        assertEquals(requestedPhysicalDatabase.getLabels(), actualPhysicalDatabase.getLabels());
        assertEquals(requestedPhysicalDatabase.getMetadata().getFeatures(), actualPhysicalDatabase.getFeatures());
        assertEquals(DatabaseType.POSTGRESQL.toString(), actualPhysicalDatabase.getType());

        assertNull(physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId),
                "Instruction must be deleted after the migration completes");
        assertTrue(physicalDatabaseInstructionRepository.findByIdOptional(instruction.getId()).isEmpty(),
                "Instruction with the reported id must be deleted");

        verify(physicalDatabasesService, times(1)).saveInstructionIntoDB(instruction.getId(), physicalDatabaseId, requestedPhysicalDatabase);
        verify(physicalDatabasesService, times(1)).findInstructionByPhyDbId(physicalDatabaseId);
        verify(physicalDatabasesService, times(1)).savePhysicalDatabaseWithRoles(physicalDatabaseId, requestedPhysicalDatabase);
        verify(physicalDatabasesService, times(1)).deleteInstruction(instruction.getId());
        verify(lockService, never()).getKeepAliveLock(any());
    }

    private InstructionRequestV3 adapterPortionReport(Instruction instruction, PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase) {
        List<String> requiredRoles = expectedRoles(requestedPhysicalDatabase);
        List<SuccessRegistrationV3> successRegistrations = instruction.getAdditionalRoles().stream()
                .map(additionalRoles -> {
                    List<String> missedRoles = requiredRoles.stream()
                            .filter(role -> !rolesOf(additionalRoles.getConnectionProperties()).contains(role))
                            .toList();
                    List<Map<String, Object>> connectionProperties = new ArrayList<>();
                    List<DbResource> resources = new ArrayList<>();
                    for (String role : missedRoles) {
                        String username = additionalRoles.getDbName() + "-" + role;
                        connectionProperties.add(new HashMap<>(Map.of(ROLE, role, "username", username, "password", username + "password")));
                        resources.add(new DbResource("user", username));
                    }
                    return new SuccessRegistrationV3(additionalRoles.getId(), connectionProperties, resources);
                })
                .toList();
        InstructionRequestV3 instructionRequest = new InstructionRequestV3();
        instructionRequest.setSuccess(successRegistrations);
        return instructionRequest;
    }

    private List<String> userNames(String dbName, PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase) {
        return expectedRoles(requestedPhysicalDatabase).stream()
                .filter(role -> !role.equalsIgnoreCase(Role.ADMIN.toString()))
                .map(role -> dbName + "-" + role)
                .toList();
    }

    private List<String> rolesOf(List<Map<String, Object>> connectionProperties) {
        return connectionProperties.stream().map(cp -> (String) cp.get(ROLE)).sorted().toList();
    }

    private List<String> expectedRoles(PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase) {
        return requestedPhysicalDatabase.getMetadata().getSupportedRoles().stream().sorted().toList();
    }

    private PhysicalDatabaseRegistryRequestV3 registrationRequest() {
        List<String> requiredRoles = new ArrayList<>(Arrays.asList(Role.ADMIN.toString(), "readWrite", "ro", "streaming"));

        PhysicalDatabaseRegistryRequestV3 request = new PhysicalDatabaseRegistryRequestV3();
        request.setAdapterAddress(adapterAddress);
        request.setHttpBasicCredentials(new HttpBasicCredentials("test-username", "test-password"));
        request.setStatus("running");
        request.setMetadata(new Metadata(VERSION_2, requiredRoles, Map.of("multiusers", true)));
        return request;
    }

    private PhysicalDatabase getPhysicalDatabaseSample() {
        String physicalDatabaseId = "phDbId-" + Math.random();
        return physicalDatabasesService.physicalDatabaseRegistration(physicalDatabaseId, DatabaseType.POSTGRESQL.toString(), registrationRequest());
    }
}