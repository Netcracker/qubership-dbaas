package com.netcracker.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.dto.RegisteredPhysicalDatabasesDTO;
import com.netcracker.cloud.dbaas.dto.role.Role;
import com.netcracker.cloud.dbaas.dto.v3.*;
import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabase;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.service.PhysicalDatabasesService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.*;

import static com.netcracker.cloud.dbaas.DbaasApiPath.DBAAS_PATH_V3;
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
    private final UUID INSTRUCTION_ID = UUID.fromString("acde070d-8c4c-4f0d-9d8a-162843c10333");
    private final UUID DATABASE_ID = UUID.randomUUID();

    @InjectMock
    PhysicalDatabasesService physicalDatabasesService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testRegister() throws JsonProcessingException {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistryRequestV3Sample();
        Instruction instruction = getInstructionSample();
        Map<String, Object> response = new HashMap<>();
        response.put("instruction", instruction);

        when(physicalDatabasesService.registerPhysicalDatabase(physicalDatabaseRegistryRequest, TEST_TYPE, PHYDBID.toString(), false))
                .thenReturn(
                        Response.created(URI.create(DBAAS_PATH_V3 + "/" + TEST_TYPE + "/physical_databases/" + PHYDBID)
                        ).build());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(CREATED.getStatusCode());

        when(physicalDatabasesService.registerPhysicalDatabase(physicalDatabaseRegistryRequest, TEST_TYPE, PHYDBID.toString(), false))
                .thenReturn(
                        Response.ok().build()
                );

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(OK.getStatusCode());

        when(physicalDatabasesService.registerPhysicalDatabase(physicalDatabaseRegistryRequest, TEST_TYPE, PHYDBID.toString(), false))
                .thenReturn(
                        Response.accepted().entity(response).build()
                );
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(ACCEPTED.getStatusCode())
                .body(is(objectMapper.writeValueAsString(response)));

        when(physicalDatabasesService.registerPhysicalDatabase(physicalDatabaseRegistryRequest, TEST_TYPE, PHYDBID.toString(), false))
                .thenReturn(
                        Response.status(CONFLICT).build()
                );
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(CONFLICT.getStatusCode());

        when(physicalDatabasesService.registerPhysicalDatabase(physicalDatabaseRegistryRequest, TEST_TYPE, PHYDBID.toString(), false))
                .thenReturn(
                        Response.status(BAD_GATEWAY).build()
                );
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(BAD_GATEWAY.getStatusCode());

        when(physicalDatabasesService.registerPhysicalDatabase(physicalDatabaseRegistryRequest, TEST_TYPE, PHYDBID.toString(), false))
                .thenReturn(
                        Response.status(BAD_REQUEST).build()
                );
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());
    }

    @Test
    void testRegister_featuresWithoutMultiusersKeyFailsWithNullPointerException() throws JsonProcessingException {
        // The OpenAPI contract requires `features` but does not declare a `multiusers` key, so this request is valid.
        PhysicalDatabaseRegistryRequestV3 missingMultiusersRequest = getPhysicalDatabaseRegistryRequestV3Sample();
        missingMultiusersRequest.getMetadata().setFeatures(new HashMap<>(Map.of("tls", true)));

        // The service fails with NullPointerException (PhysicalDatabasesService#isMigrationRequired reads features.get("multiusers"))
        doThrow(new NullPointerException("multiusers key is missing in features"))
                .when(physicalDatabasesService)
                .registerPhysicalDatabase(missingMultiusersRequest, TEST_TYPE, PHYDBID.toString(), false);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(missingMultiusersRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .body("code", is("CORE-DBAAS-2000"));

        // An explicit `multiusers: false` skips the migration branch, so only the missing key causes the failure.
        PhysicalDatabaseRegistryRequestV3 multiusersDisabledRequest = getPhysicalDatabaseRegistryRequestV3Sample();
        multiusersDisabledRequest.getMetadata().getFeatures().put("multiusers", false);
        when(physicalDatabasesService.registerPhysicalDatabase(multiusersDisabledRequest, TEST_TYPE, PHYDBID.toString(), false))
                .thenReturn(Response.ok().build());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(multiusersDisabledRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(OK.getStatusCode());
    }

    @Test
    void testRegister_missingAdapterAddressFailsWithInternalServerError() throws Exception {
        PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistryRequestV3Sample();
        physicalDatabaseRegistryRequest.setAdapterAddress(null);

        doThrow(new NullPointerException("adapterAddress is null"))
                .when(physicalDatabasesService)
                .registerPhysicalDatabase(physicalDatabaseRegistryRequest, TEST_TYPE, PHYDBID.toString(), false);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(physicalDatabaseRegistryRequest))
                .when().put("/{phydbid}", PHYDBID)
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .body("code", is("CORE-DBAAS-2000"));
    }

    @Test
    void testRegister_failureWhileSavingConnectionProperties() throws JsonProcessingException {
        InstructionRequestV3 successfulInstructionRequest = getSuccessfulInstructionRequestV3Sample();

        // Failure while saving connection properties after roles registration -> 500
        doThrow(new RuntimeException("test"))
                .when(physicalDatabasesService)
                .handleInstruction(INSTRUCTION_ID, successfulInstructionRequest);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(successfulInstructionRequest))
                .when().post("/{phydbid}/instruction/{instructionid}/additional-roles", PHYDBID, INSTRUCTION_ID)
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode());
    }

    @Test
    void testInstruction_shouldReturnNotFoundForUnknownInstructionId() throws JsonProcessingException {
        InstructionRequestV3 successfulInstructionRequest = getSuccessfulInstructionRequestV3Sample();
        when(physicalDatabasesService.handleInstruction(INSTRUCTION_ID, successfulInstructionRequest))
                .thenReturn(Response.status(NOT_FOUND)
                        .entity(String.format("Instruction with Id = %s not found", INSTRUCTION_ID))
                        .build());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(successfulInstructionRequest))
                .when().post("/{phydbid}/instruction/{instructionid}/additional-roles", PHYDBID, INSTRUCTION_ID)
                .then()
                .statusCode(NOT_FOUND.getStatusCode())
                .body(is(String.format("Instruction with Id = %s not found", INSTRUCTION_ID)));
    }

    @Test
    void testGetRegisteredDatabases() {
        when(physicalDatabasesService.getRegisteredDatabases(TEST_TYPE)).thenReturn(Collections.emptyList());
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
        when(physicalDatabasesService.getRegisteredDatabases(TEST_TYPE)).thenReturn(List.of(physicalDatabase, getPhysicalDatabaseSample()));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .when().delete("/{phydbid}", PHYDBID)
                .then()
                .statusCode(NOT_ACCEPTABLE.getStatusCode());
    }

    @Test
    void testDeleteSingleGlobalDatabase() {
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        physicalDatabase.setGlobal(true);
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(String.valueOf(PHYDBID))).thenReturn(physicalDatabase);
        when(physicalDatabasesService.getRegisteredDatabases(TEST_TYPE)).thenReturn(List.of(physicalDatabase));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", TEST_TYPE)
                .when().delete("/{phydbid}", PHYDBID)
                .then()
                .statusCode(OK.getStatusCode());
        verify(physicalDatabasesService, times(1)).dropDatabase(physicalDatabase);
    }

    @Test
    void testRequestedAndActualPhysicalDatabaseTypeMismatch() {
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(String.valueOf(PHYDBID))).thenReturn(physicalDatabase);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam("type", "misType")
                .when().delete("/{phydbid}", PHYDBID)
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());
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
        physicalDatabaseRegistryRequestV3.setAdapterAddress("http://dbaas-postgres-adapter.postgresql:8080");
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
        physicalDatabase.setType(TEST_TYPE);
        return physicalDatabase;
    }

    private InstructionRequestV3 getSuccessfulInstructionRequestV3Sample() {
        final InstructionRequestV3 instructionRequest = new InstructionRequestV3();
        SuccessRegistrationV3 successRegistrationV3 = new SuccessRegistrationV3
                (DATABASE_ID, new ArrayList<>(), new ArrayList<>());
        instructionRequest.setSuccess(Collections.singletonList(successRegistrationV3));
        return instructionRequest;
    }

    private Instruction getInstructionSample() {
        return new Instruction(INSTRUCTION_ID, Collections.singletonList(new AdditionalRoles()));
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
