package com.netcracker.cloud.dbaas.service;

import com.github.tomakehurst.wiremock.client.CountMatchingStrategy;
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
import com.netcracker.cloud.dbaas.integration.config.WireMockResource;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.PhysicalDatabaseInstructionRepository;
import com.netcracker.cloud.dbaas.utils.DatabaseBuilder;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import net.javacrumbs.shedlock.core.LockConfiguration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.netcracker.cloud.dbaas.Constants.ROLE;
import static com.netcracker.cloud.dbaas.DbaasApiPath.VERSION_2;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;

@QuarkusTest
@QuarkusTestResource(WireMockResource.class)
@QuarkusTestResource(PostgresqlContainerResource.class)
class PhysicalDatabasesServiceQuarkusTest {
    private static final long MIGRATION_TIMEOUT_MS = 30_000;
    private static final String COMPLETION_FAILURE_MESSAGE = "Instruction deletion failed";
    private static final String ENSURE_USER_PATH =
            "/api/v2/dbaas/adapter/" + DatabaseType.POSTGRESQL + "/users";

    private final String adapterAddress = "http://test-adapter-" + UUID.randomUUID().toString().substring(0, 8) + ".test:8080";

    @ConfigProperty(name = "wiremock.url")
    String wiremockAddress;

    @InjectSpy
    LockService lockService;
    @InjectSpy
    PhysicalDatabasesService physicalDatabasesService;
    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    DatabaseDbaasRepository databaseDbaasRepository;
    @InjectSpy
    PhysicalDatabaseInstructionRepository physicalDatabaseInstructionRepository;
    @Inject
    PhysicalDatabaseDbaasRepository physicalDatabaseDbaasRepository;
    @InjectMock
    PasswordEncryption encryption;

    @AfterEach
    void cleanUp() {
        databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType().forEach(databaseRegistryDbaasRepository::delete);
        physicalDatabaseDbaasRepository.findAll().forEach(physicalDatabaseDbaasRepository::delete);
        physicalDatabaseInstructionRepository.deleteAll();
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
        when(adapter.identifier()).thenReturn(physicalDatabase.getAdapter().getAdapterId());
        when(adapter.type()).thenReturn(DatabaseType.POSTGRESQL.toString());
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
        await("migration lock released after the internal migration", MIGRATION_TIMEOUT_MS,
                () -> !lockService.isLocked(migrationLockName()));
        assertFalse(lockService.isLocked(migrationLockName()),
                "Physical database registration process still locked");

        PhysicalDatabaseInstruction leftInstruction = physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId);
        assertNull(leftInstruction, () -> "Instruction is not deleted, failure: " + leftInstruction.getFailures());

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

