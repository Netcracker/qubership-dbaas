package org.qubership.cloud.dbaas.integration;

import org.qubership.cloud.dbaas.dto.backup.Status;
import org.qubership.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.RestoreResult;
import org.qubership.cloud.dbaas.entity.pg.backup.TrackedAction;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.ActionTrackDbaasRepository;
import org.qubership.cloud.dbaas.rest.DbaasAdapterRestClientV2;
import org.qubership.cloud.dbaas.service.AdapterActionTrackerClient;
import org.qubership.cloud.dbaas.service.DbaasAdapterRESTClientV2;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class AdapterActionTrackerClientTest {

    @Inject
    AdapterActionTrackerClient client;
    @Inject
    ActionTrackDbaasRepository actionTrackDbaasRepository;

    @Test
    void testFailed() {
        TrackedAction adapterBackupAction = new TrackedAction();
        adapterBackupAction.setAction(TrackedAction.Action.BACKUP);
        adapterBackupAction.setTrackId("20230927T093425");
        DbaasAdapterRestClientV2 restClientV2 = mock(DbaasAdapterRestClientV2.class);
        when(restClientV2.trackBackup(any(), any(), any())).thenThrow(new WebApplicationException());
        DatabasesBackup backup = client.waitForBackup(adapterBackupAction, new DbaasAdapterRESTClientV2("", "", restClientV2, "", client));
        Assertions.assertEquals(Status.FAIL, backup.getStatus());

        backup = new DatabasesBackup();
        RestoreResult result = client.waitForRestore(backup, adapterBackupAction, new DbaasAdapterRESTClientV2("", "", restClientV2, "", client));
        Assertions.assertEquals(Status.FAIL, result.getStatus());
    }

    @Test
    void testSettableGeneratorIdIsSet() {
        TrackedAction adapterBackupAction = new TrackedAction();
        String expectedTrackId = "20230927T093426";
        adapterBackupAction.setTrackId(expectedTrackId);

        TrackedAction savedAdapterBackupAction = actionTrackDbaasRepository.save(adapterBackupAction);

        Assertions.assertEquals(expectedTrackId, adapterBackupAction.getTrackId());
        Assertions.assertEquals(expectedTrackId, savedAdapterBackupAction.getTrackId());
    }

    @Test
    void testSettableGeneratorIdIsNotSet() {
        TrackedAction adapterBackupAction = new TrackedAction();

        TrackedAction savedAdapterBackupAction = actionTrackDbaasRepository.save(adapterBackupAction);
        Assertions.assertEquals(savedAdapterBackupAction.getTrackId(), adapterBackupAction.getTrackId());
        Assertions.assertNotNull(savedAdapterBackupAction.getTrackId());
        Assertions.assertNotNull(adapterBackupAction.getTrackId());
    }
}
