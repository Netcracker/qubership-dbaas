package org.qubership.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.backup.NamespaceBackupDeletion;
import org.qubership.cloud.dbaas.dto.backup.Status;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceRestoration;
import org.qubership.cloud.dbaas.exceptions.NamespaceBackupDeletionFailedException;
import org.qubership.cloud.dbaas.exceptions.NamespaceRestorationFailedException;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.BackupsDbaasRepository;
import org.qubership.cloud.dbaas.service.*;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.qubership.cloud.core.error.rest.tmf.TmfErrorResponse.TYPE_V1_0;
import static org.qubership.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;
import static org.qubership.cloud.dbaas.exceptions.ErrorCodes.CORE_DBAAS_2000;
import static org.qubership.cloud.dbaas.exceptions.ErrorCodes.CORE_DBAAS_4014;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(AggregatedBackupAdministrationControllerV3.class)
class AggregatedBackupAdministrationControllerV3Test {

    private static final String ILLEGAL_ARGUMENT_EXCEPTION_TEXT = "Incorrect adapter identifier in backup";
    private static final String TEST_NAMESPACE = "test-namespace";
    private static final UUID TEST_UUID = UUID.randomUUID();
    private static final boolean ALLOW_EVICTION = true;

    @InjectMock
    BackupsDbaasRepository backupsDbaasRepository;
    @InjectMock
    AsyncOperations asyncOperations;
    @InjectMock
    DBBackupsService dbBackupsService;

    @Inject
    AggregatedBackupAdministrationControllerV3 aggregatedBackupAdministrationControllerV3;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testGetAllBackupsInNamespace() {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();

        when(backupsDbaasRepository.findByNamespace(TEST_NAMESPACE)).thenReturn(Collections.singletonList(namespaceBackup));

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().get()
                .then()
                .statusCode(OK.getStatusCode())
                .body("[0].id", equalTo(TEST_UUID.toString()));
        verify(backupsDbaasRepository).findByNamespace(TEST_NAMESPACE);
        verifyNoMoreInteractions(backupsDbaasRepository);
    }

