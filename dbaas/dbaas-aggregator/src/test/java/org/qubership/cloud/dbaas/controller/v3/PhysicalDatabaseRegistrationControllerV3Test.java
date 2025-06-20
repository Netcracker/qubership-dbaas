package org.qubership.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.InstructionType;
import org.qubership.cloud.dbaas.dto.RegisteredPhysicalDatabasesDTO;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.*;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabaseInstruction;
import org.qubership.cloud.dbaas.exceptions.AdapterUnavailableException;
import org.qubership.cloud.dbaas.exceptions.PhysicalDatabaseRegistrationConflictException;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.service.InstructionService;
import org.qubership.cloud.dbaas.service.PhysicalDatabasesService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.MediaType;

import org.junit.jupiter.api.Test;

import java.util.*;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(PhysicalDatabaseRegistrationControllerV3.class)
class PhysicalDatabaseRegistrationControllerV3Test {

    private final String TEST_TYPE = "testtype";
    private final UUID PHYDBID = UUID.randomUUID();
    private final String TEST_PHYDBIDENTIFIER = "test-phydbidentifier";
    private final String INSTRUCTION_ID = "acde070d-8c4c-4f0d-9d8a-162843c10333";
    private final UUID DATABASE_ID = UUID.randomUUID();

    @InjectMock
    PhysicalDatabasesService physicalDatabasesService;
    @InjectMock
    InstructionService instructionService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testRegister() throws JsonProcessingException {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistryRequestV3Sample();
        PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        List<Database> logicalDatabases = Collections.singletonList(new Database());
        Instruction instruction = getInstructionSample();
        Map<String, Object> response = new HashMap<>();
        response.put("instruction", instruction);
        when(physicalDatabasesService.foundPhysicalDatabase(PHYDBID.toString(), TEST_TYPE, physicalDatabaseRegistryRequest))
                .thenReturn(Optional.empty());
        when(physicalDatabasesService.isDbActual(any(), any())).thenReturn(false);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(CREATED.getStatusCode());

        when(physicalDatabasesService.foundPhysicalDatabase(PHYDBID.toString(), TEST_TYPE, physicalDatabaseRegistryRequest))
                .thenReturn(Optional.of(physicalDatabase));
        when(instructionService.isRolesDifferent(eq(physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles()), any())).thenReturn(true);
        when(instructionService.getLogicalDatabasesForMigration(PHYDBID.toString(), TEST_TYPE,
                physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles())).thenReturn(Collections.emptyList());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(OK.getStatusCode());
        verify(instructionService).isRolesDifferent(
                eq(physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles()), eq(physicalDatabase.getRoles()));

        when(instructionService.isRolesDifferent(physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles(), physicalDatabase.getRoles())).
                thenReturn(true);
        when(instructionService.getLogicalDatabasesForMigration(PHYDBID.toString(), TEST_TYPE,
                physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles()))
                .thenReturn(logicalDatabases);
        when(instructionService.findInstructionByPhyDbId(physicalDatabase.getPhysicalDatabaseIdentifier()))
                .thenReturn(null);
        when(instructionService.buildInstructionForAdditionalRoles(logicalDatabases))
                .thenReturn(instruction);
        when(instructionService.saveInstruction(physicalDatabase, instruction, physicalDatabaseRegistryRequest)).thenReturn(response);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(ACCEPTED.getStatusCode())
                .body(is(objectMapper.writeValueAsString(response)));

        when(physicalDatabasesService.foundPhysicalDatabase(PHYDBID.toString(), TEST_TYPE, physicalDatabaseRegistryRequest))
                .thenThrow(new PhysicalDatabaseRegistrationConflictException("test"));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(CONFLICT.getStatusCode());

        reset(physicalDatabasesService);
        when(physicalDatabasesService.foundPhysicalDatabase(PHYDBID.toString(), TEST_TYPE, physicalDatabaseRegistryRequest))
                .thenThrow(new AdapterUnavailableException(404));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(BAD_GATEWAY.getStatusCode());
    }

