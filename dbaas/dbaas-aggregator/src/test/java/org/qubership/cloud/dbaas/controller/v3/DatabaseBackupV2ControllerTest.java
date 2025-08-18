package org.qubership.cloud.dbaas.controller.v3;

import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.dto.backupV2.*;
import org.qubership.cloud.dbaas.entity.pg.backupV2.ExternalDatabaseStrategy;
import org.qubership.cloud.dbaas.entity.pg.backupV2.Status;
import org.qubership.cloud.dbaas.exceptions.BackupNotFoundException;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.service.DbBackupV2Service;
import org.qubership.cloud.dbaas.utils.DigestUtil;

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

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .body(backupRequest)
                .when().post("/operation/backup")
                .then()
                .statusCode(OK.getStatusCode());

        verify(dbBackupV2Service, times(1)).backup(backupRequest);
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

        verify(dbBackupV2Service, times(0)).backup(any());
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
    void getBackupMetadata() {
        String backupName = "backupName";
        String storageName = "storageName";
        SortedMap<String, Object> sortedMap = new TreeMap<>();
        sortedMap.put("key", "value");


        BackupDatabaseResponse backupDatabaseResponse = new BackupDatabaseResponse(
                "db1",
                List.of(sortedMap),
                Map.of("settings-key", Map.of("field1", "value1", "field2", 123)),
                List.of(BackupDatabaseResponse.User.builder()
                        .role("role")
                        .name("name")
                        .build()),
                Map.of("key", "value"),
                true
        );

        LogicalBackupStatusResponse logicalBackupStatusResponse = new LogicalBackupStatusResponse(
                Status.COMPLETED,
                null,
                null,
                null,
                List.of(new LogicalBackupStatusResponse.Database(
                        "db1",
                        Status.COMPLETED,
                        1,
                        "duration",
                        "path",
                        null))
        );

        LogicalBackupResponse logicalBackupResponse = new LogicalBackupResponse(
                "logicalBackupName",
                "adapterId",
                "type",
                logicalBackupStatusResponse,
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

        BackupResponse backupResponse = new BackupResponse();
        backupResponse.setBackupName(backupName);
        backupResponse.setLogicalBackups(List.of(logicalBackupResponse));
        backupResponse.setStorageName(storageName);
        backupResponse.setStatus(backupStatusResponse);
        backupResponse.setBlobPath("blobPath");
        backupResponse.setIgnoreNotBackupableDatabases(false);
        backupResponse.setFilterCriteria(filterCriteria);
        backupResponse.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);

        when(dbBackupV2Service.getBackupMetadata(backupName))
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
                .body("storageName", equalTo(storageName))
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
    void uploadMetadata_DigestHeaderAndBodyNotEqual(){
        BackupResponse backupResponse = new BackupResponse();
        backupResponse.setBackupName("backupName");
        backupResponse.setBlobPath("path");
        backupResponse.setStorageName("storageName");
        backupResponse.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .header("Digest", "sha-256=abc")
                .body(backupResponse)
                .when().post("/operation/uploadMetadata")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", equalTo("Digest header mismatch."))
                .extract().response().prettyPrint();
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
}