        verify(physicalDatabaseInstructionRepository, times(1)).persist(any(PhysicalDatabaseInstruction.class));
    }

    @Test
    void testRegisterPhysicalDatabase_internalMigrationMultiplePortions() {
        PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        String physicalDatabaseId = physicalDatabase.getPhysicalDatabaseIdentifier();
        String adapterId = physicalDatabase.getAdapter().getAdapterId();
        physicalDatabase.setRoles(physicalDatabase.getRoles().subList(0, 1));
        assertNotNull(physicalDatabaseDbaasRepository.save(physicalDatabase), "Physical database save method didn't work");

        int databaseCount = 50 + 3; // must exceed the migration portion size to cover the multi-portion processing
        List<Database> logicalDatabases = Stream.generate(() -> new DatabaseBuilder().physicalDatabaseId(physicalDatabaseId)
                        .adapterId(adapterId)
                        .registry()
                        .build())
                .limit(databaseCount)
                .toList();
        logicalDatabases.forEach(db -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(db.getDatabaseRegistry().getFirst()));

        DbaasAdapter adapter = mock(DbaasAdapter.class);
        doReturn(adapter)
                .when(physicalDatabasesService)
                .getAdapterByPhysDbId(physicalDatabaseId);
        when(adapter.identifier()).thenReturn(adapterId);
        when(adapter.type()).thenReturn(DatabaseType.POSTGRESQL.toString());
        when(adapter.ensureUser(isNull(), isNull(), anyString(), anyString())).thenAnswer(inv -> {
            String role = inv.getArgument(3);
            String username = inv.getArgument(2) + "-" + role;
            String password = inv.getArgument(2) + "-" + role + "password";
            return new EnsuredUser(username, new HashMap<>(Map.of(ROLE, role, "username", username, "password", password)),
                    List.of(new DbResource("user", username)), true);
        });

        PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase = registrationRequest();
        Response response = physicalDatabasesService.registerPhysicalDatabase(
                requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, true);
        assertEquals(ACCEPTED.getStatusCode(), response.getStatus(),
                "Registration with internal migration must answer 202 Accepted");

        verify(lockService, times(1)).getKeepAliveLock(any());
        await("migration lock released after the multi-portion internal migration", MIGRATION_TIMEOUT_MS,
                () -> !lockService.isLocked(migrationLockName()));

        PhysicalDatabaseInstruction leftInstruction = physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId);
        assertNull(leftInstruction, () -> "Instruction is not deleted, failure: " + leftInstruction.getFailures());

        List<String> expectedRoles = expectedRoles(requestedPhysicalDatabase);
        for (Database db : logicalDatabases) {
            Database actualDatabase = databaseDbaasRepository.findById(db.getId()).orElseThrow();
            assertEquals(expectedRoles, rolesOf(actualDatabase.getConnectionProperties()),
                    "Roles of logical database " + db.getName());
        }


        PhysicalDatabase actualPhysicalDatabase = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        assertNotNull(actualPhysicalDatabase, "Physical database doesn't exist");
        assertEquals(expectedRoles, actualPhysicalDatabase.getRoles());

        verify(physicalDatabaseInstructionRepository, times(1)).persist(any(PhysicalDatabaseInstruction.class));
    }

    @Test
    void testRegisterPhysicalDatabase_markedForDropDatabasesSkippedByMigration() {
        PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        String physicalDatabaseId = physicalDatabase.getPhysicalDatabaseIdentifier();
        String adapterId = physicalDatabase.getAdapter().getAdapterId();
        physicalDatabase.setRoles(physicalDatabase.getRoles().subList(0, 1));
        assertNotNull(physicalDatabaseDbaasRepository.save(physicalDatabase), "Physical database save method didn't work");

        PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase = registrationRequest();
        // Role names are stored in lowercase, so the aligned database is built with the normalized names.
        List<String> requiredRoles = requestedPhysicalDatabase.getMetadata().getSupportedRoles().stream()
                .map(String::toLowerCase)
                .toList();

        Database alignedDb = new DatabaseBuilder()
                .physicalDatabaseId(physicalDatabaseId)
                .adapterId(adapterId)
                .registry()
                .connectionProperties(requiredRoles.stream()
                        .<Map<String, Object>>map(role -> new HashMap<>(Map.of(ROLE, role, "username", "user-" + role, "password", "password")))
                        .toList())
                .build();
        Database markedDb = new DatabaseBuilder()
                .physicalDatabaseId(physicalDatabaseId)
                .adapterId(adapterId)
                .registry()
                .build();
        markedDb.setMarkedForDrop(true);
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(alignedDb.getDatabaseRegistry().getFirst());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(markedDb.getDatabaseRegistry().getFirst());

        Response response = physicalDatabasesService.registerPhysicalDatabase(
                requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, true);
        assertEquals(OK.getStatusCode(), response.getStatus(),
                "Registration must answer 200 OK when only marked-for-drop databases are missing the roles");
        verify(lockService, never()).getKeepAliveLock(any());
        assertNull(physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId),
                "No instruction must be saved when the migration is not started");

        Database actualMarkedDb = databaseDbaasRepository.findById(markedDb.getId()).orElseThrow();
        assertEquals(List.of(Role.ADMIN.toString()), rolesOf(actualMarkedDb.getConnectionProperties()),
                "The marked-for-drop database must stay unchanged");
        assertTrue(actualMarkedDb.isMarkedForDrop(), "The marked-for-drop flag must stay set");

        PhysicalDatabase actualPhysicalDatabase = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        assertEquals(expectedRoles(requestedPhysicalDatabase), actualPhysicalDatabase.getRoles(),
                "The physical database must pick up the requested roles without a migration");

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
        assertTrue(savedInstruction.getFailures().isEmpty(), "No failure is expected during adapter driven migration");
        assertEquals(expectedRoles(requestedPhysicalDatabase),
                savedInstruction.getPhysicalDbRegRequest().getMetadata().getSupportedRoles());

        PhysicalDatabase parkedPhysicalDatabase = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        assertEquals(List.of(Role.ADMIN.toString()), parkedPhysicalDatabase.getRoles(),
                "Physical database must keep the old roles until the migration completes");

        Response migrationResult = physicalDatabasesService.handleInstruction(
                instruction.getId(), adapterPortionReport(instruction.getAdditionalRoles(), requestedPhysicalDatabase));
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

        verify(physicalDatabaseInstructionRepository, times(1)).persist(any(PhysicalDatabaseInstruction.class));
        verify(physicalDatabaseInstructionRepository, times(1)).deleteById(instruction.getId());
        verify(lockService, never()).getKeepAliveLock(any());
    }

    @Test
    void testRegisterPhysicalDatabase_adapterDrivenMigrationFailureRecoversAfterReTrigger() {
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
        Database succeededDb = logicalDatabases.getFirst();
        Database failedDb = logicalDatabases.get(1);
        Database untouchedDb = logicalDatabases.getLast();

        assertNull(physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId),
                "Precondition violated: instruction must not exist before the registration");

        PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase = registrationRequest();

        Response response = physicalDatabasesService.registerPhysicalDatabase(
                requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, false);
        assertEquals(ACCEPTED.getStatusCode(), response.getStatus(),
                "Registration must answer 202 Accepted with the migration instruction");

        Instruction instruction = instructionOf(response);
        assertEquals(logicalDatabases.size(), instruction.getAdditionalRoles().size(),
                "All logical databases are expected in the migration portion");
        Set<UUID> portionIds = instruction.getAdditionalRoles().stream().map(AdditionalRoles::getId).collect(Collectors.toSet());
        Set<UUID> logicalDbsIds = logicalDatabases.stream().map(Database::getId).collect(Collectors.toSet());
        assertEquals(logicalDbsIds, portionIds,
                "Instruction must reference exactly the logical databases that miss the required roles");

        PhysicalDatabaseInstruction savedInstruction = physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId);
        assertNotNull(savedInstruction, "Instruction must be saved before the adapter starts the migration");
        assertEquals(instruction.getId(), savedInstruction.getId());
        assertEquals(InstructionType.MULTIUSERS_MIGRATION, savedInstruction.getInstructionType());
        assertTrue(savedInstruction.getFailures().isEmpty(), "No failure is expected before the adapter reports one");

        String adapterErrorMessage = "Adapter failed during user creation";
        InstructionRequestV3 failedPortionReport =
                adapterPortionReport(List.of(findPortion(instruction, succeededDb)), requestedPhysicalDatabase);
        failedPortionReport.setFailure(new FailureRegistrationV3(failedDb.getId().toString(), adapterErrorMessage));

        Response migrationResult = physicalDatabasesService.handleInstruction(instruction.getId(), failedPortionReport);
        assertEquals(INTERNAL_SERVER_ERROR.getStatusCode(), migrationResult.getStatus(),
                "A failed portion must abort the migration with 500 Internal Server Error");
        assertTrue(migrationResult.getEntity().toString().contains(adapterErrorMessage),
                "The 500 response must carry the adapter failure message");

        assertDatabaseAligned(succeededDb, requestedPhysicalDatabase);
        assertDatabaseNotAligned(failedDb);
        assertDatabaseNotAligned(untouchedDb);
        assertNull(physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId),
                "The failed portion must delete the instruction");
        assertTrue(physicalDatabaseInstructionRepository.findByIdOptional(instruction.getId()).isEmpty(),
                "The instruction with the reported id must be deleted after the failure");
        PhysicalDatabase parkedPhysicalDatabase = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        assertEquals(List.of(Role.ADMIN.toString()), parkedPhysicalDatabase.getRoles(),
                "The physical database must keep the old roles until the migration completes");
        assertEquals(adapterAddress, parkedPhysicalDatabase.getAdapter().getAddress(),
                "The physical database adapter address must stay unchanged after the failure");

        Response reTriggerResponse = physicalDatabasesService.registerPhysicalDatabase(
                requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, false);
        assertEquals(ACCEPTED.getStatusCode(), reTriggerResponse.getStatus(),
                "The re-trigger must answer 202 Accepted with a new migration instruction");
        Instruction reTriggeredInstruction = instructionOf(reTriggerResponse);
        assertNotEquals(instruction.getId(), reTriggeredInstruction.getId(),
                "The re-trigger must create a new instruction, not reuse the deleted one");
        Set<UUID> reTriggeredPortionIds = reTriggeredInstruction.getAdditionalRoles().stream()
                .map(AdditionalRoles::getId).collect(Collectors.toSet());
        assertEquals(Set.of(failedDb.getId(), untouchedDb.getId()), reTriggeredPortionIds,
                "The new instruction must reference exactly the logical databases that are still not aligned");

        PhysicalDatabaseInstruction reTriggeredSavedInstruction =
                physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId);
        assertNotNull(reTriggeredSavedInstruction, "The re-trigger must save the new instruction");
        assertEquals(reTriggeredInstruction.getId(), reTriggeredSavedInstruction.getId());

        Response migrationCompletion = physicalDatabasesService.handleInstruction(
                reTriggeredInstruction.getId(),
                adapterPortionReport(reTriggeredInstruction.getAdditionalRoles(), requestedPhysicalDatabase));
        assertEquals(OK.getStatusCode(), migrationCompletion.getStatus(),
                "The completed migration must be answered with 200 OK");

        logicalDatabases.forEach(db -> assertDatabaseAligned(db, requestedPhysicalDatabase));
        PhysicalDatabase actualPhysicalDatabase = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        assertNotNull(actualPhysicalDatabase, "Physical database doesn't exist");
        assertEquals(expectedRoles(requestedPhysicalDatabase), actualPhysicalDatabase.getRoles());
        assertEquals(requestedPhysicalDatabase.getLabels(), actualPhysicalDatabase.getLabels());
        assertEquals(requestedPhysicalDatabase.getMetadata().getFeatures(), actualPhysicalDatabase.getFeatures());
        assertEquals(DatabaseType.POSTGRESQL.toString(), actualPhysicalDatabase.getType());

        assertNull(physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId),
                "Instruction must be deleted after the migration completes");
        assertTrue(physicalDatabaseInstructionRepository.findByIdOptional(reTriggeredInstruction.getId()).isEmpty(),
                "The re-triggered instruction must be deleted after the migration completes");

        verify(physicalDatabaseInstructionRepository, times(2)).persist(any(PhysicalDatabaseInstruction.class));
        verify(physicalDatabaseInstructionRepository, times(2)).deleteById(any(UUID.class));
        verify(lockService, never()).getKeepAliveLock(any());
    }

    @Test
    void testRegisterPhysicalDatabase_writePhysicalDatabaseChanges() {
        PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        String physicalDatabaseId = physicalDatabase.getPhysicalDatabaseIdentifier();
        assertNotNull(physicalDatabaseDbaasRepository.save(physicalDatabase), "Physical database save method didn't work");

        PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase = registrationRequest();
        requestedPhysicalDatabase.getMetadata().getFeatures().putIfAbsent("TEST_DATA", true);
        physicalDatabasesService.registerPhysicalDatabase(requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, true);


        PhysicalDatabase actualPhysicalDatabase = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        assertNotNull(actualPhysicalDatabase, "Physical database doesn't exist");
        assertEquals(requestedPhysicalDatabase.getLabels(), actualPhysicalDatabase.getLabels());
        assertEquals(requestedPhysicalDatabase.getMetadata().getFeatures(), actualPhysicalDatabase.getFeatures());
        assertEquals(expectedRoles(requestedPhysicalDatabase), actualPhysicalDatabase.getRoles());

        verify(lockService, never()).getKeepAliveLock(any());
        assertNull(physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId),
                "No instruction must be saved when the migration is not required");
    }

    @Test
    void testRegisterPhysicalDatabase_migrationFailureSucceedsAfterReTrigger() {
        String physicalDatabaseId = "phDbId-" + Math.random();
        PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase = registrationRequest();
        requestedPhysicalDatabase.setAdapterAddress(wiremockAddress);
        Response registrationResponse = physicalDatabasesService.registerPhysicalDatabase(
                requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, true);
        assertEquals(CREATED.getStatusCode(), registrationResponse.getStatus(),
                "A brand new physical database must be registered");
        PhysicalDatabase physicalDatabase = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        physicalDatabase.setRoles(new ArrayList<>(List.of(Role.ADMIN.toString())));
        assertNotNull(physicalDatabaseDbaasRepository.save(physicalDatabase), "Physical database save method didn't work");

        List<Database> logicalDatabases = Stream.generate(() -> new DatabaseBuilder().physicalDatabaseId(physicalDatabaseId)
                        .adapterId(physicalDatabase.getAdapter().getAdapterId())
                        .registry()
                        .build())
                .limit(3)
                .toList();
        logicalDatabases.forEach(db -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(db.getDatabaseRegistry().getFirst()));
        Database secondDb = logicalDatabases.get(1);

        assertNull(physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId),
                "Precondition violated: instruction must not exist before the registration");

        WireMockResource.getServer().resetAll();
        stubSuccessfulEnsureUsers();
        stubFailedEnsureUsers(secondDb.getName());
        when(encryption.decrypt(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        Response response = physicalDatabasesService.registerPhysicalDatabase(
                requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, true);
        assertEquals(ACCEPTED.getStatusCode(), response.getStatus(),
                "Registration with internal migration must answer 202 Accepted");

        await("migration failure recorded for the second database", MIGRATION_TIMEOUT_MS, () -> {
            PhysicalDatabaseInstruction instruction = physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId);
            return instruction != null && instruction.getFailures().stream()
                    .anyMatch(failure -> secondDb.getId().toString().equals(failure.getId()));
        });
        await("migration lock released after the failed pass", MIGRATION_TIMEOUT_MS,
                () -> !lockService.isLocked(migrationLockName()));

        PhysicalDatabaseInstruction failedInstruction =
                physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId);
        assertEquals(1, failedInstruction.getFailures().size(),
                "Exactly one failure is expected after the first migration pass");
        assertEquals(secondDb.getId().toString(), failedInstruction.getFailures().getFirst().getId(),
                "The failure must reference the second logical database");
        assertTrue(failedInstruction.getFailures().getFirst().getMessage().contains("Adapter failed to ensure user"),
                "The failure message must carry the adapter error");

        PhysicalDatabase parkedPhysicalDatabase = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        assertEquals(List.of(Role.ADMIN.toString()), parkedPhysicalDatabase.getRoles(),
                "Physical database must keep the old roles while the migration is failed");

        Response failedReTrigger = physicalDatabasesService.registerPhysicalDatabase(
                requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, true);
        assertEquals(INTERNAL_SERVER_ERROR.getStatusCode(), failedReTrigger.getStatus(),
                "Re-triggering the registration with a failed instruction must return a bad response");
        List<FailureRegistrationV3> failures = (List<FailureRegistrationV3>) failedReTrigger.getEntity();
        assertEquals(secondDb.getId().toString(), failures.getFirst().getId(),
                "The bad response must carry the recorded failure of the second logical database");

        await("migration lock released after the failed re-trigger", MIGRATION_TIMEOUT_MS,
                () -> !lockService.isLocked(migrationLockName()));
        PhysicalDatabaseInstruction reTriggeredInstruction =
                physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId);
        assertNotNull(reTriggeredInstruction, "The instruction must survive the failed re-trigger");
        assertEquals(1, reTriggeredInstruction.getFailures().size(),
                "The failed re-trigger must keep exactly the failure of the second logical database");
        assertEquals(secondDb.getId().toString(), reTriggeredInstruction.getFailures().getFirst().getId(),
                "The re-triggered failure must reference the second logical database");
        assertTrue(reTriggeredInstruction.getFailures().getFirst().getMessage().contains("Adapter failed to ensure user"),
                "The re-triggered failure must carry the adapter error");
        WireMockResource.getServer().verify(new CountMatchingStrategy(CountMatchingStrategy.GREATER_THAN, 3),
                putRequestedFor(urlPathEqualTo(ENSURE_USER_PATH))
                        .withRequestBody(matchingJsonPath("$.dbName", equalTo(secondDb.getName()))));

        WireMockResource.getServer().resetAll();
        stubSuccessfulEnsureUsers();

        Response reTrigger = physicalDatabasesService.registerPhysicalDatabase(
                requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, true);
        assertEquals(INTERNAL_SERVER_ERROR.getStatusCode(), reTrigger.getStatus(),
                "The re-trigger is still reported as failed: the instruction carries the failure from the previous pass");

        List<FailureRegistrationV3> reTriggeredFailures = (List<FailureRegistrationV3>) reTrigger.getEntity();
        assertEquals(1, reTriggeredFailures.size());
        assertEquals(secondDb.getId().toString(), reTriggeredFailures.getFirst().getId(),
                "The bad response must carry the recorded failure of the second logical database");

        await("instruction deleted after the successful re-triggered migration", MIGRATION_TIMEOUT_MS,
                () -> physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId) == null);
        await("migration lock released after the successful re-triggered migration", MIGRATION_TIMEOUT_MS,
                () -> !lockService.isLocked(migrationLockName()));

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

        WireMockResource.getServer().verify(3, putRequestedFor(urlPathEqualTo(ENSURE_USER_PATH))
                .withRequestBody(matchingJsonPath("$.dbName", equalTo(secondDb.getName()))));

        PhysicalDatabase actualPhysicalDatabase = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        assertNotNull(actualPhysicalDatabase, "Physical database doesn't exist");
        assertEquals(expectedRoles(requestedPhysicalDatabase), actualPhysicalDatabase.getRoles());
        assertEquals(requestedPhysicalDatabase.getLabels(), actualPhysicalDatabase.getLabels());
        assertEquals(requestedPhysicalDatabase.getMetadata().getFeatures(), actualPhysicalDatabase.getFeatures());
        assertEquals(DatabaseType.POSTGRESQL.toString(), actualPhysicalDatabase.getType());

        assertNull(physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId),
                "Instruction must be deleted after the migration completes");

        Response okReTrigger = physicalDatabasesService.registerPhysicalDatabase(
                requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, true);
        assertEquals(OK.getStatusCode(), okReTrigger.getStatus(),
                "After the migration completes the re-trigger must return 200 OK");

        verify(physicalDatabaseInstructionRepository, times(1)).persist(any(PhysicalDatabaseInstruction.class));
        verify(physicalDatabaseInstructionRepository, times(1)).deleteById(any(UUID.class));
    }

    @Test
    void testRegisterPhysicalDatabase_completeMigrationRollbackKeepsPreviousState() {
        PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        String physicalDatabaseId = physicalDatabase.getPhysicalDatabaseIdentifier();
        String adapterId = physicalDatabase.getAdapter().getAdapterId();
        physicalDatabase.setRoles(new ArrayList<>(List.of(Role.ADMIN.toString())));
        assertNotNull(physicalDatabaseDbaasRepository.save(physicalDatabase), "Physical database save method didn't work");

        Database logicalDatabase = new DatabaseBuilder()
                .physicalDatabaseId(physicalDatabaseId)
                .adapterId(adapterId)
                .registry()
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(logicalDatabase.getDatabaseRegistry().getFirst());

        DbaasAdapter adapter = mock(DbaasAdapter.class);
        doReturn(adapter)
                .when(physicalDatabasesService)
                .getAdapterByPhysDbId(physicalDatabaseId);
        when(adapter.identifier()).thenReturn(adapterId);
        when(adapter.type()).thenReturn(DatabaseType.POSTGRESQL.toString());
        when(adapter.ensureUser(isNull(), isNull(), anyString(), anyString())).thenAnswer(inv -> {
            String role = inv.getArgument(3);
            String username = inv.getArgument(2) + "-" + role;
            String password = inv.getArgument(2) + "-" + role + "password";
            return new EnsuredUser(username, new HashMap<>(Map.of(ROLE, role, "username", username, "password", password)),
                    List.of(new DbResource("user", username)), true);
        });

        PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase = registrationRequest();
        requestedPhysicalDatabase.getMetadata().getFeatures().putIfAbsent("TEST_DATA", true);
        doThrow(new RuntimeException(COMPLETION_FAILURE_MESSAGE))
                .when(physicalDatabaseInstructionRepository)
                .deleteById(any(UUID.class));

        Response response = physicalDatabasesService.registerPhysicalDatabase(
                requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, true);
        assertEquals(ACCEPTED.getStatusCode(), response.getStatus(),
                "Registration with internal migration must answer 202 Accepted");

        await("completion failure recorded on the instruction", MIGRATION_TIMEOUT_MS,
                () -> {
                    PhysicalDatabaseInstruction instruction =
                            physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId);
                    return instruction != null && instruction.getFailures().stream()
                            .anyMatch(failure -> failure.getMessage().contains(COMPLETION_FAILURE_MESSAGE));
                });
        await("migration lock released after the failed completion", MIGRATION_TIMEOUT_MS,
                () -> !lockService.isLocked(migrationLockName()));

        PhysicalDatabase parkedPhysicalDatabase =
                physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        assertNotNull(parkedPhysicalDatabase, "Physical database doesn't exist");
        assertEquals(List.of(Role.ADMIN.toString()), parkedPhysicalDatabase.getRoles(),
                "The physical database must keep the old roles when the completion transaction rolls back");
        assertFalse(parkedPhysicalDatabase.getFeatures().containsKey("TEST_DATA"),
                "The physical database must keep the old features when the completion transaction rolls back");

        PhysicalDatabaseInstruction failedInstruction =
                physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId);
        assertNotNull(failedInstruction, "The instruction must survive the failed completion");
        assertEquals(1, failedInstruction.getFailures().size(),
                "Exactly one completion failure is expected");
        assertTrue(failedInstruction.getFailures().getFirst().getMessage().contains(COMPLETION_FAILURE_MESSAGE),
                "The recorded failure must carry the completion error");

        Database actualDatabase = databaseDbaasRepository.findById(logicalDatabase.getId()).orElseThrow();
        assertEquals(expectedRoles(requestedPhysicalDatabase), rolesOf(actualDatabase.getConnectionProperties()),
                "The migrated logical database must keep its roles when the completion transaction rolls back");

        verify(physicalDatabaseInstructionRepository, times(1)).deleteById(any(UUID.class));
    }

    @Test
    void testHandleInstruction_completeMigrationFailureKeepsPreviousState() {
        PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        String physicalDatabaseId = physicalDatabase.getPhysicalDatabaseIdentifier();
        String adapterId = physicalDatabase.getAdapter().getAdapterId();
        physicalDatabase.setRoles(new ArrayList<>(List.of(Role.ADMIN.toString())));
        assertNotNull(physicalDatabaseDbaasRepository.save(physicalDatabase), "Physical database save method didn't work");

        PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase = registrationRequest();
        requestedPhysicalDatabase.getMetadata().getFeatures().putIfAbsent("TEST_DATA", true);
        // registerPhysicalDatabase normalizes the requested roles to lowercase before the instruction is saved,
        // and the persistence layer lowercases the roles stored in the connection properties.
        requestedPhysicalDatabase.getMetadata().getSupportedRoles().replaceAll(String::toLowerCase);
        // The logical database is already aligned with the requested roles, so the handler goes straight to the completion.
        Database alignedDatabase = new DatabaseBuilder()
                .physicalDatabaseId(physicalDatabaseId)
                .adapterId(adapterId)
                .registry()
                .connectionProperties(requestedPhysicalDatabase.getMetadata().getSupportedRoles().stream()
                        .<Map<String, Object>>map(role -> new HashMap<>(Map.of(ROLE, role, "username", "user-" + role, "password", "password")))
                        .toList())
                .build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(alignedDatabase.getDatabaseRegistry().getFirst());

        PhysicalDatabaseInstruction instruction =
                saveInstruction(physicalDatabaseId, requestedPhysicalDatabase);

        doThrow(new RuntimeException(COMPLETION_FAILURE_MESSAGE))
                .when(physicalDatabaseInstructionRepository)
                .deleteById(any(UUID.class));

        Exception exception = assertThrows(Exception.class,
                () -> physicalDatabasesService.handleInstruction(instruction.getId(), new InstructionRequestV3()));
        assertTrue(exception.toString().contains(COMPLETION_FAILURE_MESSAGE),
                "The completion error must be propagated to the caller");

        PhysicalDatabase parkedPhysicalDatabase =
                physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        assertNotNull(parkedPhysicalDatabase, "Physical database doesn't exist");
        assertEquals(List.of(Role.ADMIN.toString()), parkedPhysicalDatabase.getRoles(),
                "The physical database must keep the old roles when the completion transaction rolls back");
        assertFalse(parkedPhysicalDatabase.getFeatures().containsKey("TEST_DATA"),
                "The physical database must keep the old features when the completion transaction rolls back");

        PhysicalDatabaseInstruction survivingInstruction =
                physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId);
        assertNotNull(survivingInstruction, "The instruction must survive the failed completion");
        assertTrue(survivingInstruction.getFailures().isEmpty(),
                "The adapter-driven path reports the failure via the response, the instruction must stay clean");

        verify(physicalDatabaseInstructionRepository, times(1)).deleteById(any(UUID.class));
    }

    @Test
    void testRegisterPhysicalDatabase_failedPortionResetsFailuresOfMigratedDatabasesOnly() {
        String physicalDatabaseId = "phDbId-" + Math.random();
        PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase = registrationRequest();
        requestedPhysicalDatabase.setAdapterAddress(wiremockAddress);
        Response registrationResponse = physicalDatabasesService.registerPhysicalDatabase(
                requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, true);
        assertEquals(CREATED.getStatusCode(), registrationResponse.getStatus(),
                "A brand new physical database must be registered");
        PhysicalDatabase physicalDatabase = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        String adapterId = physicalDatabase.getAdapter().getAdapterId();
        physicalDatabase.setRoles(new ArrayList<>(List.of(Role.ADMIN.toString())));
        assertNotNull(physicalDatabaseDbaasRepository.save(physicalDatabase), "Physical database save method didn't work");

        // One database is already aligned, the other two still miss the required roles.
        List<String> requiredRoles = requestedPhysicalDatabase.getMetadata().getSupportedRoles();
        Database alignedDb = new DatabaseBuilder()
                .physicalDatabaseId(physicalDatabaseId)
                .adapterId(adapterId)
                .registry()
                .connectionProperties(requiredRoles.stream()
                        .<Map<String, Object>>map(role -> new HashMap<>(Map.of(ROLE, role, "username", "user-" + role, "password", "password")))
                        .toList())
                .build();
        Database firstDb = new DatabaseBuilder().physicalDatabaseId(physicalDatabaseId).adapterId(adapterId).registry().build();
        Database failingDb = new DatabaseBuilder().physicalDatabaseId(physicalDatabaseId).adapterId(adapterId).registry().build();
        List<Database> logicalDatabases = List.of(alignedDb, firstDb, failingDb);
        logicalDatabases.forEach(db -> databaseRegistryDbaasRepository.saveAnyTypeLogDb(db.getDatabaseRegistry().getFirst()));

        // A previous pass left a failure for the first pending database, plus a thread-level failure with a blank id.
        PhysicalDatabaseInstruction instruction = saveInstruction(physicalDatabaseId, registrationRequest());
        instruction.setFailures(new ArrayList<>(List.of(
                new FailureRegistrationV3(firstDb.getId().toString(), "first pass failure"),
                new FailureRegistrationV3("", "thread-level failure"))));
        physicalDatabaseInstructionRepository.save(instruction);

        WireMockResource.getServer().resetAll();
        stubSuccessfulEnsureUsers();
        stubFailedEnsureUsers(failingDb.getName());
        when(encryption.decrypt(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        Response response = physicalDatabasesService.registerPhysicalDatabase(
                requestedPhysicalDatabase, DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, true);
        assertEquals(INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus(),
                "The re-trigger must answer the failures recorded on the surviving instruction");
        List<FailureRegistrationV3> triggeredFailures = (List<FailureRegistrationV3>) response.getEntity();
        assertEquals(List.of(firstDb.getId().toString(), ""),
                triggeredFailures.stream().map(FailureRegistrationV3::getId).toList(),
                "The bad response must carry the failures recorded before the re-trigger");

        await("migration lock released after the failed pass", MIGRATION_TIMEOUT_MS,
                () -> !lockService.isLocked(migrationLockName()));

        PhysicalDatabaseInstruction actualInstruction =
                physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseId);
        assertNotNull(actualInstruction, "The instruction must survive the failed pass");
        List<String> actualFailureIds = actualInstruction.getFailures().stream()
                .map(FailureRegistrationV3::getId)
                .toList();
        assertTrue(actualFailureIds.contains(failingDb.getId().toString()),
                "The failure of the database that failed in the current portion must be kept");
        assertFalse(actualFailureIds.contains(""),
                "The thread-level failure with a blank id must be dropped");

        List<UUID> alignedIds = logicalDatabases.stream()
                .filter(db -> expectedRoles(requestedPhysicalDatabase)
                        .equals(rolesOf(databaseDbaasRepository.findById(db.getId()).orElseThrow().getConnectionProperties())))
                .map(Database::getId)
                .toList();
        assertTrue(alignedIds.contains(alignedDb.getId()), "The pre-aligned database must stay aligned");
        assertFalse(alignedIds.contains(failingDb.getId()), "The failed database must stay not aligned");
        if (alignedIds.contains(firstDb.getId())) {
            assertFalse(actualFailureIds.contains(firstDb.getId().toString()),
                    "The failure of the database that was migrated in the current portion must be dropped");
        } else {
            assertTrue(actualFailureIds.contains(firstDb.getId().toString()),
                    "The failure of the database that was not reached in the current portion must be kept");
        }
    }

    private void stubSuccessfulEnsureUsers() {
        WireMockResource.getServer().stubFor(
                put(urlPathEqualTo(ENSURE_USER_PATH))
                        .willReturn(aResponse()
                                .withStatus(OK.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withTransformers("response-template")
                                .withBody("""
                                        {
                                          "name": "{{jsonPath request.body '$.dbName'}}-{{jsonPath request.body '$.role'}}",
                                          "connectionProperties": {
                                            "role": "{{jsonPath request.body '$.role'}}",
                                            "username": "{{jsonPath request.body '$.dbName'}}-{{jsonPath request.body '$.role'}}",
                                            "password": "password"
                                          },
                                          "resources": [
                                            {"kind": "user", "name": "{{jsonPath request.body '$.dbName'}}-{{jsonPath request.body '$.role'}}"}
                                          ],
                                          "created": true
                                        }
                                        """)));
    }

    private void stubFailedEnsureUsers(String failingDbName) {
        WireMockResource.getServer().stubFor(
                put(urlPathEqualTo(ENSURE_USER_PATH))
                        .withRequestBody(matchingJsonPath("$.dbName", equalTo(failingDbName)))
                        .willReturn(aResponse()
                                .withStatus(INTERNAL_SERVER_ERROR.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "error": "INTERNAL_ERROR",
                                          "message": "Adapter failed to ensure user"
                                        }
                                        """)));
    }

    private String migrationLockName() {
        ArgumentCaptor<LockConfiguration> configurationCaptor = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(lockService, atLeastOnce()).getKeepAliveLock(configurationCaptor.capture());
        return configurationCaptor.getValue().getName();
    }

    private void await(String description, long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for: " + description);
            }
        }
        fail("Timed out after " + timeoutMs + " ms waiting for: " + description);
    }

    private InstructionRequestV3 adapterPortionReport(List<AdditionalRoles> portion, PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase) {
        List<String> requiredRoles = expectedRoles(requestedPhysicalDatabase);
        List<SuccessRegistrationV3> successRegistrations = portion.stream()
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

    private Instruction instructionOf(Response response) {
        Map<String, Object> responseEntity = (Map<String, Object>) response.getEntity();
        Instruction instruction = (Instruction) responseEntity.get("instruction");
        assertNotNull(instruction, "Instruction must be present in the 202 response body");
        return instruction;
    }

    private AdditionalRoles findPortion(Instruction instruction, Database database) {
        return instruction.getAdditionalRoles().stream()
                .filter(additionalRoles -> additionalRoles.getId().equals(database.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Portion must contain logical database " + database.getName()));
    }

    private void assertDatabaseAligned(Database database, PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase) {
        Database actualDatabase = databaseDbaasRepository.findById(database.getId()).orElseThrow();
        assertEquals(expectedRoles(requestedPhysicalDatabase), rolesOf(actualDatabase.getConnectionProperties()),
                "Roles of logical database " + database.getName());
        assertTrue(actualDatabase.getResources().stream()
                        .filter(resource -> "user".equals(resource.getKind()))
                        .map(DbResource::getName)
                        .toList()
                        .containsAll(userNames(database.getName(), requestedPhysicalDatabase)),
                "Resources of logical database " + database.getName() + " must include users for the new roles");
    }

    private void assertDatabaseNotAligned(Database database) {
        Database actualDatabase = databaseDbaasRepository.findById(database.getId()).orElseThrow();
        assertEquals(List.of(Role.ADMIN.toString()), rolesOf(actualDatabase.getConnectionProperties()),
                "Roles of logical database " + database.getName() + " must stay unchanged");
    }

    private PhysicalDatabaseInstruction saveInstruction(String physicalDatabaseId, PhysicalDatabaseRegistryRequestV3 request) {
        request.getMetadata().getSupportedRoles().replaceAll(String::toLowerCase);
        PhysicalDatabaseInstruction instruction = new PhysicalDatabaseInstruction();
        instruction.setId(UUID.randomUUID());
        instruction.setPhysicalDatabaseId(physicalDatabaseId);
        instruction.setContext("[]"); // resolves as blank list
        instruction.setPhysicalDbRegRequest(request);
        instruction.setInstructionType(InstructionType.MULTIUSERS_MIGRATION);
        instruction.setTimeCreation(new Date());
        physicalDatabaseInstructionRepository.save(instruction);
        return instruction;
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
        Map<String, Boolean> map = new HashMap<>();
        map.put("multiusers", true);

        PhysicalDatabaseRegistryRequestV3 request = new PhysicalDatabaseRegistryRequestV3();
        request.setAdapterAddress(adapterAddress);
        request.setHttpBasicCredentials(new HttpBasicCredentials("test-username", "test-password"));
        request.setStatus("running");
        request.setMetadata(new Metadata(VERSION_2, requiredRoles, map));
        return request;
    }

    private PhysicalDatabase getPhysicalDatabaseSample() {
        String physicalDatabaseId = "phDbId-" + Math.random();
        Response response = physicalDatabasesService.registerPhysicalDatabase(
                registrationRequest(), DatabaseType.POSTGRESQL.toString(), physicalDatabaseId, true);
        assertEquals(CREATED.getStatusCode(), response.getStatus(),
                "A brand new physical database must be registered");
        PhysicalDatabase physicalDatabase = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        assertNotNull(physicalDatabase, "The registered physical database must be stored");
        return physicalDatabase;
    }
}
