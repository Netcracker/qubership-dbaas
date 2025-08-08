package org.qubership.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.ClassifierWithRolesRequest;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.*;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.exceptions.ErrorCodes;
import org.qubership.cloud.dbaas.exceptions.UnregisteredPhysicalDatabaseException;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.monitoring.model.DatabasesInfo;
import org.qubership.cloud.dbaas.monitoring.model.DatabasesInfoSegment;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseDeclarativeConfigRepository;
import org.qubership.cloud.dbaas.service.*;
import org.qubership.cloud.dbaas.service.composite.CompositeNamespaceService;
import org.qubership.cloud.dbaas.service.dbsettings.LogicalDbSettingsService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.MockitoConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.cloud.dbaas.Constants.ACTIVE_STATE;
import static org.qubership.cloud.dbaas.Constants.IDLE_STATE;
import static org.qubership.cloud.dbaas.Constants.NAMESPACE;
import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.qubership.cloud.dbaas.DbaasApiPath.ASYNC_PARAMETER;
import static org.qubership.cloud.dbaas.DbaasApiPath.LIST_DATABASES_PATH;
import static org.qubership.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.*;
import static java.util.Collections.singletonList;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(AggregatedDatabaseAdministrationControllerV3.class)
@Slf4j
class AggregatedDatabaseAdministrationControllerV3Test {

    private static final String PHYSICAL_DATABASE_ID = "some_physical_database_id";
    private static final String TEST_CONTROLLER_NAMESPACE = "test-controller-namespace";
    private static final String TEST_NAME = "test-db";
    private static final String TEST_NAMESPACE = "test-namespace";
    private static final String TEST_NAMESPACE_IDLE = "test-namespace-idle";
    private static final String TEST_RO_HOST = "test-ro-host";

    @InjectMock
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @InjectMock
    DBaaService dBaaService;
    @InjectMock
    @MockitoConfig(convertScopes = true)
    LogicalDbSettingsService logicalDbSettingsService;
    @InjectMock
    MonitoringService monitoringService;
    @InjectMock
    DatabaseDeclarativeConfigRepository declarativeConfigRepository;
    @InjectMock
    DatabaseRolesService databaseRolesService;
    @InjectMock
    PhysicalDatabasesService physicalDatabasesService;
    @InjectMock
    DbaaSHelper dbaaSHelper;
    @InjectMock
    BlueGreenService blueGreenService;
    @InjectMock
    CompositeNamespaceService compositeNamespaceService;

    @Inject
    ProcessConnectionPropertiesService processConnectionPropertiesService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testCreateDatabaseWithRoHost() throws JsonProcessingException {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(processConnectionPropertiesService);
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setPhysicalDatabaseIdentifier("test-id");
        physicalDatabase.setRoHost(TEST_RO_HOST);
        when(physicalDatabasesService.searchInPhysicalDatabaseCache(any())).thenReturn(physicalDatabase);

        final DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample();
        when(declarativeConfigRepository.findFirstByClassifierAndType(any(), any())).thenReturn(Optional.empty());
        Mockito.when(databaseRolesService.getSupportedRoleFromRequest(any(DatabaseCreateRequestV3.class), any(), any())).thenReturn(Role.ADMIN.toString());
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenThrow(new ConstraintViolationException("constraint violation", new PSQLException("constraint violation", PSQLState.UNIQUE_VIOLATION), "database_registry_classifier_and_type_index"));

        final DatabaseRegistry database = getDatabaseSample();
        database.setPhysicalDatabaseId("test-id");
        when(dBaaService.findDatabaseByClassifierAndType(any(), any(), anyBoolean())).thenReturn(database.getDatabaseRegistry().get(0));
        when(dBaaService.isModifiedFields(any(), any())).thenReturn(true);
        when(dBaaService.detach(database)).thenReturn(database);
        when(dBaaService.isModifiedFields(any(), any())).thenReturn(false);
        DatabaseResponseV3 response = new DatabaseResponseV3SingleCP(database.getDatabaseRegistry().get(0), PHYSICAL_DATABASE_ID, Role.ADMIN.toString());
        when(dBaaService.processConnectionPropertiesV3(any(DatabaseRegistry.class), any())).thenReturn(response);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(OK.getStatusCode())
                .body("name", is(database.getName()))
                .body("connectionProperties.roHost", is(TEST_RO_HOST));
    }

    @Test
    void testCreateDatabaseControllerNamespace() throws Exception {
        final DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample();
        Map<String, Object> activeClassifier = new HashMap<>(databaseCreateRequest.getClassifier());
        databaseCreateRequest.getClassifier().put(NAMESPACE, TEST_CONTROLLER_NAMESPACE);
        BgDomain bgDomain = new BgDomain();
        bgDomain.setControllerNamespace(TEST_CONTROLLER_NAMESPACE);
        BgNamespace bgNamespace = new BgNamespace();
        bgNamespace.setState(ACTIVE_STATE);
        bgNamespace.setNamespace(TEST_NAMESPACE);
        bgNamespace.setBgDomain(bgDomain);
        bgDomain.setNamespaces(List.of(bgNamespace));
        final DatabaseRegistry database = getDatabaseSample();
        when(dBaaService.findDatabaseByClassifierAndType(eq(databaseCreateRequest.getClassifier()), any(), anyBoolean())).thenReturn(null);

        when(dBaaService.findDatabaseByClassifierAndType(eq(activeClassifier), any(), anyBoolean())).thenReturn(database.getDatabaseRegistry().get(0));
        when(blueGreenService.getDomainByControllerNamespace(TEST_CONTROLLER_NAMESPACE)).thenReturn(Optional.of(bgDomain));
        Mockito.when(databaseRolesService.getSupportedRoleFromRequest(any(DatabaseCreateRequestV3.class), any(), any())).thenReturn(Role.ADMIN.toString());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(OK.getStatusCode());
    }

