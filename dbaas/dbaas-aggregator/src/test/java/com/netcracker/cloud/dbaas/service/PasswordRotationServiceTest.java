package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.dto.EnsuredUser;
import com.netcracker.cloud.dbaas.dto.PasswordChangeResponse;
import com.netcracker.cloud.dbaas.dto.role.Role;
import com.netcracker.cloud.dbaas.dto.v3.PasswordChangeRequestV3;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import com.netcracker.cloud.dbaas.exceptions.PasswordChangeFailedException;
import com.netcracker.cloud.dbaas.exceptions.PasswordChangeValidationException;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import com.netcracker.cloud.dbaas.rest.DbaasAdapterRestClientV2;
import com.netcracker.cloud.dbaas.utils.DatabaseBuilder;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.netcracker.cloud.dbaas.Constants.ROLE;
import static com.netcracker.cloud.dbaas.entity.pg.DbResource.USER_KIND;
import static com.netcracker.cloud.dbaas.utils.DatabaseBuilder.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
class PasswordRotationServiceTest {

    @Spy
    @InjectMocks
    private PasswordRotationService passwordRotationService;

    @Spy
    @InjectMocks
    private DBaaService dBaaService;

    @Mock
    private PasswordEncryption encryption;

    @Mock
    private PhysicalDatabasesService physicalDatabasesService;

    @Mock
    private LogicalDbDbaasRepository logicalDbDbaasRepository;

    @Mock
    private DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @Mock
    private PasswordRotationCommitService passwordRotationCommitService;

    @BeforeEach
    void wireCollaborators() {
        // Mockito does not inject one @InjectMocks instance into another, so wire the
        // real (spied) DBaaService into the service under test manually.
        passwordRotationService.dBaaService = dBaaService;
    }

