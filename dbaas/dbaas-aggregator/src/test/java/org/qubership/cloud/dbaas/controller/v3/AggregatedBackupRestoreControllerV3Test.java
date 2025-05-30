package org.qubership.cloud.dbaas.controller.v3;

import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceRestoration;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.BackupsDbaasRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(AggregatedBackupRestoreControllerV3.class)
class AggregatedBackupRestoreControllerV3Test {

    private static final UUID BACKUP_ID = UUID.randomUUID();
    private static final String TEST_NAMESPACE = "test-namespace";

    @InjectMock
    BackupsDbaasRepository backupsDbaasRepository;

    @Inject
    AggregatedBackupRestoreControllerV3 aggregatedBackupRestoreControllerV3;

    @Test
    void testRestoreBackup() {
        AggregatedBackupAdministrationControllerV3 backupAdministrationControllerV3 = mock(AggregatedBackupAdministrationControllerV3.class);
        aggregatedBackupRestoreControllerV3.backupAdministrationControllerV3 = backupAdministrationControllerV3;
        when(backupAdministrationControllerV3.restoreBackupInNamespace(eq(null), eq(BACKUP_ID), eq(TEST_NAMESPACE)))
                .thenReturn(Response.ok().build());
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .queryParam("targetNamespace", TEST_NAMESPACE)
                .when().post("/{backupId}/restorations", BACKUP_ID)
                .then()
                .statusCode(OK.getStatusCode());

        verify(backupAdministrationControllerV3).restoreBackupInNamespace(eq(null), eq(BACKUP_ID), eq(TEST_NAMESPACE));
        verifyNoMoreInteractions(backupAdministrationControllerV3);
    }

    @Test
    void testGetRestorationOfBackupInNamespace() throws Exception {
        final UUID restorationId = UUID.randomUUID();
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .when().get("/{backupId}/restorations/{restorationId}", BACKUP_ID, restorationId)
                .then()
                .statusCode(NOT_FOUND.getStatusCode());

        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        when(backupsDbaasRepository.findById(BACKUP_ID)).thenReturn(Optional.of(namespaceBackup));
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .when().get("/{backupId}/restorations/{restorationId}", BACKUP_ID, restorationId)
                .then()
                .statusCode(NOT_FOUND.getStatusCode());

        namespaceBackup.setRestorations(Collections.emptyList());
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .when().get("/{backupId}/restorations/{restorationId}", BACKUP_ID, restorationId)
                .then()
                .statusCode(NOT_FOUND.getStatusCode());

        final NamespaceRestoration namespaceRestoration = new NamespaceRestoration();
        namespaceRestoration.setId(restorationId);
        namespaceBackup.setRestorations(Collections.singletonList(namespaceRestoration));

        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .when().get("/{backupId}/restorations/{restorationId}", BACKUP_ID, restorationId)
                .then()
                .statusCode(OK.getStatusCode())
                .body("id", is(namespaceRestoration.getId().toString()));
    }

    private NamespaceBackup getNamespaceBackupSample() {
        return new NamespaceBackup(BACKUP_ID, TEST_NAMESPACE, Collections.emptyList(), Collections.emptyList());
    }
}