    @Test
    void testCreateDatabaseControllerNamespaceDatabaseIsAbsentInActiveExists() throws Exception {
        final DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample();
        databaseCreateRequest.getClassifier().put(NAMESPACE, TEST_CONTROLLER_NAMESPACE);
        BgDomain bgDomain = new BgDomain();
        bgDomain.setControllerNamespace(TEST_CONTROLLER_NAMESPACE);
        BgNamespace bgNamespace = new BgNamespace();
        bgNamespace.setState(ACTIVE_STATE);
        bgNamespace.setNamespace(TEST_NAMESPACE);
        bgNamespace.setBgDomain(bgDomain);
        bgDomain.setNamespaces(List.of(bgNamespace));
        when(dBaaService.findDatabaseByClassifierAndType(eq(databaseCreateRequest.getClassifier()), any(), anyBoolean())).thenReturn(null);

        when(blueGreenService.getDomainByControllerNamespace(TEST_CONTROLLER_NAMESPACE)).thenReturn(Optional.of(bgDomain));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode());
    }

    @Test
    void testCreateDatabaseControllerNamespaceWithVersionedDbException() throws Exception {
        final DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample();
        Map<String, Object> activeClassifier = new HashMap<>(databaseCreateRequest.getClassifier());
        databaseCreateRequest.getClassifier().put(NAMESPACE, TEST_CONTROLLER_NAMESPACE);
        BgDomain bgDomain = new BgDomain();
        bgDomain.setControllerNamespace(TEST_CONTROLLER_NAMESPACE);
        BgNamespace bgNamespace = new BgNamespace();
        bgNamespace.setState(ACTIVE_STATE);
        bgNamespace.setNamespace(TEST_NAMESPACE);
        bgNamespace.setBgDomain(bgDomain);
        bgDomain.setNamespaces(List.of(bgNamespace));
        final DatabaseRegistry database = getDatabaseSample();
        when(dBaaService.findDatabaseByClassifierAndType(eq(databaseCreateRequest.getClassifier()), any(), anyBoolean())).thenReturn(null);

        when(dBaaService.findDatabaseByClassifierAndType(eq(activeClassifier), any(), anyBoolean())).thenReturn(database.getDatabaseRegistry().get(0));
        when(blueGreenService.getDomainByControllerNamespace(TEST_CONTROLLER_NAMESPACE)).thenReturn(Optional.of(bgDomain));
        Mockito.when(databaseRolesService.getSupportedRoleFromRequest(any(DatabaseCreateRequestV3.class), any(), any())).thenReturn(null);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(FORBIDDEN.getStatusCode());
    }

    @Test
    void testCreateDatabase() throws JsonProcessingException {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(processConnectionPropertiesService);
        final DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample();
        when(declarativeConfigRepository.findFirstByClassifierAndType(any(), any())).thenReturn(Optional.empty());
        Mockito.when(databaseRolesService.getSupportedRoleFromRequest(any(DatabaseCreateRequestV3.class), any(), any())).thenReturn(Role.ADMIN.toString());
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenThrow(new ConstraintViolationException("constraint violation", new PSQLException("constraint violation", PSQLState.UNIQUE_VIOLATION), "database_registry_classifier_and_type_index"));
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(anyMap(), anyString())).thenReturn(Optional.of(Mockito.mock(DatabaseRegistry.class)));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(CONFLICT.getStatusCode());

        final DatabaseRegistry database = getDatabaseSample();
        when(dBaaService.findDatabaseByClassifierAndType(any(), any(), anyBoolean())).thenReturn(database.getDatabaseRegistry().get(0));
        when(dBaaService.isModifiedFields(any(), any())).thenReturn(true);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());
        // Is it intended that the same request tested twice?
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());

        when(dBaaService.detach(database)).thenReturn(database);
        when(dBaaService.isModifiedFields(any(), any())).thenReturn(false);
        DatabaseResponseV3 response = new DatabaseResponseV3SingleCP(database.getDatabaseRegistry().get(0), PHYSICAL_DATABASE_ID, Role.ADMIN.toString());
        when(dBaaService.processConnectionPropertiesV3(any(DatabaseRegistry.class), any())).thenReturn(response);
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(OK.getStatusCode())
                .body("name", is(database.getName()));

        reset(databaseRegistryDbaasRepository);

        DatabaseRegistry[] savedDatabase = new DatabaseRegistry[1];
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenAnswer(invocationOnMock -> {
            savedDatabase[0] = invocationOnMock.getArgument(0);
            return savedDatabase[0];
        });
        when(databaseRegistryDbaasRepository.findDatabaseRegistryById(any())).then(invocationOnMock -> Optional.of(savedDatabase[0]));
        when(dBaaService.createDatabase(eq(databaseCreateRequest), eq(TEST_NAMESPACE), any())).thenReturn(Optional.empty());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(NOT_FOUND.getStatusCode());

        final CreatedDatabaseV3 createdDatabase = getCreatedDatabaseV3Sample();
        physicalDatabase.setId(PHYSICAL_DATABASE_ID);
        when(physicalDatabasesService.getByAdapterId(any())).thenReturn(physicalDatabase);
        when(dBaaService.createDatabase(eq(databaseCreateRequest), eq(TEST_NAMESPACE), any())).thenReturn(Optional.of(createdDatabase));
        when(dBaaService.isAdapterExists(eq(databaseCreateRequest), eq(TEST_NAMESPACE), any())).thenReturn(true);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(CREATED.getStatusCode());
    }

    @Test
    void testCreateDatabaseAsync() throws JsonProcessingException {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(processConnectionPropertiesService);
        final DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample();
        when(declarativeConfigRepository.findFirstByClassifierAndType(any(), any())).thenReturn(Optional.empty());
        Mockito.when(databaseRolesService.getSupportedRoleFromRequest(any(DatabaseCreateRequestV3.class), any(), any())).thenReturn(Role.ADMIN.toString());
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenThrow(new ConstraintViolationException("constraint violation", new PSQLException("constraint violation", PSQLState.UNIQUE_VIOLATION), "database_registry_classifier_and_type_index"));
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(anyMap(), anyString())).thenReturn(Optional.of(Mockito.mock(DatabaseRegistry.class)));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .queryParam(ASYNC_PARAMETER, "true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(CONFLICT.getStatusCode());

        final DatabaseRegistry database = getDatabaseSample();
        when(dBaaService.findDatabaseByClassifierAndType(any(), any(), anyBoolean())).thenReturn(database.getDatabaseRegistry().get(0));
        when(dBaaService.isModifiedFields(any(), any())).thenReturn(true);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .queryParam(ASYNC_PARAMETER, "true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());
        // Is it intended that the same request tested twice?
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .queryParam(ASYNC_PARAMETER, "true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());

        when(dBaaService.detach(database)).thenReturn(database);
        when(dBaaService.isModifiedFields(any(), any())).thenReturn(false);
        DatabaseResponseV3 response = new DatabaseResponseV3SingleCP(database.getDatabaseRegistry().get(0), PHYSICAL_DATABASE_ID, Role.ADMIN.toString());
        when(dBaaService.processConnectionPropertiesV3(any(DatabaseRegistry.class), any())).thenReturn(response);
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .queryParam(ASYNC_PARAMETER, "true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(OK.getStatusCode())
                .body("name", is(database.getName()));

        reset(databaseRegistryDbaasRepository);

        DatabaseRegistry[] savedDatabase = new DatabaseRegistry[1];
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenAnswer(invocationOnMock -> {
            savedDatabase[0] = invocationOnMock.getArgument(0);
            return savedDatabase[0];
        });
        when(databaseRegistryDbaasRepository.findDatabaseRegistryById(any())).then(invocationOnMock -> Optional.of(savedDatabase[0]));
        when(dBaaService.createDatabase(eq(databaseCreateRequest), eq(TEST_NAMESPACE), any())).thenReturn(Optional.empty());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .queryParam(ASYNC_PARAMETER, "true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(NOT_FOUND.getStatusCode());

        final CreatedDatabaseV3 createdDatabase = getCreatedDatabaseV3Sample();
        physicalDatabase.setId(PHYSICAL_DATABASE_ID);
        when(physicalDatabasesService.getByAdapterId(any())).thenReturn(physicalDatabase);
        when(dBaaService.createDatabase(eq(databaseCreateRequest), eq(TEST_NAMESPACE), any())).thenReturn(Optional.of(createdDatabase));
        when(dBaaService.isAdapterExists(eq(databaseCreateRequest), eq(TEST_NAMESPACE), any())).thenReturn(true);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .queryParam(ASYNC_PARAMETER, "true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(ACCEPTED.getStatusCode());



    }

    @Test
    void testNamespaceInRequestAndClassifierIsDifferent() throws JsonProcessingException {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(processConnectionPropertiesService);
        DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample();
        Mockito.when(databaseRolesService.getSupportedRoleFromRequest(any(DatabaseCreateRequestV3.class), any(), any()))
                .thenReturn(Role.ADMIN.toString());
        when(declarativeConfigRepository.findFirstByClassifierAndType(any(), any())).thenReturn(Optional.empty());

        String namespace = "real-namespace";
        databaseCreateRequest.getClassifier().put("namespace", namespace);
        when(dBaaService.createDatabase(eq(databaseCreateRequest), eq(namespace), any()))
                .thenReturn(Optional.of(getCreatedDatabaseV3Sample()));
        when(dBaaService.isAdapterExists(eq(databaseCreateRequest), eq(namespace), any()))
                .thenReturn(true);
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(PHYSICAL_DATABASE_ID);
        when(physicalDatabasesService.getByAdapterId(any())).thenReturn(physicalDatabase);
        DatabaseRegistry[] savedDatabase = new DatabaseRegistry[1];
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenAnswer(invocationOnMock -> {
            savedDatabase[0] = invocationOnMock.getArgument(0);
            return savedDatabase[0];
        });
        when(databaseRegistryDbaasRepository.findDatabaseRegistryById(any())).then(invocationOnMock -> Optional.of(savedDatabase[0]));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(CREATED.getStatusCode())
                .body("classifier.namespace", is(namespace))
                .body("namespace", is(namespace));

        ArgumentCaptor<DatabaseRegistry> databaseCaptor = ArgumentCaptor.forClass(DatabaseRegistry.class);
        verify(databaseRegistryDbaasRepository).saveAnyTypeLogDb(databaseCaptor.capture());
        DatabaseRegistry savedDb = databaseCaptor.getValue();
        Assertions.assertEquals(namespace, savedDb.getNamespace());
        Assertions.assertEquals(namespace, savedDb.getClassifier().get("namespace"));
    }

    @Test
    void testDbTypeCannotBeEmptyOrNull() {
        Mockito.when(databaseRolesService.getSupportedRoleFromRequest(any(DatabaseCreateRequestV3.class), any(), any())).thenReturn(Role.ADMIN.toString());
        String body = "{\"classifier\":{\"microserviceName\":\"test_name\",\"scope\":\"service\",\"namespace\":\"test-namespace\"},\"originService\":\"test_name\"}";
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .when().put()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", is("Invalid DB Create request. request parameter 'type' can't be null or empty"));

        body = "{\"classifier\":{\"microserviceName\":\"test_name\",\"scope\":\"service\",\"namespace\":\"test-namespace\"},\"originService\":\"test_name\", \"type\":\"\"}";
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .when().put()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", is("Invalid DB Create request. request parameter 'type' can't be null or empty"));
    }

    @Test
    void testEmptyOriginServiceToCreateDatabase() throws JsonProcessingException {
        DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample();
        databaseCreateRequest.setOriginService(null);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());
    }

    @Test
    void testEmptyClassifierToCreateDatabase() throws JsonProcessingException {
        DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample(new HashMap<>());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());
    }

    @Test
    void testClassifierWithTenantScopeToCreateDatabase() throws JsonProcessingException {
        Map<String, Object> classifier = getSampleClassifier();
        classifier.put("scope", "tenant");
        DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample(classifier);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());
    }

    @Test
    void testClassifierWithWrongScopeToCreateDatabase() throws JsonProcessingException {
        Map<String, Object> classifier = getSampleClassifier();
        classifier.put("scope", "different");
        DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample(classifier);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());
    }

    @Test
    void testClassifierWithoutScopeToCreateDatabase() throws JsonProcessingException {
        Map<String, Object> classifier = getSampleClassifier();
        classifier.remove("scope");
        DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample(classifier);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());
    }

    @Test
    void testClassifierWithoutMicroserviceNameToCreateDatabase() throws JsonProcessingException {
        Map<String, Object> classifier = getSampleClassifier();
        classifier.remove("microserviceName");
        DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample(classifier);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());
    }

    @Test
    void testGetAllDatabases() {
        final DatabaseRegistry database = getDatabaseSample();
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE)).thenReturn(singletonList(database.getDatabaseRegistry().get(0)));
        when(dBaaService.getConnectionPropertiesService()).thenReturn(processConnectionPropertiesService);
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(PHYSICAL_DATABASE_ID);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().get(LIST_DATABASES_PATH)
                .then()
                .statusCode(OK.getStatusCode())
                .body("[0].name", is(database.getName()));
    }

    @Test
    void testGetAllDatabasesWithResources() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(processConnectionPropertiesService);
        final DatabaseRegistry database = getDatabaseSample();
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE)).thenReturn(singletonList(database.getDatabaseRegistry().get(0)));
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(PHYSICAL_DATABASE_ID);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .queryParam("withResources", "true")
                .when().get(LIST_DATABASES_PATH)
                .then()
                .statusCode(OK.getStatusCode())
                .body("[0].name", is(database.getName()))
                .body("[0].resources[0].name", is(database.getName()));
    }

    @Test
    void testGetAllDatabasesWithResourcesParamNotSpecify() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(processConnectionPropertiesService);
        final DatabaseRegistry database = getDatabaseSample();
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE)).thenReturn(singletonList(database.getDatabaseRegistry().get(0)));
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(PHYSICAL_DATABASE_ID);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .queryParam("withResources")
                .when().get(LIST_DATABASES_PATH)
                .then()
                .statusCode(OK.getStatusCode())
                .body("[0].name", is(database.getName()));
    }

    @Test
    void testGetAllDatabasesWithResourcesAndNullPhysicalDatabaseId() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(processConnectionPropertiesService);
        final DatabaseRegistry database = getDatabaseSample();
        database.setAdapterId("adapter-id");
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE)).thenReturn(singletonList(database.getDatabaseRegistry().get(0)));
        when(physicalDatabasesService.getByAdapterId("adapter-id")).thenReturn(new PhysicalDatabase());
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(PHYSICAL_DATABASE_ID);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .queryParam("withResources", "true")
                .when().get(LIST_DATABASES_PATH)
                .then()
                .statusCode(OK.getStatusCode())
                .body("[0].name", is(database.getName()))
                .body("[0].resources[0].name", is(database.getName()));
    }

    @Test
    void testGetDatabaseByClassifier() throws JsonProcessingException {
        final Map<String, Object> classifier = getSampleClassifier();
        ClassifierWithRolesRequest classifierWithRolesRequest = new ClassifierWithRolesRequest();
        classifierWithRolesRequest.setClassifier(classifier);
        classifierWithRolesRequest.setUserRole(Role.ADMIN.toString());
        classifierWithRolesRequest.setOriginService("test");
        final String testType = "test-type";
        Mockito.when(dBaaService.isValidClassifierV3(any())).thenCallRealMethod();
        doReturn(Role.ADMIN.toString()).when(databaseRolesService).getSupportedRoleFromRequest(any(ClassifierWithRolesRequest.class), any(), any());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(classifierWithRolesRequest))
                .when().post("/get-by-classifier/{type}", testType)
                .then()
                .statusCode(NOT_FOUND.getStatusCode());

        final DatabaseRegistry database = getDatabaseSample();
        DatabaseResponseV3 response = new DatabaseResponseV3ListCP(database.getDatabaseRegistry().get(0), PHYSICAL_DATABASE_ID);
        when(dBaaService.processConnectionPropertiesV3(any(DatabaseRegistry.class), any())).thenReturn(response);
        when(dBaaService.findDatabaseByClassifierAndType(classifier, testType, false)).thenReturn(database.getDatabaseRegistry().get(0));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(classifierWithRolesRequest))
                .when().post("/get-by-classifier/{type}", testType)
                .then()
                .statusCode(OK.getStatusCode())
                .body("name", is(database.getName()));
    }

    @Test
    void testEmptyOriginServiceGetDatabaseByClassifier() throws JsonProcessingException {
        ClassifierWithRolesRequest classifierWithRolesRequest = new ClassifierWithRolesRequest();
        classifierWithRolesRequest.setOriginService(null);
        final String testType = "test-type";
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, "another-namespace")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(classifierWithRolesRequest))
                .when().post("/get-by-classifier/{type}", testType)
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());
    }

    @Test
    void testEmptyRoleGetDatabaseByClassifier() throws JsonProcessingException {
        ClassifierWithRolesRequest classifierWithRolesRequest = new ClassifierWithRolesRequest();
        final Map<String, Object> classifier = getSampleClassifier();
        classifierWithRolesRequest.setClassifier(classifier);
        classifierWithRolesRequest.setOriginService("test");
        final String testType = "test-type";
        classifierWithRolesRequest.setUserRole(null);

        when(dBaaService.isValidClassifierV3(any())).thenCallRealMethod();

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(classifierWithRolesRequest))
                .when().post("/get-by-classifier/{type}", testType)
                .then()
                .statusCode(FORBIDDEN.getStatusCode());
    }

    @Test
    void testGetDatabasesStatus() {
        final DatabasesInfo databasesInfo = getDatabasesInfoSample();
        when(monitoringService.getDatabasesStatus()).thenReturn(databasesInfo);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().get("/statuses")
                .then()
                .statusCode(OK.getStatusCode())
                .body("global.name", is(databasesInfo.getGlobal().getName()));
    }

    @Test
    void testCanDeleteAllDatabasesInNamespaceWithV2() {
        final UUID id = UUID.randomUUID();
        final DatabaseRegistry database = getDatabaseSample();
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
        database.setId(id);
        database.setNamespace(TEST_NAMESPACE);
        List<DatabaseRegistry> listOfDbRegistry = database.getDatabaseRegistry();
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE)).thenReturn(listOfDbRegistry);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().delete("/deleteall")
                .then()
                .statusCode(OK.getStatusCode());

        verify(databaseRegistryDbaasRepository).findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE);
        verify(dBaaService, times(1)).deleteDatabasesAsync(TEST_NAMESPACE, listOfDbRegistry, true);
        verify(dBaaService).checkNamespaceAlreadyDropped(TEST_NAMESPACE, listOfDbRegistry);
        verifyNoMoreInteractions(dBaaService, databaseRegistryDbaasRepository);
    }

    @Test
    void testCanDeleteAllDatabasesInNamespaceBlueGreenActive() {
        final UUID id = UUID.randomUUID();
        final DatabaseRegistry database = getDatabaseSample();
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
        database.setId(id);
        database.setNamespace(TEST_NAMESPACE);

        Database db = database.getDatabase();
        DatabaseRegistry dbrIdle = getDatabaseSample();
        dbrIdle.setNamespace(TEST_NAMESPACE_IDLE);
        db.getDatabaseRegistry().add(dbrIdle);


        List<DatabaseRegistry> listOfDbRegistryActive = List.of(database);
        List<DatabaseRegistry> listDbrIdle = List.of(dbrIdle);

        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE)).thenReturn(listOfDbRegistryActive);
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE_IDLE)).thenReturn(listDbrIdle);

        BgDomain bgDomain = new BgDomain();
        BgNamespace activeBgNamespace = new BgNamespace();
        activeBgNamespace.setNamespace(TEST_NAMESPACE);
        activeBgNamespace.setState(ACTIVE_STATE);
        BgNamespace idleBgNamespace = new BgNamespace();
        idleBgNamespace.setNamespace(TEST_NAMESPACE_IDLE);
        idleBgNamespace.setState(IDLE_STATE);

        bgDomain.setNamespaces(Arrays.asList(activeBgNamespace, idleBgNamespace));
        when(blueGreenService.getBgDomainContains(TEST_NAMESPACE)).thenReturn(Optional.of(bgDomain));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().delete("/deleteall")
                .then()
                .statusCode(OK.getStatusCode());

        verify(databaseRegistryDbaasRepository).findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE_IDLE);
        verify(dBaaService, times(1)).deleteDatabasesAsync(TEST_NAMESPACE_IDLE, listDbrIdle, true);
        verify(dBaaService).checkNamespaceAlreadyDropped(TEST_NAMESPACE_IDLE, listDbrIdle);

        verify(databaseRegistryDbaasRepository).findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE);
        verify(dBaaService, times(1)).deleteDatabasesAsync(TEST_NAMESPACE, listOfDbRegistryActive, true);
        verify(dBaaService).checkNamespaceAlreadyDropped(TEST_NAMESPACE, listOfDbRegistryActive);
        verifyNoMoreInteractions(dBaaService, databaseRegistryDbaasRepository);
    }

    @Test
    void testDeleteDatabaseByClassifier() throws JsonProcessingException {
        final String type = "postgresql";
        final UUID id = UUID.randomUUID();
        TreeMap<String, Object> classifier = new TreeMap<>(getSampleClassifier());
        ClassifierWithRolesRequest classifierWithRolesRequest = new ClassifierWithRolesRequest();
        classifierWithRolesRequest.setClassifier(classifier);
        classifierWithRolesRequest.setUserRole(Role.ADMIN.toString());
        classifierWithRolesRequest.setOriginService("test");
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
        doReturn(Role.ADMIN.toString()).when(databaseRolesService).getSupportedRoleFromRequest(any(ClassifierWithRolesRequest.class), any(), any());
        String requestBody = objectMapper.writer().withDefaultPrettyPrinter().writeValueAsString(classifierWithRolesRequest);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .when().delete("/{type}", type)
                .then()
                .statusCode(OK.getStatusCode());

        final DatabaseRegistry database = getDatabaseSample();
        database.setId(id);
        database.setType(type);
        database.setClassifier(classifier);
        database.setNamespace(TEST_NAMESPACE);
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier, type)).thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, "another-namespace")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .when().delete("/{type}", type)
                .then()
                .statusCode(FORBIDDEN.getStatusCode());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .when().delete("/{type}", type)
                .then()
                .statusCode(OK.getStatusCode());

        database.setMarkedForDrop(false);

        verify(dBaaService, times(1)).dropDatabase(database);
        verify(databaseRegistryDbaasRepository, times(1)).deleteById(database.getDatabaseRegistry().get(0).getId());
        verify(databaseRegistryDbaasRepository, times(3)).getDatabaseByClassifierAndType(classifier, type);
        verifyNoMoreInteractions(dBaaService, databaseRegistryDbaasRepository);
    }

    @Test
    void testDeleteMarkedDatabaseById() throws JsonProcessingException {
        final UUID id = UUID.randomUUID();
        TreeMap<String, Object> classifier = new TreeMap<>(getSampleClassifier());
        ClassifierWithRolesRequest classifierWithRolesRequest = new ClassifierWithRolesRequest();
        classifierWithRolesRequest.setClassifier(classifier);
        classifierWithRolesRequest.setUserRole(Role.ADMIN.toString());
        classifierWithRolesRequest.setOriginService("test");

        String requestBody = objectMapper.writer().withDefaultPrettyPrinter().writeValueAsString(classifierWithRolesRequest);
        final String type = "postgresql";
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
        doReturn(Role.ADMIN.toString()).when(databaseRolesService).getSupportedRoleFromRequest(any(ClassifierWithRolesRequest.class), any(), any());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .when().delete("/{type}", type)
                .then()
                .statusCode(OK.getStatusCode());
        final DatabaseRegistry database = getDatabaseSample();
        database.setId(id);
        database.setType(type);
        database.setClassifier(classifier);
        database.setNamespace(TEST_NAMESPACE);
        database.getDatabaseRegistry().get(0).setNamespace(TEST_NAMESPACE);
        database.getDatabaseRegistry().get(0).setClassifier(classifier);
        database.setMarkedForDrop(true);
        doReturn(Optional.of(database.getDatabaseRegistry().get(0))).when(databaseRegistryDbaasRepository).getDatabaseByClassifierAndType(classifier, type);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, "another-namespace")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .when().delete("/{type}", type)
                .then()
                .statusCode(FORBIDDEN.getStatusCode());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .when().delete("/{type}", type)
                .then()
                .statusCode(OK.getStatusCode());

        verify(dBaaService, times(1)).dropDatabase(database);
        verify(databaseRegistryDbaasRepository, times(1)).deleteById(database.getDatabaseRegistry().get(0).getId());
        verify(databaseRegistryDbaasRepository, times(3)).getDatabaseByClassifierAndType(classifier, type);
        verifyNoMoreInteractions(dBaaService, databaseRegistryDbaasRepository);
    }

    @Test
    void testDeleteNotExistedDatabaseById() throws JsonProcessingException {
        final UUID id = UUID.randomUUID();
        TreeMap<String, Object> classifier = new TreeMap<>(getSampleClassifier());
        ClassifierWithRolesRequest classifierWithRolesRequest = new ClassifierWithRolesRequest();
        classifierWithRolesRequest.setClassifier(classifier);
        classifierWithRolesRequest.setUserRole(Role.ADMIN.toString());
        classifierWithRolesRequest.setOriginService("test");
        String requestBody = objectMapper.writer().withDefaultPrettyPrinter().writeValueAsString(classifierWithRolesRequest);
        final String type = "postgresql";
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
        doReturn(Role.ADMIN.toString()).when(databaseRolesService).getSupportedRoleFromRequest(any(ClassifierWithRolesRequest.class), any(), any());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .when().delete("/{type}", type)
                .then()
                .statusCode(OK.getStatusCode());

        final DatabaseRegistry database = getDatabaseSample();
        database.setId(id);
        database.setType(type);
        database.setClassifier(classifier);
        database.setNamespace(TEST_NAMESPACE);
        database.setMarkedForDrop(false);
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier, type)).thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, "another-namespace")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .when().delete("/{type}", type)
                .then()
                .statusCode(FORBIDDEN.getStatusCode());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .when().delete("/{type}", type)
                .then()
                .statusCode(OK.getStatusCode());

        final String anotherType = "anotherType";
        doReturn(Optional.empty()).when(databaseRegistryDbaasRepository).getDatabaseByClassifierAndType(classifier, anotherType);

        TreeMap<String, Object> anotherClassifier = new TreeMap<>(classifier);
        anotherClassifier.put("customKey", "customValue");
        ClassifierWithRolesRequest anotherClassifierWithRolesRequest = new ClassifierWithRolesRequest();
        anotherClassifierWithRolesRequest.setClassifier(anotherClassifier);
        anotherClassifierWithRolesRequest.setUserRole(Role.ADMIN.toString());
        anotherClassifierWithRolesRequest.setOriginService("test");
        String anotherRequestBody = objectMapper.writer().withDefaultPrettyPrinter().writeValueAsString(anotherClassifierWithRolesRequest);
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(anotherClassifier, type)).thenReturn(Optional.empty());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(anotherRequestBody)
                .when().delete("/{type}", type)
                .then()
                .statusCode(OK.getStatusCode());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .when().delete("/{type}", anotherType)
                .then()
                .statusCode(OK.getStatusCode());

        verify(dBaaService, times(1)).dropDatabase(database);
        verify(databaseRegistryDbaasRepository, times(1)).deleteById(database.getDatabaseRegistry().get(0).getId());
        verify(databaseRegistryDbaasRepository, times(3)).getDatabaseByClassifierAndType(classifier, type);
        verify(databaseRegistryDbaasRepository, times(1)).getDatabaseByClassifierAndType(classifier, anotherType);
        verify(databaseRegistryDbaasRepository, times(1)).getDatabaseByClassifierAndType(anotherClassifier, type);
        verifyNoMoreInteractions(dBaaService, databaseRegistryDbaasRepository);
    }

    @Test
    void testCannotDeleteByIdWhenProfileIsProd() throws JsonProcessingException {
        final String type = "postgresql";
        TreeMap<String, Object> classifier = new TreeMap<>(getSampleClassifier());
        String requestBody = objectMapper.writer().withDefaultPrettyPrinter().writeValueAsString(classifier);
        when(dbaaSHelper.isProductionMode()).thenReturn(true);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .when().delete("/{type}", type)
                .then()
                .statusCode(FORBIDDEN.getStatusCode());
    }

    @Test
    void testCannotDeleteDatabaseWithV3InProdMode() {
        List<DatabaseRegistry> databaseRegistryList = singletonList(new DatabaseRegistry());
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE)).thenReturn(databaseRegistryList);

        when(dbaaSHelper.isProductionMode()).thenReturn(true);
        final UUID id = UUID.randomUUID();
        final DatabaseRegistry database = getDatabaseSample();
        database.setId(id);
        database.setNamespace(TEST_NAMESPACE);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().delete("/deleteall")
                .then()
                .statusCode(FORBIDDEN.getStatusCode());
        verify(databaseRegistryDbaasRepository).findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE);
        verify(dBaaService).checkNamespaceAlreadyDropped(TEST_NAMESPACE, databaseRegistryList);
        verifyNoMoreInteractions(dBaaService, databaseRegistryDbaasRepository);
    }

    @Test
    void testDeleteAllWhenProfileIsProdAndListIsEmpty() {
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE)).thenReturn(Collections.emptyList());
        when(dBaaService.checkNamespaceAlreadyDropped(TEST_NAMESPACE, Collections.emptyList())).thenReturn(true);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().delete()
                .then()
                .statusCode(OK.getStatusCode())
                .body(is("namespace " + TEST_NAMESPACE + " doesn't contain any databases and namespace specific resources"));
    }

    @Test
    void testDeleteAllV3WhenProfileIsProdAndListIsEmpty() {
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE)).thenReturn(Collections.emptyList());
        when(dBaaService.checkNamespaceAlreadyDropped(TEST_NAMESPACE, Collections.emptyList())).thenReturn(true);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().delete("/deleteall")
                .then()
                .statusCode(OK.getStatusCode())
                .body(is("namespace " + TEST_NAMESPACE + " doesn't contain any databases and namespace specific resources"));
    }

    @Test
    void testDeleteAllV3WhenProfileIsProdAndConfigListIsEmpty() {
        when(dBaaService.checkNamespaceAlreadyDropped(TEST_NAMESPACE, Collections.emptyList())).thenReturn(true);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().delete("/deleteall")
                .then()
                .statusCode(OK.getStatusCode())
                .body(is("namespace " + TEST_NAMESPACE + " doesn't contain any databases and namespace specific resources"));
    }

    @Test
    void testDeleteNothingInComposite() throws Exception {
        when(compositeNamespaceService.isNamespaceInComposite(TEST_NAMESPACE)).thenReturn(false);
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE)).thenReturn(Collections.emptyList());
        when(dBaaService.checkNamespaceAlreadyDropped(TEST_NAMESPACE, Collections.emptyList())).thenReturn(true);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().delete()
                .then()
                .statusCode(OK.getStatusCode())
                .body(is("namespace " + TEST_NAMESPACE + " doesn't contain any databases and namespace specific resources"));
    }

    @Test
    void testDeleteOnlyComposite() throws Exception {
        when(compositeNamespaceService.isNamespaceInComposite(TEST_NAMESPACE)).thenReturn(true);
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE)).thenReturn(Collections.emptyList());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().delete()
                .then()
                .statusCode(OK.getStatusCode())
                .body(is("Successfully deleted 0 databases and namespace specific resources in " + TEST_NAMESPACE + " namespace"));
    }

    @Test
    void testDeleteAllInComposite() {
        when(compositeNamespaceService.isNamespaceInComposite(eq(TEST_NAMESPACE))).thenReturn(true);
        final DatabaseRegistry database = getDatabaseSample();
        List<DatabaseRegistry> dbForDeletion = singletonList(database.getDatabaseRegistry().get(0));
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(TEST_NAMESPACE)).thenReturn(dbForDeletion);
        when(dBaaService.deleteDatabasesAsync(eq(TEST_NAMESPACE), eq(dbForDeletion), anyBoolean())).thenReturn(1L);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().delete()
                .then()
                .statusCode(OK.getStatusCode())
                .body(is("Successfully deleted 1 databases and namespace specific resources in " + TEST_NAMESPACE + " namespace"));
    }

    @Test
    void testDeleteV3WhenProfileIsProdAndConfigListIsEmpty() {
        when(dBaaService.checkNamespaceAlreadyDropped(TEST_NAMESPACE, Collections.emptyList())).thenReturn(true);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().delete()
                .then()
                .statusCode(OK.getStatusCode())
                .body(is("namespace " + TEST_NAMESPACE + " doesn't contain any databases and namespace specific resources"));
    }


    @Test
    void saveExternalDatabaseSuccess() throws JsonProcessingException {
        ExternalDatabaseRequestV3 externalDatabaseRequest = getExternalDatabaseRequestObject();
        DatabaseRegistry database = externalDatabaseRequest.toDatabaseRegistry();
        ArgumentCaptor<DatabaseRegistry> databaseCaptor = ArgumentCaptor.forClass(DatabaseRegistry.class);

        when(dBaaService.saveExternalDatabase(databaseCaptor.capture())).thenReturn(database);
        when(dBaaService.processConnectionPropertiesV3(any()))
                .thenAnswer(i -> new DatabaseResponseV3ListCP((DatabaseRegistry) i.getArguments()[0], PHYSICAL_DATABASE_ID));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(externalDatabaseRequest))
                .when().put("/registration/externally_manageable")
                .then()
                .statusCode(CREATED.getStatusCode());
        assertDatabasesEqual(database, databaseCaptor.getValue());
    }

    @Test
    void saveExternalDatabaseExist() throws JsonProcessingException {
        ExternalDatabaseRequestV3 externalDatabaseRequest = getExternalDatabaseRequestObject();
        DatabaseRegistry database = externalDatabaseRequest.toDatabaseRegistry();
        ArgumentCaptor<DatabaseRegistry> databaseCaptor = ArgumentCaptor.forClass(DatabaseRegistry.class);
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(eq(database.getClassifier()), eq(database.getType())))
                .thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));
        when(dBaaService.processConnectionPropertiesV3(databaseCaptor.capture()))
                .thenAnswer(i -> new DatabaseResponseV3ListCP((DatabaseRegistry) i.getArguments()[0], PHYSICAL_DATABASE_ID));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(externalDatabaseRequest))
                .when().put("/registration/externally_manageable")
                .then()
                .statusCode(OK.getStatusCode());
        assertDatabasesRegistryEqual(database.getDatabaseRegistry().get(0), databaseCaptor.getValue());
    }

    @Test
    void updateConnectionPropertiesExternalDatabaseExist() throws JsonProcessingException {
        List<Map<String, Object>> oldConnectionProperties = new ArrayList<>();
        Map<String, Object> firstConnectionProperty = new HashMap<>();
        firstConnectionProperty.put(ROLE, Role.ADMIN.toString());
        firstConnectionProperty.put("username", "user1");
        firstConnectionProperty.put("password", "password1");
        oldConnectionProperties.add(firstConnectionProperty);
        ExternalDatabaseRequestV3 externalDatabaseRequest = getExternalDatabaseRequestObject();
        externalDatabaseRequest.setUpdateConnectionProperties(true);
        externalDatabaseRequest.setConnectionProperties(oldConnectionProperties);

        DatabaseRegistry database = externalDatabaseRequest.toDatabaseRegistry();

        Map<String, Object> newConnectionProperty = new HashMap<>();
        newConnectionProperty.put(ROLE, Role.ADMIN.toString());
        newConnectionProperty.put("username", "user2");
        newConnectionProperty.put("password", "password2");
        List<Map<String, Object>> newConnectionProperties = new ArrayList<>(oldConnectionProperties);
        newConnectionProperties.add(newConnectionProperty);
        externalDatabaseRequest.setConnectionProperties(newConnectionProperties);

        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(eq(database.getClassifier()), eq(database.getType())))
                .thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));
        ArgumentCaptor<DatabaseRegistry> saveDatabaseCaptor = ArgumentCaptor.forClass(DatabaseRegistry.class);
        when(dBaaService.saveExternalDatabase(saveDatabaseCaptor.capture())).thenAnswer(i -> i.getArguments()[0]);
        ArgumentCaptor<DatabaseRegistry> processDatabaseCaptor = ArgumentCaptor.forClass(DatabaseRegistry.class);
        when(dBaaService.processConnectionPropertiesV3(processDatabaseCaptor.capture()))
                .thenAnswer(i -> new DatabaseResponseV3ListCP((DatabaseRegistry) i.getArguments()[0], PHYSICAL_DATABASE_ID));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(externalDatabaseRequest))
                .when().put("/registration/externally_manageable")
                .then()
                .statusCode(CREATED.getStatusCode());
        assertDatabasesRegistryEqual(database.getDatabaseRegistry().get(0), saveDatabaseCaptor.getValue());
        assertDatabasesRegistryEqual(database.getDatabaseRegistry().get(0), processDatabaseCaptor.getValue());
    }

    @Test
    void updateWithEmptyConnectionProperties() throws JsonProcessingException {
        ExternalDatabaseRequestV3 externalDatabaseRequest = getExternalDatabaseRequestObject();
        externalDatabaseRequest.setUpdateConnectionProperties(true);

        DatabaseRegistry existingDatabaseRegistry = externalDatabaseRequest.toDatabaseRegistry();
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(eq(existingDatabaseRegistry.getClassifier()), eq(existingDatabaseRegistry.getType())))
                .thenReturn(Optional.of(existingDatabaseRegistry.getDatabaseRegistry().get(0)));

        externalDatabaseRequest.setConnectionProperties(new ArrayList<>());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(externalDatabaseRequest))
                .when().put("/registration/externally_manageable")
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .body("code", is(ErrorCodes.CORE_DBAAS_4025.getCode()))
                .body("reason", is(ErrorCodes.CORE_DBAAS_4025.getDetail()));
    }

    @Test
    void saveExternalDatabaseConflict() throws JsonProcessingException {
        ExternalDatabaseRequestV3 externalDatabaseRequest = getExternalDatabaseRequestObject();
        DatabaseRegistry database = externalDatabaseRequest.toDatabaseRegistry();
        DatabaseRegistry existingDatabase = externalDatabaseRequest.toDatabaseRegistry();
        existingDatabase.setExternallyManageable(false);
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(eq(database.getClassifier()), eq(database.getType())))
                .thenReturn(Optional.of(existingDatabase.getDatabaseRegistry().get(0)));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(externalDatabaseRequest))
                .when().put("/registration/externally_manageable")
                .then()
                .statusCode(CONFLICT.getStatusCode());
    }

    @Test
    void testSettingsUpdateWithCreateRequest() throws JsonProcessingException {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(processConnectionPropertiesService);
        final DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample();
        final CreatedDatabaseV3 createdDatabase = getCreatedDatabaseV3Sample();
        final String extensionName = "ext-value";
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(PHYSICAL_DATABASE_ID);
        when(physicalDatabasesService.getByAdapterId(any())).thenReturn(physicalDatabase);
        when(declarativeConfigRepository.findFirstByClassifierAndType(any(), any())).thenReturn(Optional.empty());

        when(databaseRolesService.getSupportedRoleFromRequest(any(DatabaseCreateRequestV3.class), any(), any())).thenReturn(Role.ADMIN.toString());

        DatabaseRegistry[] savedDatabase = new DatabaseRegistry[1];
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenAnswer(invocationOnMock -> {
            savedDatabase[0] = invocationOnMock.getArgument(0);
            return savedDatabase[0];
        });
        when(databaseRegistryDbaasRepository.findDatabaseRegistryById(any())).then(invocationOnMock -> Optional.of(savedDatabase[0]));

        when(dBaaService.createDatabase(eq(databaseCreateRequest), eq(TEST_NAMESPACE), any())).thenReturn(Optional.of(createdDatabase));
        when(dBaaService.isAdapterExists(eq(databaseCreateRequest), eq(TEST_NAMESPACE), any())).thenReturn(true);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(CREATED.getStatusCode())
                .body("settings", is(nullValue()));

        databaseCreateRequest.setSettings(
                Collections.singletonMap("pgExtension", Collections.singletonList(extensionName))
        );

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(CREATED.getStatusCode())
                .body("settings.pgExtension", is(Collections.singletonList(extensionName)));
    }

    @Test
    void testStatusWhenThereAreNoAdapter() throws JsonProcessingException {
        final DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample();
        Mockito.when(databaseRolesService.getSupportedRoleFromRequest(any(DatabaseCreateRequestV3.class), any(), any())).thenReturn(Role.ADMIN.toString());
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenThrow(new ConstraintViolationException("constraint violation", new PSQLException("constraint violation", PSQLState.UNIQUE_VIOLATION), "database_registry_classifier_and_type_index"));
        when(dBaaService.findDatabaseByClassifierAndType(any(), any(), anyBoolean())).thenReturn(getDatabaseSample().getDatabaseRegistry().get(0));
        when(declarativeConfigRepository.findFirstByClassifierAndType(any(), any())).thenReturn(Optional.empty());

        databaseCreateRequest.setSettings(
                Collections.singletonMap("pgExtension", Collections.singletonList("ext"))
        );
        when(logicalDbSettingsService.updateSettings(any(), any())).thenThrow(new UnregisteredPhysicalDatabaseException(AggregatedDatabaseAdministrationService.AggregatedDatabaseAdministrationServiceConst.NO_ADAPTER_MSG));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(databaseCreateRequest))
                .when().put()
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode());
    }

    private ExternalDatabaseRequestV3 getExternalDatabaseRequestObject() {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("microserviceName", "test");
        classifier.put("namespace", TEST_NAMESPACE);
        classifier.put("scope", "service");
        Map<String, Object> cp = new HashMap<>();
        cp.put(ROLE, Role.ADMIN.toString());
        return new ExternalDatabaseRequestV3(classifier, singletonList(cp), "tarantool", "external-db");
    }

    private DatabaseCreateRequestV3 getDatabaseCreateRequestSample(Map<String, Object> classifier) {
        DatabaseCreateRequestV3 request = new DatabaseCreateRequestV3(classifier, "test-type");
        return request;
    }

    private DatabaseCreateRequestV3 getDatabaseCreateRequestSample() {
        final Map<String, Object> classifier = getSampleClassifier();
        DatabaseCreateRequestV3 request = new DatabaseCreateRequestV3(classifier, "test-type");
        request.setOriginService("test_name");
        return request;
    }

    private Map<String, Object> getSampleClassifier() {
        final Map<String, Object> classifier = new HashMap<>();
        classifier.put("namespace", TEST_NAMESPACE);
        classifier.put("scope", "service");
        classifier.put("microserviceName", "test_name");
        return classifier;
    }

    private DatabaseRegistry getDatabaseSample() {
        final Database database = new Database();
        database.setName(TEST_NAME);
        HashMap<String, Object> cp = new HashMap<>();
        cp.put(ROLE, Role.ADMIN.toString());
        database.setConnectionProperties(singletonList(cp));
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setDatabase(database);
        database.setResources(Collections.singletonList(new DbResource("database", TEST_NAME)));
        ArrayList<DatabaseRegistry> dbrs = new ArrayList<>();
        dbrs.add(databaseRegistry);
        database.setDatabaseRegistry(dbrs);
        return databaseRegistry;
    }

    private CreatedDatabaseV3 getCreatedDatabaseV3Sample() {
        final CreatedDatabaseV3 createdDatabase = new CreatedDatabaseV3();
        createdDatabase.setName(TEST_NAME);
        createdDatabase.setConnectionProperties(singletonList(new HashMap<String, Object>() {{
            put(ROLE, Role.ADMIN.toString());
        }}));
        return createdDatabase;
    }

    private DatabasesInfo getDatabasesInfoSample() {
        final DatabasesInfoSegment databasesInfoSegment = new DatabasesInfoSegment("test-dbis", null, null, null);
        final DatabasesInfo databasesInfo = new DatabasesInfo(databasesInfoSegment, singletonList(databasesInfoSegment));
        return databasesInfo;
    }

    private void assertDatabasesEqual(DatabaseRegistry database, DatabaseRegistry capturedDatabase) {
        // reset id and date since they generated each time toDatabase() method is invoked and should be ignored
        Stream.of(database, capturedDatabase)
                .peek(db -> db.setId(null))
                .peek(db -> db.setTimeDbCreation(null))
                .peek(db -> db.getDatabase().setId(null))
                .peek(db -> db.getDatabase().setTimeDbCreation(null))
                .collect(Collectors.toList());
        Assertions.assertEquals(database, capturedDatabase);
    }

    private void assertDatabasesRegistryEqual(DatabaseRegistry database, DatabaseRegistry capturedDatabase) {
        // reset id and date since they generated each time toDatabase() method is invoked and should be ignored
        Stream.of(database, capturedDatabase)
                .peek(db -> db.setId(null))
                .peek(db -> db.setTimeDbCreation(null))
                .peek(db -> db.getDatabase().setId(null))
//                .peek(db -> db.getDatabaseRegistry().get(0).setDatabase(null))
                .peek(db -> db.getDatabase().setTimeDbCreation(null))
                .collect(Collectors.toList());
        Assertions.assertEquals(database, capturedDatabase);
    }
}