    @Test
    void testRegisterWithExistingInstruction() throws JsonProcessingException {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistryRequestV3Sample();
        PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        List<Database> logicalDatabases = Collections.singletonList(new Database());
        Instruction instruction = getInstructionSample();
        PhysicalDatabaseInstruction physicalDatabaseInstruction = getPhysicalDatabaseInstructionSample();
        Map<String, Object> response = new HashMap<>();
        response.put("instruction", instruction);
        when(physicalDatabasesService.isDbActual(any(), any())).thenReturn(false);
        when(physicalDatabasesService.foundPhysicalDatabase(PHYDBID.toString(), TEST_TYPE, physicalDatabaseRegistryRequest))
                .thenReturn(Optional.empty());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(CREATED.getStatusCode());

        when(physicalDatabasesService.foundPhysicalDatabase(PHYDBID.toString(), TEST_TYPE, physicalDatabaseRegistryRequest))
                .thenReturn(Optional.of(physicalDatabase));
        when(instructionService.isRolesDifferent(eq(physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles()), any())).thenReturn(true);
        when(instructionService.getLogicalDatabasesForMigration(PHYDBID.toString(), TEST_TYPE,
                physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles())).thenReturn(Collections.emptyList());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(OK.getStatusCode());
        verify(instructionService, times(1)).isRolesDifferent(
                physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles(), physicalDatabase.getRoles());

        when(instructionService.isRolesDifferent(physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles(), physicalDatabase.getRoles())).
                thenReturn(true);
        when(instructionService.getLogicalDatabasesForMigration(PHYDBID.toString(), TEST_TYPE,
                physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles()))
                .thenReturn(logicalDatabases);
        when(instructionService.findInstructionByPhyDbId(physicalDatabase.getPhysicalDatabaseIdentifier()))
                .thenReturn(physicalDatabaseInstruction);
        when(instructionService.findPortion(INSTRUCTION_ID)).thenReturn(instruction);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(ACCEPTED.getStatusCode())
                .body(is(objectMapper.writeValueAsString(response)));
    }

    @Test
    void testRegisterWithExistingInstructionWithNullPortion() throws JsonProcessingException {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistryRequestV3Sample();
        PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        List<Database> logicalDatabases = Collections.singletonList(new Database());
        Instruction instruction = getInstructionSample();
        PhysicalDatabaseInstruction physicalDatabaseInstruction = getPhysicalDatabaseInstructionSample();
        Map<String, Object> response = new HashMap<>();
        response.put("instruction", instruction);
        when(physicalDatabasesService.isDbActual(any(), any())).thenReturn(false);
        when(physicalDatabasesService.foundPhysicalDatabase(PHYDBID.toString(), TEST_TYPE, physicalDatabaseRegistryRequest))
                .thenReturn(Optional.empty());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(CREATED.getStatusCode());

        when(physicalDatabasesService.foundPhysicalDatabase(PHYDBID.toString(), TEST_TYPE, physicalDatabaseRegistryRequest))
                .thenReturn(Optional.of(physicalDatabase));
        when(instructionService.isRolesDifferent(eq(physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles()), any())).thenReturn(true);
        when(instructionService.getLogicalDatabasesForMigration(PHYDBID.toString(), TEST_TYPE,
                physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles())).thenReturn(Collections.emptyList());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(OK.getStatusCode());
        verify(instructionService, times(1)).isRolesDifferent(
                physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles(), physicalDatabase.getRoles());

        when(instructionService.isRolesDifferent(physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles(), physicalDatabase.getRoles())).
                thenReturn(true);
        when(instructionService.getLogicalDatabasesForMigration(PHYDBID.toString(), TEST_TYPE,
                physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles()))
                .thenReturn(logicalDatabases);
        when(instructionService.findInstructionByPhyDbId(physicalDatabase.getPhysicalDatabaseIdentifier()))
                .thenReturn(physicalDatabaseInstruction);
        Instruction portionedInstruction = null;
        when(instructionService.findPortion(INSTRUCTION_ID)).thenReturn(portionedInstruction);
        when(instructionService.buildInstructionForAdditionalRoles(logicalDatabases))
                .thenReturn(instruction);
        when(instructionService.saveInstruction(physicalDatabase, instruction, physicalDatabaseRegistryRequest)).thenReturn(response);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(ACCEPTED.getStatusCode())
                .body(is(objectMapper.writeValueAsString(response)));
    }

    @Test
    void testSuccessfulInstruction() throws JsonProcessingException {
        InstructionRequestV3 successfulInstructionRequest = getSuccessfulInstructionRequestV3Sample();
        Instruction instruction = getInstructionSample();

        when(instructionService.findInstructionById(INSTRUCTION_ID)).thenReturn(instruction);
        when(instructionService.findNextAdditionalRoles(INSTRUCTION_ID))
                .thenReturn(null);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(successfulInstructionRequest))
                .when().post("/{phydbid}/instruction/{instructionid}/additional-roles", PHYDBID, INSTRUCTION_ID)
                .then()
                .statusCode(OK.getStatusCode());

