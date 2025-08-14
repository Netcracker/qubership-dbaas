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
import org.qubership.cloud.dbaas.dto.backupV2.BackupRequest;
import org.qubership.cloud.dbaas.dto.backupV2.ExternalDatabaseStrategyDto;
import org.qubership.cloud.dbaas.dto.backupV2.Filter;
import org.qubership.cloud.dbaas.dto.backupV2.FilterCriteria;
import org.qubership.cloud.dbaas.entity.pg.backupV2.*;
import org.qubership.cloud.dbaas.exceptions.BackupNotFoundException;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.service.DbBackupV2Service;

import java.util.*;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.*;

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
    //TODO write test for bad cases for initiateBackup method

    @Test
    void getBackupStatus_validBackupNameCase() {
        String backupName = "test-backup-name";

        Status expectedResult = Status.COMPLETED;

        BackupStatus expected = BackupStatus.builder()
                .status(expectedResult)
                .total(0)
                .completed(0)
                .errorMessage(null)
                .size(0L)
                .build();

        when(dbBackupV2Service.getCurrentStatus(backupName))
                .thenReturn(expected);

        BackupStatus result = given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .pathParam("backupName", backupName)
                .when().get("/backup/{backupName}/status")
                .then()
                .statusCode(OK.getStatusCode())
                .extract().as(BackupStatus.class);

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
                .body("message", equalTo("Invalid database backup request. backup name null or blank."));

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
        SortedMap<String, Object> sortedMap = new TreeMap<>();
        sortedMap.put("key", "value");

        BackupDatabase backupDatabase = BackupDatabase.builder()
                .id(UUID.randomUUID())
                .name("db1")
                .classifiers(List.of(sortedMap))
                .settings(Map.of("settings-key", Map.of("field1", "value1", "field2", 123)))
                .users(List.of(BackupDatabase.User.builder()
                        .role("role")
                        .name("name")
                        .build()))
                .resources(Map.of("key", "value"))
                .externallyManageable(true)
                .build();

        LogicalBackupStatus logicalBackupStatus = LogicalBackupStatus.builder()
                .status(Status.COMPLETED)
                .errorMessage(null)
                .databases(List.of(LogicalBackupStatus.Database.builder()
                        .databaseName("db1")
                        .path("path")
                        .errorMessage(null)
                        .duration(null)
                        .size(1)
                        .status(Status.COMPLETED)
                        .build()))
                .build();

        LogicalBackup logicalBackup = LogicalBackup.builder()
                .id(UUID.randomUUID())
                .logicalBackupName("name")
                .adapterId("1")
                .type("type")
                .status(logicalBackupStatus)
                .backupDatabases(List.of(backupDatabase))
                .build();

        backupDatabase.setLogicalBackup(logicalBackup);

        BackupStatus backupStatus = BackupStatus.builder()
                .status(Status.COMPLETED)
                .errorMessage(null)
                .total(1)
                .completed(1)
                .size(1L)
                .build();

        Backup backup = new Backup();
        backup.setName(backupName);
        backup.setStorageName("storage");
        backup.setBlobPath("blobPath");
        backup.setExternalDatabaseStrategy(ExternalDatabaseStrategy.FAIL);
        backup.setFilters("null");
        backup.setStatus(backupStatus);
        backup.setLogicalBackups(List.of(logicalBackup));

        when(dbBackupV2Service.getBackupMetadata(backupName))
                .thenReturn(backup);

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .pathParam("backupName", backupName)
                .when().get("/backup/{backupName}/metadata")
                .then()
                .statusCode(OK.getStatusCode())
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
        dto.setExternalDatabaseStrategyDto(ExternalDatabaseStrategyDto.FAIL);

        return dto;
    }
}
