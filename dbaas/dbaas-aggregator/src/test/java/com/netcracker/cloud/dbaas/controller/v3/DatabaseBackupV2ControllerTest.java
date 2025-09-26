package com.netcracker.cloud.dbaas.controller.v3;

import com.netcracker.cloud.dbaas.dto.Source;
import com.netcracker.cloud.dbaas.dto.backupV2.*;
import com.netcracker.cloud.dbaas.enums.ExternalDatabaseStrategy;
import com.netcracker.cloud.dbaas.enums.Status;
import com.netcracker.cloud.dbaas.exceptions.BackupAlreadyExistsException;
import com.netcracker.cloud.dbaas.exceptions.BackupExecutionException;
import com.netcracker.cloud.dbaas.exceptions.BackupNotFoundException;
import com.netcracker.cloud.dbaas.exceptions.DatabaseBackupNotSupportedException;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.service.DbBackupV2Service;
import com.netcracker.cloud.dbaas.utils.DigestUtil;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.any;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(DatabaseBackupV2Controller.class)
@Slf4j
class DatabaseBackupV2ControllerTest {

    @InjectMock
    DbBackupV2Service dbBackupV2Service;

    @Test
    void initiateBackup_dtoFilledCorrectly_shouldStartBackup() {
        String namespace = "test-namespace";
        String backupName = "backup-123";

        BackupRequest backupRequest = createBackupRequest(namespace, backupName);

        BackupResponse backupResponse = new BackupResponse();
        backupResponse.setBackupName(backupName);
        backupResponse.setStorageName("storageName");

        BackupOperationResponse response = new BackupOperationResponse(
                backupName,
                backupResponse
        );

        when(dbBackupV2Service.backup(backupRequest, false))
                .thenReturn(response);

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .body(backupRequest)
                .when().post("/operation/backup")
                .then()
                .statusCode(OK.getStatusCode())
                .body("backupName", equalTo(backupName))
                .body("dryRun.backupName", equalTo(backupName))
                .body("dryRun.storageName", equalTo(backupResponse.getStorageName()));

        verify(dbBackupV2Service, times(1)).backup(backupRequest, false);
    }

