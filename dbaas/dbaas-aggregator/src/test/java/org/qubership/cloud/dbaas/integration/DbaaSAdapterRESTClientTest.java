package org.qubership.cloud.dbaas.integration;

import org.qubership.cloud.dbaas.dto.DescribedDatabase;
import org.qubership.cloud.dbaas.dto.backup.Status;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.backup.TrackedAction;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.rest.DbaasAdapterRestClientV2;
import org.qubership.cloud.dbaas.service.AdapterActionTrackerClient;
import org.qubership.cloud.dbaas.service.DbaasAdapterRESTClientV2;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.ws.rs.WebApplicationException;
import lombok.Data;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.qubership.cloud.dbaas.entity.shared.AbstractDbResource.DATABASE_KIND;
import static org.qubership.cloud.dbaas.entity.shared.AbstractDbResource.USER_KIND;
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

        when(restClientV2.trackBackup(any(), any(), any())).thenThrow(new WebApplicationException());

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

    @Data
    static class AdapterV1DescribeDatabaseResponse {
        private Map<String, Object> connectionProperties;
        private List<DbResource> resources;
    }

    @Data
    static class AdapterV2DescribeDatabaseResponse {
        private List<Map<String, Object>> connectionProperties;
        private List<DbResource> resources;
    }
}