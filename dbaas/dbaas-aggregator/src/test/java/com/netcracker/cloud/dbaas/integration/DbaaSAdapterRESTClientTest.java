package com.netcracker.cloud.dbaas.integration;

import com.netcracker.cloud.dbaas.dto.DescribedDatabase;
import com.netcracker.cloud.dbaas.dto.backup.DeleteResult;
import com.netcracker.cloud.dbaas.dto.backup.Status;
import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import com.netcracker.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import com.netcracker.cloud.dbaas.entity.pg.backup.TrackedAction;
import com.netcracker.cloud.dbaas.exceptions.AdapterException;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.rest.DbaasAdapterRestClientV2;
import com.netcracker.cloud.dbaas.service.AdapterActionTrackerClient;
import com.netcracker.cloud.dbaas.service.DbaasAdapterRESTClientV2;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.netcracker.cloud.dbaas.entity.shared.AbstractDbResource.DATABASE_KIND;
import static com.netcracker.cloud.dbaas.entity.shared.AbstractDbResource.USER_KIND;
import static jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class DbaaSAdapterRESTClientTest {

    @InjectSpy
    AdapterActionTrackerClient client;

    private final Boolean ALLOW_EVICTION = false;

    @Test
    void testFailBackup() {
        DbaasAdapterRestClientV2 restClientV2 = mock(DbaasAdapterRestClientV2.class);

        TrackedAction adapterBackupAction = new TrackedAction();
        adapterBackupAction.setAction(TrackedAction.Action.BACKUP);
        when(restClientV2.collectBackup(any(), any(), any(), any())).thenReturn(adapterBackupAction);

        TrackedAction failedResponse = new TrackedAction();
        failedResponse.setStatus(Status.FAIL);
        when(restClientV2.trackBackup(any(), any(), any())).thenReturn(failedResponse);

        Assertions.assertEquals(Status.FAIL, new DbaasAdapterRESTClientV2("", "", restClientV2, "", client)
                .backup(Arrays.asList("any"), ALLOW_EVICTION).getStatus());
    }

    @Test
    void testFailBackupOnException() {
        DbaasAdapterRestClientV2 restClientV2 = mock(DbaasAdapterRestClientV2.class);
        TrackedAction adapterBackupAction = new TrackedAction();
        adapterBackupAction.setAction(TrackedAction.Action.BACKUP);
        when(restClientV2.collectBackup(any(), any(), any(), any())).thenReturn(adapterBackupAction);

        when(restClientV2.trackBackup(any(), any(), any()))
                .thenThrow(new AdapterException(INTERNAL_SERVER_ERROR.getStatusCode(), "Adapter Internal Server Error"));

        Assertions.assertEquals(Status.FAIL, new DbaasAdapterRESTClientV2("", "", restClientV2, "", client)
                .backup(Arrays.asList("any"), ALLOW_EVICTION).getStatus());
    }

    @Test
    void describeDatabaseResponseAsSingle() {
        DbaasAdapterRestClientV2 restClientV2 = mock(DbaasAdapterRestClientV2.class);
        DbResource dbKind = new DbResource(DATABASE_KIND, "test");
        DbResource dbUser = new DbResource(USER_KIND, "test-username");
        List<DbResource> resources = Arrays.asList(dbKind, dbUser);

        DescribedDatabase expectedDescribeResponse = new DescribedDatabase();
        expectedDescribeResponse.setResources(resources);
        expectedDescribeResponse.setConnectionProperties(List.of(Collections.singletonMap("host", "pg.pg")));

        Map<String, DescribedDatabase> describedDatabases = new HashMap<>();
        describedDatabases.put("one", expectedDescribeResponse);
        when(restClientV2.describeDatabases(any(), anyBoolean(), anyBoolean(), any())).thenReturn(describedDatabases);

        DbaasAdapterRESTClientV2 dbaasRestClient = new DbaasAdapterRESTClientV2("http://adapter-addr:8080", "pg", restClientV2, "", client);
        Map<String, DescribedDatabase> describeDatabases = dbaasRestClient.describeDatabases(Collections.singletonList("one"));
        verify(restClientV2).describeDatabases(any(), anyBoolean(), anyBoolean(), any());
        DescribedDatabase describedDatabase = describeDatabases.get("one");
        Assertions.assertNotNull(describedDatabase.getConnectionProperties());
        Assertions.assertEquals(expectedDescribeResponse.getConnectionProperties().get(0), describedDatabase.getConnectionProperties().get(0));
    }

    @Test
    void testDeleteBackup_500_adapterException_setsFailStatus() {
        DbaasAdapterRestClientV2 restClientV2 = mock(DbaasAdapterRestClientV2.class);
        when(restClientV2.deleteBackup(any(), any()))
                .thenThrow(new AdapterException(INTERNAL_SERVER_ERROR.getStatusCode(), "internal adapter error"));

        DatabasesBackup backup = new DatabasesBackup();
        backup.setLocalId("backup-123");

        DeleteResult result = new DbaasAdapterRESTClientV2("", "pg", restClientV2, "", client).delete(backup);
        Assertions.assertEquals(Status.FAIL, result.getStatus(),
                "A 500 from deleteBackup should record Status.FAIL on the DeleteResult, not propagate");
    }

    @Test
    void testDeleteBackup_404_adapterException_setsSuccessStatus() {
        DbaasAdapterRestClientV2 restClientV2 = mock(DbaasAdapterRestClientV2.class);
        when(restClientV2.deleteBackup(any(), any()))
                .thenThrow(new AdapterException(NOT_FOUND.getStatusCode(), "backup not found"));

        DatabasesBackup backup = new DatabasesBackup();
        backup.setLocalId("backup-404");

        DeleteResult result = new DbaasAdapterRESTClientV2("", "pg", restClientV2, "", client).delete(backup);
        Assertions.assertEquals(Status.SUCCESS, result.getStatus(),
                "A 404 from deleteBackup should record Status.SUCCESS on the DeleteResult, not propagate");
    }

    @Test
    void testUpdateSettings_adapterException_isRethrown() {
        DbaasAdapterRestClientV2 restClientV2 = mock(DbaasAdapterRestClientV2.class);
        when(restClientV2.updateSettings(any(), any(), any()))
                .thenThrow(new AdapterException(INTERNAL_SERVER_ERROR.getStatusCode(), "settings update failed"));

        DbaasAdapterRESTClientV2 adapter = new DbaasAdapterRESTClientV2("", "pg", restClientV2, "", client);

        Assertions.assertThrows(AdapterException.class,
                () -> adapter.updateSettings("dbname", Map.of(), Map.of("key", "value")));
    }
}