    @Test
    void changeUserPasswordOneDatabaseTest() {
        DatabaseRegistry database = new DatabaseBuilder()
                .registry()
                .build()
                .getDatabaseRegistry().getFirst();
        SortedMap<String, Object> classifier = database.getClassifier();
        String databaseName = database.getName();

        Map<String, Object> connection = database.getConnectionProperties().getFirst();

        // MOCKs
        DbaasAdapter pgDefaultAdapter = Mockito.spy(createAdapter("pgDefaultAdapter-address", PG_TYPE, mock(DbaasAdapterRestClientV2.class), POSTGRES_ADAPTER_ID,
                mock(AdapterActionTrackerClient.class)));
        Mockito.when(physicalDatabasesService.getAllAdapters()).thenReturn(Arrays.asList(pgDefaultAdapter));
        doReturn(createEnsureUser(connection)).when(pgDefaultAdapter).ensureUser(ADMIN_USER_NAME, null, databaseName, Role.ADMIN.toString());
        doReturn(true).when(pgDefaultAdapter).isUsersSupported();
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        Mockito.when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier, PG_TYPE)).thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));

        adapterSupportUsers(TEST_NS, PG_TYPE, connection, classifier, database);
        doReturn(false).when(pgDefaultAdapter).isUsersSupported();
        adapterNotSupportUsers(TEST_NS, PG_TYPE, connection, classifier);
        doReturn(true).when(pgDefaultAdapter).isUsersSupported();

        doThrow(new WebApplicationException(Response.Status.NOT_FOUND)).when(pgDefaultAdapter).ensureUser(ADMIN_USER_NAME, null, databaseName, Role.ADMIN.toString());
        passwordChangeFail(TEST_NS, PG_TYPE, classifier);
    }

    @Test
    void testGetMergedResources_curretContainsNewResources() {
        DbResource prevResourcePrefix = new DbResource("resourcePrefix", "test-prefix");
        DbResource prevUsernameResource = new DbResource(USER_KIND, "test-username");
        DbResource currAdditionalResource = new DbResource("additionalResource", "test-additional-resource");

        List<DbResource> prev = new ArrayList<>();
        prev.add(prevResourcePrefix);
        prev.add(prevUsernameResource);

        List<DbResource> curr = new ArrayList<>();
        curr.add(currAdditionalResource);

        List<DbResource> result = passwordRotationService.getMergedResources(prev, curr);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(3, result.size());
        Assertions.assertTrue(result.contains(prevResourcePrefix));
        Assertions.assertTrue(result.contains(prevUsernameResource));
        Assertions.assertTrue(result.contains(currAdditionalResource));
    }

    @Test
    void testGetMergedResources_adapterReturnedAllResources() {
        DbResource prevResourcePrefix = new DbResource("resourcePrefix", "test-prefix");
        prevResourcePrefix.setId(UUID.randomUUID());
        DbResource prevUsernameResource = new DbResource(USER_KIND, "test-username");
        prevUsernameResource.setId(UUID.randomUUID());
        DbResource currResourcePrefix = new DbResource("resourcePrefix", "test-prefix");
        DbResource currUsernameResource = new DbResource(USER_KIND, "test-username");

        List<DbResource> prev = new ArrayList<>();
        prev.add(prevResourcePrefix);
        prev.add(prevUsernameResource);

        List<DbResource> curr = new ArrayList<>();
        curr.add(currUsernameResource);
        curr.add(currResourcePrefix);

        List<DbResource> result = passwordRotationService.getMergedResources(prev, curr);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(2, result.size());
        Assertions.assertTrue(result.contains(currUsernameResource));
        Assertions.assertTrue(result.contains(currResourcePrefix));
    }

    @Test
    void changeUserPasswordSeveralDatabaseTest() {
        String defaultAdapter = "pgDefaultAdapter";
        String notDefaultAdapter = "pgNotDefaultAdapter";
        String userName1 = "user-name-1";
        String userPass1 = "new-password-1";
        String userName2 = "user-name-2";
        String userPassw2 = "new-password-2";
        Map<String, Object> connection1 = new HashMap<>();
        connection1.put("username", userName1);
        connection1.put("password", userPass1);
        connection1.put("role", Role.ADMIN.toString());
        Map<String, Object> connection2 = new HashMap<>();
        connection2.put("username", userName2);
        connection2.put("password", userPassw2);
        connection2.put("role", Role.ADMIN.toString());

        DatabaseRegistry database1 = new DatabaseBuilder()
                .adapterId(defaultAdapter)
                .resource(new DbResource("username", userName1))
                .connectionProperties(List.of(
                        Map.of("username", userName1,
                                "password", "old-password",
                                "role", Role.ADMIN.toString())
                ))
                .registry()
                .build()
                .getDatabaseRegistry().getFirst();
        DatabaseRegistry database2 = new DatabaseBuilder()
                .adapterId(notDefaultAdapter)
                .resource(new DbResource("username", userName2))
                .classifier("db", "second")
                .connectionProperties(List.of(
                        Map.of("username", userName2,
                                "password", "old-password",
                                "role", Role.ADMIN.toString())
                ))
                .registry()
                .build()
                .getDatabaseRegistry().getFirst();
        SortedMap<String, Object> classifier1 = database1.getClassifier();
        SortedMap<String, Object> classifier2 = database2.getClassifier();
        String databaseName1 = database1.getName();
        String databaseName2 = database2.getName();

        DbaasAdapter mongoDefaultAdapter = Mockito.spy(createAdapter("pgDefaultAdapter-address", PG_TYPE, mock(DbaasAdapterRestClientV2.class), defaultAdapter,
                mock(AdapterActionTrackerClient.class)));
        DbaasAdapter mongoNotDefaultAdapter = Mockito.spy(createAdapter("pgNotDefaultAdapter-address", PG_TYPE, mock(DbaasAdapterRestClientV2.class), notDefaultAdapter,
                mock(AdapterActionTrackerClient.class)));
        Mockito.when(physicalDatabasesService.getAllAdapters()).thenReturn(Arrays.asList(mongoDefaultAdapter, mongoNotDefaultAdapter));
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        Mockito.when(databaseRegistryDbaasRepository.findInternalDatabaseRegistryByNamespace(eq(TEST_NS))).thenReturn(Arrays.asList(database1, database2));
        doReturn(createEnsureUser(connection1)).when(mongoDefaultAdapter).ensureUser(userName1, null, databaseName1, Role.ADMIN.toString());
        doReturn(createEnsureUser(connection2)).when(mongoNotDefaultAdapter).ensureUser(userName2, null, databaseName2, Role.ADMIN.toString());
        doReturn(true).when(mongoDefaultAdapter).isUsersSupported();
        doReturn(true).when(mongoNotDefaultAdapter).isUsersSupported();

        checkSuccessChangePassword(TEST_NS, PG_TYPE, classifier1, classifier2, connection1, connection2, mongoDefaultAdapter, mongoNotDefaultAdapter, database1, database2);
        doThrow(new WebApplicationException(Response.Status.NOT_FOUND)).when(mongoNotDefaultAdapter).ensureUser(userName2, null, databaseName2, Role.ADMIN.toString());
        Mockito.clearInvocations(passwordRotationCommitService);
        checkOneFailChangePassword(TEST_NS, PG_TYPE, classifier1, classifier2, connection1, connection2, mongoDefaultAdapter, mongoNotDefaultAdapter, database1, database2);
    }

    @Test
    void changeUserPasswordViaV2AndRoleAbsentInResponse() {
        String adapterId = "1234";
        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        when(adapter.identifier()).thenReturn(adapterId);

        when(physicalDatabasesService.getAllAdapters()).thenReturn(Stream.of(adapter).collect(Collectors.toList()));

        DatabaseRegistry database = new DatabaseBuilder()
                .adapterId(adapterId)
                .connectionProperties(List.of(
                        Map.of("username", ADMIN_USER_NAME,
                                "password", "pwd-1",
                                ROLE, Role.ADMIN.toString()),
                        Map.of("username", "username-2",
                                "password", "pwd-2",
                                ROLE, "rw")
                ))
                .registry()
                .build()
                .getDatabaseRegistry().getFirst();
        String databaseName = database.getName();
        Map<String, Object> cp = new HashMap<>(Map.of("username", "user-name", "password", "changed-pwd-1"));
        Map<String, Object> cp2 = new HashMap<>(Map.of("username", "user-name-2", "password", "changed-pwd-2"));
        doReturn(createEnsureUser(cp)).when(adapter).ensureUser("username", null, databaseName, Role.ADMIN.toString());
        doReturn(createEnsureUser(cp2)).when(adapter).ensureUser("username-2", null, databaseName, "rw");


        PasswordChangeResponse passwordChangeResponse = passwordRotationService.performChangePassword(Collections.singletonList(database), null);
        log.info("passwordChangeResponse = {} ", passwordChangeResponse);

        List<PasswordChangeResponse.PasswordChanged> passwordChanges = passwordChangeResponse.getChanged();

        Optional<PasswordChangeResponse.PasswordChanged> passwordChanged1 = passwordChanges.stream().filter(passwordChanged -> passwordChanged.getConnection().containsValue("changed-pwd-1")).findFirst();
        Assertions.assertTrue(passwordChanged1.isPresent());
        Assertions.assertEquals(Role.ADMIN.toString(), passwordChanged1.get().getConnection().get("role"));

        Optional<PasswordChangeResponse.PasswordChanged> passwordChanged2 = passwordChanges.stream().filter(passwordChanged -> passwordChanged.getConnection().containsValue("changed-pwd-2")).findFirst();
        Assertions.assertTrue(passwordChanged2.isPresent());
        Assertions.assertEquals("rw", passwordChanged2.get().getConnection().get("role"));

        verify(passwordRotationCommitService).commitRotation(database);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void testChangePasswordWhenAdapterV1ReturnEmptyRole(String role) {
        String userName = "user-name";
        String password = "new-password";

        Map<String, Object> connection = new HashMap<>();
        connection.put("username", userName);
        connection.put("password", password);
        connection.put("role", Role.ADMIN.toString());

        Map<String, Object> connectionWithEmptyRole = new HashMap<>();
        connectionWithEmptyRole.put("username", userName);
        connectionWithEmptyRole.put("password", password);
        connectionWithEmptyRole.put("role", role);

        DatabaseRegistry database = new DatabaseBuilder()
                .connectionProperties(List.of(
                        connection,
                        connectionWithEmptyRole
                ))
                .registry()
                .build()
                .getDatabaseRegistry().getFirst();
        SortedMap<String, Object> classifier = database.getClassifier();
        String databaseName = database.getName();

        // MOCKs
        DbaasAdapter mongoDefaultAdapter = Mockito.spy(createAdapter("mongoDefaultAdapter-address", PG_TYPE, mock(DbaasAdapterRestClientV2.class), POSTGRES_ADAPTER_ID,
                mock(AdapterActionTrackerClient.class)));
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        Mockito.when(physicalDatabasesService.getAllAdapters()).thenReturn(Arrays.asList(mongoDefaultAdapter));
        doReturn(createEnsureUser(connectionWithEmptyRole)).when(mongoDefaultAdapter).ensureUser(userName, null, databaseName, Role.ADMIN.toString());
        doReturn(true).when(mongoDefaultAdapter).isUsersSupported();

        Mockito.when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier, PG_TYPE)).thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));

        adapterSupportUsers(TEST_NS, PG_TYPE, connection, classifier, database);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void testChangePasswordWhenAdapterV2ReturnEmptyRole(String role) {
        String userName = "user-name";
        String password = "new-password";

        Map<String, Object> connection = new HashMap<>();
        connection.put("username", userName);
        connection.put("password", password);
        connection.put("role", Role.ADMIN.toString());

        Map<String, Object> connectionWithEmptyRole = new HashMap<>();
        connectionWithEmptyRole.put("username", userName);
        connectionWithEmptyRole.put("password", password);
        connectionWithEmptyRole.put("role", role);

        DatabaseRegistry database = new DatabaseBuilder()
                .connectionProperties(List.of(
                        connection,
                        connectionWithEmptyRole
                ))
                .registry()
                .build()
                .getDatabaseRegistry().getFirst();

        SortedMap<String, Object> classifier = database.getClassifier();

        // MOCKs
        DbaasAdapter mongoDefaultAdapter = Mockito.spy(createAdapter("mongoDefaultAdapter-address", PG_TYPE, mock(DbaasAdapterRestClientV2.class), POSTGRES_ADAPTER_ID,
                mock(AdapterActionTrackerClient.class)));
        Mockito.when(physicalDatabasesService.getAllAdapters()).thenReturn(Arrays.asList(mongoDefaultAdapter));
        doReturn(createEnsureUser(connectionWithEmptyRole)).when(mongoDefaultAdapter).ensureUser(userName, null, database.getName(), Role.ADMIN.toString());
        doReturn(true).when(mongoDefaultAdapter).isUsersSupported();

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        Mockito.when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier, PG_TYPE)).thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));

        adapterSupportUsers(TEST_NS, PG_TYPE, connection, classifier, database);
    }

    // ---------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------

    private void checkOneFailChangePassword(String namespace, String dbType, Map<String, Object> classifier1, Map<String, Object> classifier2, Map<String, Object> connection1, Map<String, Object> connection2, DbaasAdapter mongoDefaultAdapter, DbaasAdapter mongoNotDefaultAdapter, DatabaseRegistry database1, DatabaseRegistry database2) {
        PasswordChangeRequestV3 passwordChangeRequest = createPasswordChangeRequest(null, dbType);
        AtomicReference<PasswordChangeFailedException> eRef = new AtomicReference<>();
        Assertions.assertThrows(PasswordChangeFailedException.class, () -> {
            try {
                passwordRotationService.changeUserPassword(passwordChangeRequest, namespace, Role.ADMIN.toString());
            } catch (PasswordChangeFailedException pe) {
                eRef.set(pe);
                throw pe;
            }
        });
        PasswordChangeFailedException exception = eRef.get();
        PasswordChangeResponse response = exception.getResponse();
        Assertions.assertNotNull(response);
        Assertions.assertEquals(404, exception.getStatus());

        Assertions.assertEquals(1, response.getChanged().size());
        Assertions.assertEquals(classifier1, response.getChanged().get(0).getClassifier());

        Assertions.assertEquals(1, response.getFailed().size());
        Assertions.assertEquals(classifier2, response.getFailed().get(0).getClassifier());
        Assertions.assertEquals("HTTP 404 Not Found", response.getFailed().get(0).getMessage());

        // The successful database must have been committed; the failed one must not have been.
        verify(passwordRotationCommitService).commitRotation(database1);
        verify(passwordRotationCommitService, never()).commitRotation(database2);
    }

    private void checkSuccessChangePassword(String namespace, String dbType, Map<String, Object> classifier1, Map<String, Object> classifier2, Map<String, Object> connection1,
                                            Map<String, Object> connection2, DbaasAdapter mongoDefaultAdapter, DbaasAdapter mongoNotDefaultAdapter,
                                            DatabaseRegistry database1, DatabaseRegistry database2) {
        PasswordChangeRequestV3 passwordChangeRequest = createPasswordChangeRequest(null, dbType);
        PasswordChangeResponse response = passwordRotationService.changeUserPassword(passwordChangeRequest, namespace, Role.ADMIN.toString());
        Assertions.assertNotNull(response);
        PasswordChangeResponse expectPasswordChangeResponse = new PasswordChangeResponse();
        expectPasswordChangeResponse.putSuccessEntity(classifier1, connection1);
        expectPasswordChangeResponse.putSuccessEntity(classifier2, connection2);
        Assertions.assertEquals(expectPasswordChangeResponse.getChanged(), response.getChanged());
        verify(mongoDefaultAdapter).isUsersSupported();
        verify(mongoNotDefaultAdapter).isUsersSupported();
        verify(encryption, times(2)).encryptPassword(any(Database.class), any());
        verify(passwordRotationCommitService).commitRotation(database1);
        verify(passwordRotationCommitService).commitRotation(database2);
    }

    private void passwordChangeFail(String namespace, String dbType, Map<String, Object> classifierRequest) {
        PasswordChangeRequestV3 passwordChangeRequest = createPasswordChangeRequest(classifierRequest, dbType);
        AtomicReference<PasswordChangeFailedException> eRef = new AtomicReference<>();
        Assertions.assertThrows(PasswordChangeFailedException.class, () -> {
            try {
                passwordRotationService.changeUserPassword(passwordChangeRequest, namespace, Role.ADMIN.toString());
            } catch (PasswordChangeFailedException pe) {
                eRef.set(pe);
                throw pe;
            }
        });
        PasswordChangeFailedException exception = eRef.get();
        PasswordChangeResponse response = exception.getResponse();
        Assertions.assertNotNull(response);
        Assertions.assertEquals(404, exception.getStatus());
        Assertions.assertEquals(1, response.getFailed().size());
        Assertions.assertEquals(classifierRequest, response.getFailed().get(0).getClassifier());
        Assertions.assertEquals("HTTP 404 Not Found", response.getFailed().get(0).getMessage());
    }

    private void adapterNotSupportUsers(String namespace, String dbType, Map<String, Object> connection, Map<String, Object> classifierRequest) {
        PasswordChangeRequestV3 passwordChangeRequest = createPasswordChangeRequest(classifierRequest, dbType);
        Assertions.assertThrows(PasswordChangeValidationException.class, () -> {
            passwordRotationService.changeUserPassword(passwordChangeRequest, namespace, Role.ADMIN.toString());
        }, "The following adapters: [\"mongoDefaultAdapter-address\"] do not support user password change");
    }

    private void adapterSupportUsers(String namespace, String dbType, Map<String, Object> connection, Map<String, Object> classifierRequest, DatabaseRegistry database) {
        PasswordChangeRequestV3 passwordChangeRequest = createPasswordChangeRequest(classifierRequest, dbType);
        PasswordChangeResponse response = passwordRotationService.changeUserPassword(passwordChangeRequest, namespace, Role.ADMIN.toString());
        PasswordChangeResponse expectPasswordChangeResponse = new PasswordChangeResponse();
        expectPasswordChangeResponse.putSuccessEntity(classifierRequest, connection);
        Assertions.assertEquals(connection, response.getChanged().get(0).getConnection());
        Assertions.assertEquals(classifierRequest, response.getChanged().get(0).getClassifier());
        Assertions.assertEquals(expectPasswordChangeResponse.getChanged(), response.getChanged());
        verify(passwordRotationCommitService).commitRotation(database);
    }

    private PasswordChangeRequestV3 createPasswordChangeRequest(Map<String, Object> classifier, String type) {
        PasswordChangeRequestV3 passwordChangeRequest = new PasswordChangeRequestV3();
        passwordChangeRequest.setType(type);
        passwordChangeRequest.setClassifier(classifier);
        return passwordChangeRequest;
    }

    private EnsuredUser createEnsureUser(Map<String, Object> connectionProperties) {
        EnsuredUser ensuredUser = new EnsuredUser();
        ensuredUser.setConnectionProperties(connectionProperties);
        return ensuredUser;
    }

    private DbaasAdapter createAdapter(String adapterAddress, String type, DbaasAdapterRestClientV2 restClient, String identifier, AdapterActionTrackerClient trackerClient) {
        return new DbaasAdapterRESTClientV2(adapterAddress, type, restClient, identifier, trackerClient);
    }
}