    @Test
    void initiateBackup_invalidDto() {
        BackupRequest invalidBackupRequest = new BackupRequest();
        invalidBackupRequest.setBlobPath("BlobPath");
        invalidBackupRequest.setStorageName("storage name");
        invalidBackupRequest.setIgnoreNotBackupableDatabases(false);

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .body(invalidBackupRequest)
                .when().post("/operation/backup")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", allOf(
                        containsString("backupName: must not be blank"),
                        containsString("externalDatabaseStrategy: must not be null")
                ));

        verify(dbBackupV2Service, times(0)).backup(any(), anyBoolean());
    }

    @Test
    void initiateBackup_databasesCannotBackup_ignoreNotBackupableDatabaseFalse() {
        String namespace = "test-namespace";
        String backupName = "backup-123";

        BackupRequest backupRequest = createBackupRequest(namespace, backupName);

        List<String> dbNames = List.of("db1", "db2");
        doThrow(new DatabaseBackupNotSupportedException(
                "Backup operation unsupported for databases: " + dbNames,
                Source.builder().parameter("ignoreNotBackupableDatabases").build()))
                .when(dbBackupV2Service).backup(backupRequest, false);

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .body(backupRequest)
                .when().post("/operation/backup")
                .then()
                .statusCode(412)
                .body("reason", equalTo("Backup not allowed"))
                .body("message", equalTo("The backup request can`t be processed. Backup operation unsupported for databases: " + dbNames));
        verify(dbBackupV2Service, times(1)).backup(backupRequest, false);
    }

    @Test
    void initiateBackup_backupAlreadyExists() {
        String namespace = "test-namespace";
        String backupName = "backup-123";

        BackupRequest backupRequest = createBackupRequest(namespace, backupName);


        doThrow(new BackupAlreadyExistsException(backupName, Source.builder().build()))
                .when(dbBackupV2Service).backup(backupRequest, false);

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .body(backupRequest)
                .when().post("/operation/backup")
                .then()
                .statusCode(CONFLICT.getStatusCode())
                .body("reason", equalTo("Backup already exists"))
                .body("message", equalTo(String.format("Backup with name '%s' already exists", backupName)))
                .extract().response().prettyPrint();
        verify(dbBackupV2Service, times(1)).backup(backupRequest, false);
    }

    @Test
    void getBackupStatus_validBackupNameCase() {
        String backupName = "test-backup-name";

        BackupStatusResponse expected = new BackupStatusResponse();
        expected.setStatus(Status.COMPLETED);
        expected.setTotal(0);
        expected.setCompleted(0);
        expected.setErrorMessage(null);
        expected.setSize(0L);

        when(dbBackupV2Service.getCurrentStatus(backupName))
                .thenReturn(expected);

        BackupStatusResponse result = given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .pathParam("backupName", backupName)
                .when().get("/backup/{backupName}/status")
                .then()
                .statusCode(OK.getStatusCode())
                .extract().as(BackupStatusResponse.class);

        Assertions.assertEquals(expected, result);

        verify(dbBackupV2Service, times(1)).getCurrentStatus(backupName);
    }

    @Test
    void getBackupStatus_invalidBackupName() {
        String backupName = " ";

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .pathParam("backupName", backupName)
                .when().get("/backup/{backupName}/status")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", equalTo("backupName: must not be blank"));
    }

    @Test
    void getBackupStatus_backupNotExist() {
        String backupName = "notExistBackupName";

        Mockito.doThrow(new BackupNotFoundException(backupName, Source.builder().build()))
                .when(dbBackupV2Service).getCurrentStatus(backupName);

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .pathParam("backupName", backupName)
                .when().get("/backup/{backupName}/status")
                .then()
                .statusCode(NOT_FOUND.getStatusCode())
                .body("message", equalTo(String.format("No backup with id: '%s' is found", backupName)));
    }

    @Test
    void getBackup() {
        String backupName = "backupName";
        BackupResponse backupResponse = createBackupResponse(backupName);

        when(dbBackupV2Service.getBackup(backupName))
                .thenReturn(backupResponse);

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .pathParam("backupName", backupName)
                .when().post("/backup/{backupName}")
                .then()
                .statusCode(OK.getStatusCode())
                .body("backupName", equalTo(backupName))
                .body("storageName", equalTo(backupResponse.getStorageName()));
    }

    @Test
    void getBackupMetadata() {
        String backupName = "backupName";
        BackupResponse backupResponse = createBackupResponse(backupName);

        when(dbBackupV2Service.getBackup(backupName))
                .thenReturn(backupResponse);

        String expectedDigest = DigestUtil.calculateDigest(backupResponse);

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .pathParam("backupName", backupName)
                .when().get("/backup/{backupName}/metadata")
                .then()
                .statusCode(OK.getStatusCode())
                .header("Digest", equalTo(expectedDigest))
                .body("backupName", equalTo(backupName))
                .body("storageName", equalTo("storageName"))
                .extract().response().prettyPrint();
    }

    @Test
    void uploadMetadata_invalidRequest() {
        BackupResponse backupResponse = new BackupResponse();

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .body(backupResponse)
                .when().post("/operation/uploadMetadata")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", allOf(
                        containsString("digestHeader: must not be null"),
                        containsString("storageName: must not be blank"),
                        containsString("externalDatabaseStrategy: must not be null"),
                        containsString("backupName: must not be blank"),
                        containsString("blobPath: must not be blank"))
                );

    }

    @Test
    void uploadMetadata_DigestHeaderAndBodyNotEqual() {
        BackupResponse backupResponse = new BackupResponse();
        backupResponse.setBackupName("backupName");
        backupResponse.setBlobPath("path");
        backupResponse.setStorageName("storageName");
        backupResponse.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .header("Digest", "SHA-256=abc")
                .body(backupResponse)
                .when().post("/operation/uploadMetadata")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", equalTo("Digest header mismatch."))
                .extract().response().prettyPrint();
    }

    @Test
    void deleteBackup_shouldReturn204() {
        String backupName = "backup123";

        doNothing().when(dbBackupV2Service).deleteBackup(backupName);

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .when().delete("/backup/" + backupName)
                .then()
                .statusCode(204); // No Content

        verify(dbBackupV2Service, times(1)).deleteBackup(backupName);
    }

    @Test
    void deleteBackup_shouldReturn500_onException() {
        String backupName = "backup123";

        String aggregatedError = String.format(
                "Not all backups were deleted successfully in backup %s, failed adapters: %s",
                backupName, Map.of("adapterId1", "{\"error\":\"Internal server error\",\"requestId\":\"req_1234567890\"}")
        );

        doThrow(new BackupExecutionException(
                URI.create("deleteBackup"),
                aggregatedError,
                new Throwable()
        )).when(dbBackupV2Service).deleteBackup(backupName);

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .when().delete("/backup/" + backupName)
                .then()
                .statusCode(500); // Internal Server Error
    }

    public static BackupRequest createBackupRequest(String namespace, String backupName) {
        Filter filter = new Filter();
        filter.setNamespace(List.of(namespace));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setFilter(List.of(filter));

        BackupRequest dto = new BackupRequest();
        dto.setFilterCriteria(filterCriteria);
        dto.setBackupName(backupName);
        dto.setExternalDatabaseStrategy(ExternalDatabaseStrategy.FAIL);
        dto.setBlobPath("path");
        dto.setIgnoreNotBackupableDatabases(true);
        dto.setStorageName("e");
        return dto;
    }

    private BackupResponse createBackupResponse(String backupName) {
        String storageName = "storageName";
        SortedMap<String, Object> sortedMap = new TreeMap<>();
        sortedMap.put("key", "value");

        BackupDatabaseResponse backupDatabaseResponse = new BackupDatabaseResponse(
                "backup-database",
                List.of(sortedMap),
                Map.of("settings-key", "settings-value"),
                List.of(BackupDatabaseResponse.User.builder()
                        .name("name")
                        .role("role")
                        .build()),
                Map.of("key", "value"),
                true,
                Status.COMPLETED,
                1,
                1,
                "path",
                null,
                LocalDateTime.now()
        );

        LogicalBackupResponse logicalBackupResponse = new LogicalBackupResponse(
                "logicalBackupName",
                "adapterID",
                "type",
                Status.COMPLETED,
                null,
                null,
                null,
                List.of(backupDatabaseResponse)
        );

        BackupStatusResponse backupStatusResponse = new BackupStatusResponse();
        backupStatusResponse.setStatus(Status.COMPLETED);
        backupStatusResponse.setErrorMessage(null);
        backupStatusResponse.setTotal(1);
        backupStatusResponse.setCompleted(1);
        backupStatusResponse.setSize(1L);

        Filter filter = new Filter();
        filter.setNamespace(List.of("namespace"));

        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setFilter(List.of(filter));

        SortedMap<String, Object> map = new TreeMap<>();
        map.put("key", "value");

        BackupExternalDatabaseResponse backupExternalDatabase = new BackupExternalDatabaseResponse();
        backupExternalDatabase.setName("Name");
        backupExternalDatabase.setType("postgresql");
        backupExternalDatabase.setClassifiers(List.of(map));

        BackupResponse backupResponse = new BackupResponse();
        backupResponse.setBackupName(backupName);
        backupResponse.setLogicalBackups(List.of(logicalBackupResponse));
        backupResponse.setStorageName(storageName);
        backupResponse.setStatus(Status.COMPLETED);
        backupResponse.setTotal(1);
        backupResponse.setCompleted(1);
        backupResponse.setSize(1L);
        backupResponse.setErrorMessage(null);
        backupResponse.setBlobPath("blobPath");
        backupResponse.setIgnoreNotBackupableDatabases(false);
        backupResponse.setFilterCriteria(filterCriteria);
        backupResponse.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);
        backupResponse.setExternalDatabases(List.of(backupExternalDatabase));

        return backupResponse;
    }
}