    @Test
    void testRestoreBackupInNamespace() throws NamespaceRestorationFailedException, IllegalAccessException {
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().post("/{backupId}/restorations", TEST_UUID)
                .then()
                .statusCode(NOT_FOUND.getStatusCode());

        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        when(backupsDbaasRepository.findById(TEST_UUID)).thenReturn(Optional.of(namespaceBackup));

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, "another-namespace")
                .when().post("/{backupId}/restorations", TEST_UUID)
                .then()
                .statusCode(FORBIDDEN.getStatusCode());

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().post("/{backupId}/restorations", TEST_UUID)
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());

        namespaceBackup.setStatus(NamespaceBackup.Status.ACTIVE);
        when(backupsDbaasRepository.findById(TEST_UUID)).thenReturn(Optional.of(namespaceBackup));

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().post("/{backupId}/restorations", TEST_UUID)
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());

        when(dbBackupsService.validateBackup(any())).thenReturn(true);
        when(asyncOperations.getBackupPool()).thenReturn(new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()));
        final NamespaceRestoration namespaceRestoration = new NamespaceRestoration();
        namespaceRestoration.setId(UUID.randomUUID());
        when(dbBackupsService.restore(any(), any(), any(), eq(false), any())).thenReturn(namespaceRestoration);
        aggregatedBackupAdministrationControllerV3.awaitOperationSeconds = 0;
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().post("/{backupId}/restorations", TEST_UUID)
                .then()
                .statusCode(ACCEPTED.getStatusCode());

        aggregatedBackupAdministrationControllerV3.awaitOperationSeconds = 10;
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().post("/{backupId}/restorations", TEST_UUID)
                .then()
                .statusCode(OK.getStatusCode())
                .body("id", is(namespaceRestoration.getId().toString()));
    }

    @Test
    void testValidateBackupInNamespace() {
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().get("/{backupId}/validate", TEST_UUID)
                .then()
                .statusCode(NOT_FOUND.getStatusCode());

        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        when(backupsDbaasRepository.findById(TEST_UUID)).thenReturn(Optional.of(namespaceBackup));
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, "another-namespace")
                .when().get("/{backupId}/validate", TEST_UUID)
                .then()
                .statusCode(FORBIDDEN.getStatusCode());

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().get("/{backupId}/validate", TEST_UUID)
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode());

        namespaceBackup.setStatus(NamespaceBackup.Status.ACTIVE);
        when(backupsDbaasRepository.findById(TEST_UUID)).thenReturn(Optional.of(namespaceBackup));
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().get("/{backupId}/validate", TEST_UUID)
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode());

        when(dbBackupsService.validateBackup(any())).thenReturn(true);
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().get("/{backupId}/validate", TEST_UUID)
                .then()
                .statusCode(OK.getStatusCode());
    }

    @Test
    void testGetBackupInNamespace() throws Exception {
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().get("/{backupId}", TEST_UUID)
                .then()
                .statusCode(NOT_FOUND.getStatusCode());

        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        when(backupsDbaasRepository.findById(TEST_UUID)).thenReturn(Optional.of(namespaceBackup));

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, "another-namespace")
                .when().get("/{backupId}", TEST_UUID)
                .then()
                .statusCode(FORBIDDEN.getStatusCode());

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().get("/{backupId}", TEST_UUID)
                .then()
                .statusCode(OK.getStatusCode())
                .body("id", is(namespaceBackup.getId().toString()));
    }

    @Test
    void testAddBackupInNamespace() throws JsonProcessingException {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        String contentType = MediaType.APPLICATION_JSON_TYPE.withCharset("UTF-8").toString();
        given().config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig().encodeContentTypeAs(contentType, ContentType.JSON)))
                .auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, "another-namespace")
                .when()
                .contentType(contentType)
                .body(objectMapper.writeValueAsString(namespaceBackup))
                .put("/{backupId}", TEST_UUID)
                .then()
                .statusCode(FORBIDDEN.getStatusCode());

        given().config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig().encodeContentTypeAs(contentType, ContentType.JSON)))
                .auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when()
                .contentType(contentType)
                .body(objectMapper.writeValueAsString(namespaceBackup))
                .put("/{backupId}", TEST_UUID)
                .then()
                .statusCode(OK.getStatusCode());
        verify(backupsDbaasRepository, times(1)).save(namespaceBackup);
        verifyNoMoreInteractions(backupsDbaasRepository);
    }

    @Test
    void testCollectBackupInNamespace() throws Exception {
        when(asyncOperations.getBackupPool()).thenReturn(new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()));
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        when(dbBackupsService.collectBackup(eq(TEST_NAMESPACE), any(), eq(ALLOW_EVICTION))).thenReturn(namespaceBackup);
        aggregatedBackupAdministrationControllerV3.awaitOperationSeconds = 0;
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when()
                .post("/collect")
                .then()
                .statusCode(ACCEPTED.getStatusCode());

        aggregatedBackupAdministrationControllerV3.awaitOperationSeconds = 10;
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when()
                .post("/collect")
                .then()
                .statusCode(CREATED.getStatusCode());
    }

    @Test
    void testCollectBackupInNamespaceWithoutIgnoreNotBackupableDatabases() throws Exception {
        DbaasAdapter adapterOne = new DbaasAdapterRESTClientV2("address-of-redis-adapter-1", "redis", null, "redis-adapter-1", mock(AdapterActionTrackerClient.class));
        DbaasAdapter adapterTwo = new DbaasAdapterRESTClientV2("address-of-redis-adapter-2", "redis", null, "redis-adapter-2", mock(AdapterActionTrackerClient.class));
        when(dbBackupsService.checkAdaptersOnBackupOperation(TEST_NAMESPACE)).thenReturn(Arrays.asList(adapterOne, adapterTwo));
        when(dbBackupsService.getDatabasesForBackup(TEST_NAMESPACE)).thenReturn(Arrays.asList(
                createDatabase("redis-db-1", "redis-adapter-1", false, false),
                createDatabase("redis-db-2", "redis-adapter-2", false, false),
                createDatabase("redis-db-3", "redis-adapter-2", false, false),
                createDatabase("mongo-db-1", "mongo-adapter", false, false)
        ));
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when()
                .post("/collect")
                .then()
                .statusCode(NOT_IMPLEMENTED.getStatusCode())
                .body(containsString("1 lbdbs in adapter with id=redis-adapter-1 and address=address-of-redis-adapter-1, 2 lbdbs in adapter with id=redis-adapter-2 and address=address-of-redis-adapter-2"));
        verify(dbBackupsService, times(1)).checkAdaptersOnBackupOperation(TEST_NAMESPACE);
    }

    @Test
    void testCollectBackupInNamespaceWithIgnoreNotBackupableDatabases() throws IllegalAccessException {
        when(asyncOperations.getBackupPool()).thenReturn(new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()));
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        when(dbBackupsService.collectBackup(eq(TEST_NAMESPACE), any(), eq(ALLOW_EVICTION))).thenReturn(namespaceBackup);
        aggregatedBackupAdministrationControllerV3.awaitOperationSeconds = 10;
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .queryParam("ignoreNotBackupableDatabases", "true")
                .when()
                .post("/collect")
                .then()
                .statusCode(CREATED.getStatusCode());
        verify(dbBackupsService, times(0)).checkAdaptersOnBackupOperation(TEST_NAMESPACE);
    }

    @Test
    void testDeleteBackupInNamespaceWithBackupNotFound() throws Exception {
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when()
                .delete("/{backupId}", TEST_UUID)
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    void testDeleteBackupInNamespaceForbiddenWithWrongNamespace() throws Exception {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        when(backupsDbaasRepository.findById(TEST_UUID)).thenReturn(Optional.of(namespaceBackup));
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, "another-namespace")
                .when()
                .delete("/{backupId}", TEST_UUID)
                .then()
                .statusCode(FORBIDDEN.getStatusCode());
    }

    @Test
    void testDeleteBackupInNamespaceWithProceedingStatus() throws Exception {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        namespaceBackup.setStatus(NamespaceBackup.Status.PROCEEDING);
        when(backupsDbaasRepository.findById(TEST_UUID)).thenReturn(Optional.of(namespaceBackup));

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when()
                .delete("/{backupId}", TEST_UUID)
                .then()
                .statusCode(FORBIDDEN.getStatusCode());
    }

    @Test
    void testDeleteBackupInNamespaceWithSuccessStatus() throws Exception {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        namespaceBackup.setStatus(NamespaceBackup.Status.ACTIVE);
        when(backupsDbaasRepository.findById(TEST_UUID)).thenReturn(Optional.of(namespaceBackup));

        final NamespaceBackupDeletion backupDeletion = new NamespaceBackupDeletion();
        backupDeletion.setStatus(Status.SUCCESS);
        when(dbBackupsService.deleteBackup(any())).thenReturn(backupDeletion);

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when()
                .delete("/{backupId}", TEST_UUID)
                .then()
                .statusCode(OK.getStatusCode())
                .body("status", is(backupDeletion.getStatus().toString()));
    }

    @Test
    void testDeleteBackupInNamespaceWithIllegalArgumentException() throws Exception {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        namespaceBackup.setStatus(NamespaceBackup.Status.ACTIVE);
        when(backupsDbaasRepository.findById(TEST_UUID)).thenReturn(Optional.of(namespaceBackup));

        when(dbBackupsService.deleteBackup(any())).thenThrow(new IllegalArgumentException(ILLEGAL_ARGUMENT_EXCEPTION_TEXT));

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when()
                .delete("/{backupId}", TEST_UUID)
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .body("code", is(CORE_DBAAS_2000.getCode()))
                .body("reason", is(CORE_DBAAS_2000.getTitle()))
                .body("message", is(ILLEGAL_ARGUMENT_EXCEPTION_TEXT))
                .body("status", is(String.valueOf(INTERNAL_SERVER_ERROR.getStatusCode())))
                .body("@type", is(TYPE_V1_0));
    }

    @Test
    void testDeleteBackupInNamespaceWithNamespaceBackupDeletionFailedException() throws Exception {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        namespaceBackup.setStatus(NamespaceBackup.Status.ACTIVE);
        when(backupsDbaasRepository.findById(TEST_UUID)).thenReturn(Optional.of(namespaceBackup));

        when(dbBackupsService.deleteBackup(any())).thenThrow(new NamespaceBackupDeletionFailedException(TEST_UUID, 1L, namespaceBackup));

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when()
                .delete("/{backupId}", TEST_UUID)
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .body("code", is(CORE_DBAAS_4014.getCode()))
                .body("reason", is(CORE_DBAAS_4014.getTitle()))
                .body("message", is(CORE_DBAAS_4014.getDetail(TEST_UUID, 1L)))
                .body("status", is(String.valueOf(INTERNAL_SERVER_ERROR.getStatusCode())))
                .body("@type", is(TYPE_V1_0));
    }

    private NamespaceBackup getNamespaceBackupSample() {
        return new NamespaceBackup(TEST_UUID, TEST_NAMESPACE, Collections.emptyList(), Collections.emptyList());
    }

    private DatabaseRegistry createDatabase(String databaseName, String adapterId, Boolean isMarkedForDrop, Boolean isBackupDisabled) {
        Database db = new Database();
        DatabaseRegistry dbRegistry = new DatabaseRegistry();
        dbRegistry.setDatabase(db);
        dbRegistry.setAdapterId(adapterId);
        dbRegistry.setName(databaseName);
        dbRegistry.setMarkedForDrop(isMarkedForDrop);
        dbRegistry.setBackupDisabled(isBackupDisabled);
        db.setDatabaseRegistry(List.of(dbRegistry));

        return dbRegistry;
    }
}

