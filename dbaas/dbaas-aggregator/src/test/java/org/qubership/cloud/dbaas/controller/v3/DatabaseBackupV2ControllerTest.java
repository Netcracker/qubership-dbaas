package org.qubership.cloud.dbaas.controller.v3;

import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.qubership.cloud.dbaas.dto.backupV2.BackupDto;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.service.DbBackupV2Service;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(DatabaseBackupV2Controller.class)
@Slf4j
class DatabaseBackupV2ControllerTest {


    @InjectMock
    DbBackupV2Service dbBackupV2Service;

    @Test
    void shouldStartBackupAndReturnOk() {
        String namespace = "test-namespace";
        String backupName = "backup-123";
        BackupDto dto = new BackupDto();
        dto.setNamespace(namespace);
        dto.setBackupName(backupName);

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(ContentType.JSON)
                .body(dto)
                .when().post()
                .then()
                .statusCode(200);

        verify(dbBackupV2Service, times(1)).backup(namespace, backupName);
    }
}
