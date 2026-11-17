package com.netcracker.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.dto.DeleteAllInNamespacesRequest;
import com.netcracker.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import com.netcracker.cloud.dbaas.entity.pg.backup.NamespaceRestoration;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.repositories.dbaas.BackupsDbaasRepository;
import com.netcracker.cloud.dbaas.service.DBBackupsService;
import com.netcracker.cloud.dbaas.service.DbaaSHelper;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.*;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(AggregatedBackupRestoreControllerV3.class)
class AggregatedBackupRestoreControllerV3Test {

    private static final UUID BACKUP_ID = UUID.randomUUID();
    private static final String TEST_NAMESPACE = "test-namespace";

    @InjectMock
    BackupsDbaasRepository backupsDbaasRepository;

    @InjectMock
    DBBackupsService dbBackupsService;

    @InjectMock
    DbaaSHelper dbaaSHelper;

    @Inject
    AggregatedBackupRestoreControllerV3 aggregatedBackupRestoreControllerV3;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Test
    void testDeleteAllNamespaceBackups_ProdModeTrue() throws JsonProcessingException {
        when(dbaaSHelper.isProductionMode()).thenReturn(true);
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(MediaType.APPLICATION_JSON)
                .body(deleteAllRequestBody(List.of("ns1", "ns2")))
                .when().delete("/deleteall")
                .then()
                .statusCode(FORBIDDEN.getStatusCode());
        verifyNoInteractions(dbBackupsService);
    }

    @Test
    void testDeleteAllNamespaceBackups_NothingToDelete() throws JsonProcessingException {
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
        when(backupsDbaasRepository.countByNamespaces(any())).thenReturn(0L);
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(MediaType.APPLICATION_JSON)
                .body(deleteAllRequestBody(List.of("ns1", "ns2")))
                .when().delete("/deleteall")
                .then()
                .statusCode(OK.getStatusCode());
        verifyNoInteractions(dbBackupsService);
    }

    @Test
    void testDeleteAllNamespaceBackups_ForceFromQuery() throws JsonProcessingException {
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
        when(backupsDbaasRepository.countByNamespaces(any())).thenReturn(2L);
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .queryParam("forceRemoveNotDeletableBackups", true)
                .contentType(MediaType.APPLICATION_JSON)
                .body(deleteAllRequestBody(List.of("ns1", "ns2")))
                .when().delete("/deleteall")
                .then()
                .statusCode(OK.getStatusCode());
        verify(dbBackupsService).asyncDeleteAllNamespaceBackupsInNamespacesByPortions(Set.of("ns1", "ns2"), true);
        verify(backupsDbaasRepository, never()).findAllNotDeletableBackupIdsByNamespaces(any());
    }

    @Test
    void testDeleteAllNamespaceBackups_NotForced_NoNotDeletableBackups() throws JsonProcessingException {
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
        when(backupsDbaasRepository.countByNamespaces(any())).thenReturn(2L);
        when(backupsDbaasRepository.findAllNotDeletableBackupIdsByNamespaces(any())).thenReturn(List.of());
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(MediaType.APPLICATION_JSON)
                .body(deleteAllRequestBody(List.of("ns1", "ns2")))
                .when().delete("/deleteall")
                .then()
                .statusCode(OK.getStatusCode());
        verify(dbBackupsService).asyncDeleteAllNamespaceBackupsInNamespacesByPortions(Set.of("ns1", "ns2"), false);
    }

    @Test
    void testDeleteAllNamespaceBackups_NotForced_HasNotDeletableBackups() throws JsonProcessingException {
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
        when(backupsDbaasRepository.countByNamespaces(any())).thenReturn(2L);
        when(backupsDbaasRepository.findAllNotDeletableBackupIdsByNamespaces(any())).thenReturn(List.of(BACKUP_ID));
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(MediaType.APPLICATION_JSON)
                .body(deleteAllRequestBody(List.of("ns1", "ns2")))
                .when().delete("/deleteall")
                .then()
                .statusCode(FORBIDDEN.getStatusCode());
        verifyNoInteractions(dbBackupsService);
    }

    @Test
    void testDeleteAllNamespaceBackups_EmptyNamespaces() throws JsonProcessingException {
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(MediaType.APPLICATION_JSON)
                .body(deleteAllRequestBody(List.of()))
                .when().delete("/deleteall")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", containsString("namespaces"));
        verifyNoInteractions(dbBackupsService);
    }

    @Test
    void testDeleteAllNamespaceBackups_NamespacesFieldMissing() {
        given().auth().preemptive().basic("backup_manager", "backup_manager")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .when().delete("/deleteall")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", containsString("namespaces"));
        verifyNoInteractions(dbBackupsService);
    }

    private String deleteAllRequestBody(List<String> namespaces) throws JsonProcessingException {
        DeleteAllInNamespacesRequest request = new DeleteAllInNamespacesRequest();
        request.setNamespaces(new HashSet<>(namespaces));
        return objectMapper.writeValueAsString(request);
    }

    private NamespaceBackup getNamespaceBackupSample() {
        return new NamespaceBackup(BACKUP_ID, TEST_NAMESPACE, Collections.emptyList(), Collections.emptyList());
    }
}
