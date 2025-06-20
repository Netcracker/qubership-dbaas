package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.*;
import org.qubership.cloud.dbaas.dto.backup.DeleteResult;
import org.qubership.cloud.dbaas.dto.backup.Status;
import org.qubership.cloud.dbaas.dto.v3.CreatedDatabaseV3;
import org.qubership.cloud.dbaas.dto.v3.DatabaseCreateRequestV3;
import org.qubership.cloud.dbaas.dto.v3.UserEnsureRequestV3;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.RestoreResult;
import org.qubership.cloud.dbaas.entity.pg.backup.TrackedAction;
import org.qubership.cloud.dbaas.dto.v3.ApiVersion;
import org.qubership.cloud.dbaas.exceptions.MultiValidationException;
import org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus;
import org.qubership.cloud.dbaas.rest.DbaasAdapterRestClientV2;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.specimpl.BuiltResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus.HEALTH_CHECK_STATUS_UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DbaasAdapterRESTClientV2Test {

    @Mock
    private DbaasAdapterRestClientV2 restClient;

    @Mock
    private AdapterActionTrackerClient tracker;

    private DbaasAdapterRESTClientV2 dbaasAdapterRESTClient;

    private final String TEST_ADAPTER_ADDRESS = "test-adapter-address";
    private final String TEST_TYPE = "test-type";
    private final String TEST_IDENTIFIER = "test-identifier";
    private final String TEST_NAMESPACE = "test-namespace";
    private final String TEST_MESSAGE = "Backup deleted";

    @BeforeEach
    public void init() {
        dbaasAdapterRESTClient = new DbaasAdapterRESTClientV2(TEST_ADAPTER_ADDRESS, TEST_TYPE, restClient, TEST_IDENTIFIER, tracker);
    }

    @Test
    void testIdentifier() {
        assertEquals(TEST_IDENTIFIER, dbaasAdapterRESTClient.identifier());
    }

    @Test
    void testType() {
        assertEquals(TEST_TYPE, dbaasAdapterRESTClient.type());
    }

    @Test
    void testRestoreWithDifferentIdentifier() {
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();
        Assertions.assertThrows(MultiValidationException.class, () -> {
            dbaasAdapterRESTClient.restore(TEST_NAMESPACE, databasesBackup, false, null, new HashMap<>());
        });
    }

    @Test
    void testRestoreWithEmptyLocalId() {
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();
        databasesBackup.setAdapterId(TEST_IDENTIFIER);
        Assertions.assertThrows(MultiValidationException.class, () -> {
            dbaasAdapterRESTClient.restore(TEST_NAMESPACE, databasesBackup, false, null, new HashMap<>());
        });
    }

    @Test
    void testRestore() {
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();
        databasesBackup.setAdapterId(TEST_IDENTIFIER);
        databasesBackup.setLocalId(UUID.randomUUID().toString());

        final TrackedAction trackedAction = getTrackedActionSample();

        when(restClient.restoreBackup(anyString(), any(), anyBoolean(), eq(databasesBackup.getDatabases()))).thenReturn(trackedAction);

        final RestoreResult restoreResult = getRestoreResultSample();
        restoreResult.setStatus(Status.SUCCESS);
        when(tracker.waitForRestore(databasesBackup, trackedAction, dbaasAdapterRESTClient)).thenReturn(restoreResult);
        final RestoreResult actualRestoreResult = dbaasAdapterRESTClient.restore(TEST_NAMESPACE, databasesBackup, false, null, new HashMap<>());

        assertEquals(restoreResult.getStatus(), actualRestoreResult.getStatus());
        verify(restClient).restoreBackup(anyString(), any(), anyBoolean(), eq(databasesBackup.getDatabases()));
        verify(tracker).waitForRestore(databasesBackup, trackedAction, dbaasAdapterRESTClient);
        verifyNoMoreInteractions(restClient, tracker);
    }

    @Test
    void testRestoreExtended() {
        ApiVersion apiVersions = new ApiVersion(List.of(new ApiVersion.Spec("/api", 2, 1, List.of(2))));
        dbaasAdapterRESTClient = new DbaasAdapterRESTClientV2(TEST_ADAPTER_ADDRESS, TEST_TYPE, restClient, TEST_IDENTIFIER, tracker, apiVersions);

        final DatabasesBackup databasesBackup = getDatabasesBackupSample();
        databasesBackup.setAdapterId(TEST_IDENTIFIER);
        databasesBackup.setLocalId(UUID.randomUUID().toString());

        final TrackedAction trackedAction = getTrackedActionSample();
        when(restClient.restoreBackup(anyString(), anyString(), any())).thenReturn(trackedAction);
        final RestoreResult restoreResult = getRestoreResultSample();
        restoreResult.setStatus(Status.SUCCESS);
        when(tracker.waitForRestore(databasesBackup, trackedAction, dbaasAdapterRESTClient)).thenReturn(restoreResult);

        ArrayList<DatabaseRegistry> databases = new ArrayList<>();
        databasesBackup.getDatabases().forEach(dbName -> {
            Database database = mock(Database.class);
            when(database.getName()).thenReturn(dbName);
            DatabaseRegistry databaseRegistry = mock(DatabaseRegistry.class);
            when(databaseRegistry.getDatabase()).thenReturn(database);
            when(databaseRegistry.getClassifier()).thenReturn(new TreeMap<>(Map.of("microserviceName", "testMS")));
            databases.add(databaseRegistry);
        });
        boolean regenerateNames = true;
        final RestoreResult actualRestoreResult = dbaasAdapterRESTClient.restore(TEST_NAMESPACE, databasesBackup, regenerateNames, databases, new HashMap<>());

        RestoreRequest restoreRequest = new RestoreRequest();
        restoreRequest.setRegenerateNames(regenerateNames);
        databasesBackup.getDatabases().forEach(dbName -> restoreRequest.getDatabases().add(new RestoreRequest.Database(TEST_NAMESPACE, "testMS", dbName)));

        assertEquals(restoreResult.getStatus(), actualRestoreResult.getStatus());
        verify(restClient).restoreBackup(any(), any(), eq(restoreRequest));
        verify(tracker).waitForRestore(databasesBackup, trackedAction, dbaasAdapterRESTClient);
        verifyNoMoreInteractions(restClient, tracker);
    }

    @Test
    void testDelete() {
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();
        databasesBackup.setAdapterId(TEST_IDENTIFIER);
        databasesBackup.setLocalId(UUID.randomUUID().toString());

        DeleteResult deleteResponse = new DeleteResult();
        deleteResponse.setStatus(Status.SUCCESS);
        deleteResponse.setAdapterId(TEST_IDENTIFIER);
        deleteResponse.setMessage(TEST_MESSAGE);
        deleteResponse.setDatabasesBackup(databasesBackup);
        when(restClient.deleteBackup(any(), any())).thenReturn(TEST_MESSAGE);
        final DeleteResult actualRestoreResult = dbaasAdapterRESTClient.delete(databasesBackup);
        assertEquals(deleteResponse.getStatus(), actualRestoreResult.getStatus());
        assertEquals(deleteResponse, actualRestoreResult);
    }

    @Test
    void testDeleteNotFound() {
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();

        when(restClient.deleteBackup(any(), any()))
                .thenThrow(new NotFoundException());
        final DeleteResult actualRestoreResultWithNotFound = dbaasAdapterRESTClient.delete(databasesBackup);
        assertEquals(Status.SUCCESS, actualRestoreResultWithNotFound.getStatus());
        assertEquals("Endpoint to delete backup not implemented on adapter yet!", actualRestoreResultWithNotFound.getMessage());
    }

    @Test
    void testDeleteInternalServerError() {
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();

        when(restClient.deleteBackup(any(), any()))
                .thenThrow(new InternalServerErrorException());
        final DeleteResult actualRestoreResultWithError = dbaasAdapterRESTClient.delete(databasesBackup);
        assertEquals(Status.FAIL, actualRestoreResultWithError.getStatus());
        assertEquals("Adapter returned 500 with error HTTP 500 Internal Server Error", actualRestoreResultWithError.getMessage());
    }

    @Test
    void testValidate() {
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();
        databasesBackup.setStatus(Status.FAIL);
        when(tracker.validateBackup(any(TrackedAction.class), eq(dbaasAdapterRESTClient))).thenReturn(databasesBackup);
        boolean actualResult = dbaasAdapterRESTClient.validate(databasesBackup);
        assertFalse(actualResult);

        databasesBackup.setStatus(Status.SUCCESS);
        when(tracker.validateBackup(any(TrackedAction.class), eq(dbaasAdapterRESTClient))).thenReturn(databasesBackup);
        actualResult = dbaasAdapterRESTClient.validate(databasesBackup);
        assertTrue(actualResult);

        when(tracker.validateBackup(any(TrackedAction.class), eq(dbaasAdapterRESTClient))).thenThrow(new RuntimeException());
        actualResult = dbaasAdapterRESTClient.validate(databasesBackup);
        assertFalse(actualResult);
    }

    @Test
    void testGetAdapterHealth() {
        final AdapterHealthStatus adapterHealthStatus = getAdapterHealthStatusSample();
        when(restClient.getHealth()).thenReturn(adapterHealthStatus);
        AdapterHealthStatus actualHealth = dbaasAdapterRESTClient.getAdapterHealth();
        assertEquals("UP", actualHealth.getStatus());

        adapterHealthStatus.setStatus("DOWN");
        when(restClient.getHealth()).thenReturn(adapterHealthStatus);
        actualHealth = dbaasAdapterRESTClient.getAdapterHealth();
        assertEquals("DOWN", actualHealth.getStatus());

        when(restClient.getHealth()).thenThrow(new RuntimeException());
        actualHealth = dbaasAdapterRESTClient.getAdapterHealth();
        assertEquals("PROBLEM", actualHealth.getStatus());
    }

    @Test
    void testCreateDatabaseV3() {
        final DatabaseCreateRequestV3 databaseCreateRequest = getDatabaseCreateRequestSample();
        final CreatedDatabaseV3 createdDatabase = getCreatedDatabaseSample();
        when(restClient.createDatabase(any(String.class), any(AdapterDatabaseCreateRequest.class))).thenReturn(createdDatabase);
        final CreatedDatabaseV3 actualCreatedDatabase = dbaasAdapterRESTClient.createDatabaseV3(databaseCreateRequest, "Test-MS");

        assertEquals(createdDatabase.getName(), actualCreatedDatabase.getName());
        assertEquals(createdDatabase.getAdapterId(), actualCreatedDatabase.getAdapterId());

        verify(restClient).supports(eq(TEST_TYPE));
        verify(restClient).createDatabase(any(String.class), any(AdapterDatabaseCreateRequest.class));
        verifyNoMoreInteractions(restClient);
    }

    @Test
    void testDropDatabase() {
        final DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        Database database = new Database();
        databaseRegistry.setDatabase(database);
        dbaasAdapterRESTClient.dropDatabase(databaseRegistry);

        verifyNoMoreInteractions(restClient);

        databaseRegistry.setResources(Collections.singletonList(new DbResource("test-db", "test-name")));
        when(restClient.dropResources(any(String.class), eq(databaseRegistry.getResources()))).thenReturn(Response.ok().build());
        dbaasAdapterRESTClient.dropDatabase(databaseRegistry);

        verify(restClient).dropResources(any(String.class), eq(databaseRegistry.getResources()));
        verifyNoMoreInteractions(restClient);
    }

    @Test
    void testDropDatabase503() {
        final DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        Database database = new Database();
        databaseRegistry.setDatabase(database);

        databaseRegistry.setResources(Collections.singletonList(new DbResource("test-db", "test-name")));
        databaseRegistry.getResources();
        when(restClient.dropResources(any(String.class), eq(databaseRegistry.getResources()))).thenThrow(new WebApplicationException(Response.Status.SERVICE_UNAVAILABLE));

        Assertions.assertThrows(WebApplicationException.class, () -> {
            dbaasAdapterRESTClient.dropDatabase(databaseRegistry);
        });
    }

    @Test
    void testDropDatabase401() {
        final DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        Database database = new Database();
        databaseRegistry.setDatabase(database);

        databaseRegistry.setResources(Collections.singletonList(new DbResource("test-db", "test-name")));
        databaseRegistry.getResources();
        when(restClient.dropResources(any(String.class), eq(databaseRegistry.getResources()))).thenThrow(new WebApplicationException(Response.Status.FORBIDDEN));
        Assertions.assertThrows(WebApplicationException.class, () -> {
            dbaasAdapterRESTClient.dropDatabase(databaseRegistry);
        });
    }

    @Test
    void testUpdateSettings() {
        String dbName = "test-db-name";
        Map<String, Object> currentSettings = new HashMap<String, Object>() {{
            put("setting-#1", Stream.of("item1", "item2").collect(Collectors.toList()));
            put("setting-#2", true);
        }};
        Map<String, Object> newSettings = new HashMap<String, Object>() {{
            put("setting-#1", Stream.of("item3", "item4").collect(Collectors.toList()));
            put("setting-#2", true);
        }};
        String expectedResponseMessage = "test message";

        UpdateSettingsAdapterRequest expectedUpdateRequest = new UpdateSettingsAdapterRequest();
        expectedUpdateRequest.setCurrentSettings(currentSettings);
        expectedUpdateRequest.setNewSettings(newSettings);

        when(restClient.updateSettings(eq(TEST_TYPE), eq(dbName), eq(expectedUpdateRequest))).thenReturn(expectedResponseMessage);

        String responseMessage = dbaasAdapterRESTClient.updateSettings(dbName, currentSettings, newSettings);
        Assertions.assertEquals(expectedResponseMessage, responseMessage);
    }

    @Test
    void testGetDatabasesMethodNotAllowed() {

        when(restClient.getDatabases(eq(TEST_TYPE))).thenThrow(new WebApplicationException(Response.Status.METHOD_NOT_ALLOWED));

        try {
            dbaasAdapterRESTClient.getDatabases();
            Assertions.fail("Must catch NotAllowedException");
        } catch (WebApplicationException e) {
            Assertions.assertEquals(String.format("Method Not Allowed. DbaaS adapter with address %s does not have 'GET /api/v2/dbaas/adapter/%s/databases' API (getDatabases). You need to contact cloud administrator and update this adapter to a newer version.",
                    TEST_ADAPTER_ADDRESS,
                    TEST_TYPE),
                    ((BuiltResponse) e.getResponse()).getReasonPhrase());
        }
    }

    @Test
    void restoreUsersRequest() {
        String expectedResponseMessage = "test message";
        Response response = Response.accepted(expectedResponseMessage).build();

        Map<String, Object> settings = new HashMap<>() {{
            put("trackId", "test-track-id");
        }};
        List<Map<String, Object>> connProps = new ArrayList<>();
        connProps.add(new HashMap<>() {{
            put("username", "test-user-1");
            put("password", "test-pwd-1");
        }});
        connProps.add(new HashMap<>() {{
            put("username", "test-user-2");
            put("password", "test-pwd-2");
        }});
        RestorePasswordsAdapterRequest request = new RestorePasswordsAdapterRequest(settings, connProps);

        when(restClient.restorePassword(eq(TEST_TYPE), eq(request))).thenReturn(response);

        Response.StatusType responseStatus = dbaasAdapterRESTClient.restorePasswords(settings, connProps);
        Assertions.assertEquals(Response.Status.ACCEPTED.getStatusCode(), responseStatus.getStatusCode());
    }

    @Test
    void createUser() {
        EnsuredUser response = new EnsuredUser();
        when(restClient.createUser(eq(TEST_TYPE), any())).thenReturn(response);
        dbaasAdapterRESTClient.createUser("dbName", "password", "role", "usernamePrefix");
        Mockito.verify(restClient).createUser(eq(TEST_TYPE), any());
    }

    @Test
    void deleteUser() {
        Response responseEntity = Response.ok("deleted").build();
        when(restClient.dropResources(eq(TEST_TYPE), any())).thenReturn(responseEntity);
        dbaasAdapterRESTClient.deleteUser(Collections.emptyList());
        verify(restClient).dropResources(eq(TEST_TYPE), any());
    }

    @Test
    void ensureUser() {
        EnsuredUser responseEntity = new EnsuredUser();
        when(restClient.ensureUser(eq(TEST_TYPE), anyString(), any(UserEnsureRequestV3.class))).thenReturn(responseEntity);
        dbaasAdapterRESTClient.ensureUser("dbName", "password", "role", "usernamePrefix");
        verify(restClient).ensureUser(eq(TEST_TYPE), anyString(), any(UserEnsureRequestV3.class));
    }

    @Test
    void createDatabase() {
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> dbaasAdapterRESTClient.createDatabase(new DatabaseCreateRequest(), "ms1"));
    }

    private CreatedDatabaseV3 getCreatedDatabaseSample() {
        final CreatedDatabaseV3 createdDatabase = new CreatedDatabaseV3();
        createdDatabase.setName("test-db");
        createdDatabase.setAdapterId(TEST_IDENTIFIER);
        return createdDatabase;
    }

    private DatabasesBackup getDatabasesBackupSample() {
        final DatabasesBackup databasesBackup = new DatabasesBackup();
        databasesBackup.setDatabases(Arrays.asList("any"));
        return databasesBackup;
    }

    private TrackedAction getTrackedActionSample() {
        final TrackedAction trackedAction = new TrackedAction();
        trackedAction.setAdapterId(TEST_IDENTIFIER);
        return trackedAction;
    }

    private RestoreResult getRestoreResultSample() {
        final RestoreResult restoreResult = new RestoreResult(TEST_IDENTIFIER);
        return restoreResult;
    }

    private AdapterHealthStatus getAdapterHealthStatusSample() {
        return new AdapterHealthStatus(HEALTH_CHECK_STATUS_UP);
    }

    private DatabaseCreateRequestV3 getDatabaseCreateRequestSample() {
        final Map<String, Object> classifier = getSampleClassifier();
        return new DatabaseCreateRequestV3(classifier, "test-type");
    }

    private Map<String, Object> getSampleClassifier() {
        final Map<String, Object> classifier = new HashMap<>();
        classifier.put("namespace", TEST_NAMESPACE);
        return classifier;
    }
}