        when(instructionService.findInstructionById(INSTRUCTION_ID)).thenReturn(instruction);
        when(instructionService.findNextAdditionalRoles(INSTRUCTION_ID))
                .thenReturn(new ArrayList<AdditionalRoles>());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(successfulInstructionRequest))
                .when().post("/{phydbid}/instruction/{instructionid}/additional-roles", PHYDBID, INSTRUCTION_ID)
                .then()
                .statusCode(ACCEPTED.getStatusCode());

        when(instructionService.findInstructionById(INSTRUCTION_ID)).thenReturn(instruction);
        doThrow(new RuntimeException("test")).when(instructionService).saveConnectionPropertiesAfterRolesRegistration(successfulInstructionRequest.getSuccess());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(successfulInstructionRequest))
                .when().post("/{phydbid}/instruction/{instructionid}/additional-roles", PHYDBID, INSTRUCTION_ID)
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode());

        when(instructionService.findInstructionById(INSTRUCTION_ID)).thenReturn(null);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(successfulInstructionRequest))
                .when().post("/{phydbid}/instruction/{instructionid}/additional-roles", PHYDBID, INSTRUCTION_ID)
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }


    @Test
    void testFailureInstruction() throws JsonProcessingException {
        InstructionRequestV3 failureInstructionRequest = getFailureInstructionRequestV3Sample();
        when(instructionService.findInstructionById(INSTRUCTION_ID)).thenReturn(new Instruction());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(failureInstructionRequest))
                .when().post("/{phydbid}/instruction/{instructionid}/additional-roles", PHYDBID, INSTRUCTION_ID)
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode());
    }

    @Test
    void testGetRegisteredDatabases() {
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .when().get()
                .then()
                .statusCode(NOT_FOUND.getStatusCode());

        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        final RegisteredPhysicalDatabasesDTO registeredPhysicalDatabasesDTO = getRegisteredPhysicalDatabasesDTOSample();
        when(physicalDatabasesService.getRegisteredDatabases(TEST_TYPE)).thenReturn(Collections.singletonList(physicalDatabase));
        when(physicalDatabasesService.presentPhysicalDatabases(any())).thenReturn(registeredPhysicalDatabasesDTO);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .when().get()
                .then()
                .statusCode(OK.getStatusCode())
                .body("identified.test-key.adapterId", is(registeredPhysicalDatabasesDTO.getIdentified().get("test-key").getAdapterId()))
                .body("identified.test-key.supportedRoles", is(registeredPhysicalDatabasesDTO.getIdentified().get("test-key").getSupportedRoles()));

        verify(physicalDatabasesService, times(2)).getRegisteredDatabases(TEST_TYPE);
        verify(physicalDatabasesService, times(1)).presentPhysicalDatabases(any());
        verifyNoMoreInteractions(physicalDatabasesService);
    }

    @Test
    void testCanNotDeleteGlobalDatabase() {
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        physicalDatabase.setGlobal(true);
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(String.valueOf(PHYDBID))).thenReturn(physicalDatabase);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .when().delete("/{phydbid}", PHYDBID)
                .then()
                .statusCode(NOT_ACCEPTABLE.getStatusCode());
    }

    @Test
    void testCanNotDeleteNonExistentDatabase() {
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(String.valueOf(PHYDBID))).thenReturn(null);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .when().delete("/{phydbid}", PHYDBID)
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    void testCanNotDeleteDatabaseWithConnectedLogicalDatabases() {
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();

        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(String.valueOf(PHYDBID))).thenReturn(physicalDatabase);
        when(physicalDatabasesService.checkContainsConnectedLogicalDb(physicalDatabase)).thenReturn(true);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .when().delete("/{phydbid}", PHYDBID)
                .then()
                .statusCode(NOT_ACCEPTABLE.getStatusCode());
    }

    @Test
    void testSuccessfulDeletion() {
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();

        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(String.valueOf(PHYDBID))).thenReturn(physicalDatabase);
        when(physicalDatabasesService.checkContainsConnectedLogicalDb(physicalDatabase)).thenReturn(false);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .when().delete("/{phydbid}", PHYDBID)
                .then()
                .statusCode(OK.getStatusCode());
        verify(physicalDatabasesService, times(1)).dropDatabase(physicalDatabase);
    }

    @Test
    void testMakeGlobal_Ok() {
        PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(String.valueOf(PHYDBID))).thenReturn(physicalDatabase);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .when().put("/{phydbid}/global", PHYDBID)
                .then()
                .statusCode(OK.getStatusCode());
        verify(physicalDatabasesService, times(1)).makeGlobal(physicalDatabase);
    }

    @Test
    void testMakeGlobal_NotExist() {
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(String.valueOf(PHYDBID))).thenReturn(null);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .when().put("/{phydbid}/global", PHYDBID)
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
        verify(physicalDatabasesService, times(0)).makeGlobal(any());
    }

    private PhysicalDatabaseRegistryRequestV3 getPhysicalDatabaseRegistryRequestV3Sample() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequestV3 = new PhysicalDatabaseRegistryRequestV3();
        physicalDatabaseRegistryRequestV3.setAdapterAddress("test-address");
        Map<String, Boolean> features = new HashMap<>();
        features.put("multiusers", true);
        List<String> roles = Arrays.asList(Role.ADMIN.toString(), "ro");
        physicalDatabaseRegistryRequestV3.setMetadata(new Metadata("v2",
                Arrays.asList(Role.ADMIN.toString(), "ro"), features));
        physicalDatabaseRegistryRequestV3.setStatus("running");
        return physicalDatabaseRegistryRequestV3;
    }

    private PhysicalDatabase getPhysicalDatabaseSample() {
        final PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(PHYDBID.toString());
        physicalDatabase.setPhysicalDatabaseIdentifier(PHYDBID.toString());
        physicalDatabase.setRoles(Collections.singletonList(Role.ADMIN.toString()));
        return physicalDatabase;
    }

    private InstructionRequestV3 getSuccessfulInstructionRequestV3Sample() {
        final InstructionRequestV3 instructionRequest = new InstructionRequestV3();
        SuccessRegistrationV3 successRegistrationV3 = new SuccessRegistrationV3
                (DATABASE_ID, new ArrayList<>(), new ArrayList<>());
        instructionRequest.setSuccess(Collections.singletonList(successRegistrationV3));
        return instructionRequest;
    }

    private InstructionRequestV3 getFailureInstructionRequestV3Sample() {
        final InstructionRequestV3 instructionRequest = new InstructionRequestV3();
        instructionRequest.setFailure(new FailureRegistrationV3("1", "error"));
        return instructionRequest;
    }

    private Instruction getInstructionSample() {
        return new Instruction(INSTRUCTION_ID, Collections.singletonList(new AdditionalRoles()));
    }

    private PhysicalDatabaseInstruction getPhysicalDatabaseInstructionSample() throws JsonProcessingException {
        PhysicalDatabaseInstruction physicalDatabaseInstruction = new PhysicalDatabaseInstruction();
        physicalDatabaseInstruction.setId(UUID.fromString(INSTRUCTION_ID));
        physicalDatabaseInstruction.setInstructionType(InstructionType.MULTIUSERS_MIGRATION);
        physicalDatabaseInstruction.setPhysicalDatabaseId(TEST_PHYDBIDENTIFIER);
        physicalDatabaseInstruction.setContext(instructionService.listToString(getInstructionSample().getAdditionalRoles()));
        physicalDatabaseInstruction.setPhysicalDbRegRequest(getPhysicalDatabaseRegistryRequestV3Sample());
        Date timeDbInstructionCreation = new Date();
        physicalDatabaseInstruction.setTimeCreation(timeDbInstructionCreation);
        return physicalDatabaseInstruction;

    }

    private RegisteredPhysicalDatabasesDTO getRegisteredPhysicalDatabasesDTOSample() {
        final RegisteredPhysicalDatabasesDTO registeredPhysicalDatabasesDTO = new RegisteredPhysicalDatabasesDTO();
        final PhysicalDatabaseRegistrationResponseDTOV3 physicalDatabaseRegistrationResponseDTO = new PhysicalDatabaseRegistrationResponseDTOV3();
        physicalDatabaseRegistrationResponseDTO.setSupportedRoles(Arrays.asList("admin", "ro"));
        final Map<String, PhysicalDatabaseRegistrationResponseDTOV3> identifiedMap = new HashMap<>();
        identifiedMap.put("test-key", physicalDatabaseRegistrationResponseDTO);
        registeredPhysicalDatabasesDTO.setIdentified(identifiedMap);
        return registeredPhysicalDatabasesDTO;
    }
}
