package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.EnsuredUser;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.GetOrCreateUserRequest;
import org.qubership.cloud.dbaas.dto.v3.GetOrCreateUserResponse;
import org.qubership.cloud.dbaas.dto.userrestore.RestoreUsersRequest;
import org.qubership.cloud.dbaas.dto.v3.UserOperationRequest;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.exceptions.DbNotFoundException;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseUserRepository;
import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @InjectMocks
    private UserService userService;
    @Mock
    private DBaaService dBaaService;

    @Mock
    private PasswordEncryption encryption;

    @Mock
    private PhysicalDatabasesService physicalDatabasesService;

    @Mock
    private DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @Mock
    DatabaseUserRepository databaseUserRepository;

    @Mock
    private ProcessConnectionPropertiesService connectionPropertiesService;

    @BeforeEach
    public void setUp() {
        userService.setDBaaService(dBaaService);
    }


    @Test
    void createUserTest() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(connectionPropertiesService);
        String adapterId = "test-adapter-id";
        String dbName = "test-db-name";
        String phyDbId = "postgresql";
        GetOrCreateUserRequest request = createGetOrCreateUserRequest();

        Database database = createDatabase(request, adapterId, phyDbId, dbName);

        DbaasAdapterRESTClientV2 adapter = spy(new DbaasAdapterRESTClientV2("", "", null, adapterId, Mockito.mock(AdapterActionTrackerClient.class)));
        Map<String, Object> connectionPropertiesForNewUser = new HashMap<>();
        connectionPropertiesForNewUser.put("username", "test-user");
        EnsuredUser ensuredUser = new EnsuredUser(dbName, connectionPropertiesForNewUser,
                Collections.singletonList(new DbResource("someKind", "someName")), true);
        when(physicalDatabasesService.getAdapterByPhysDbId(eq(phyDbId)))
                .thenReturn(adapter);
        DatabaseUser preCreatedUser = new DatabaseUser(request, DatabaseUser.CreationMethod.ON_REQUEST, database);
        preCreatedUser.setStatus(DatabaseUser.Status.CREATING);
        doReturn(ensuredUser)
                .when(adapter).createUser(eq(dbName),
                any(),
                eq(request.getUserRole()),
                eq(request.getUsernamePrefix()));

        GetOrCreateUserResponse getOrCreateUserResponse = userService.createUser(request, database);

        assertNotNull(getOrCreateUserResponse.getUserId());
        assertEquals(getOrCreateUserResponse.getConnectionProperties().get("username"), "test-user");
        verify(adapter,
                times(1)).createUser(eq(dbName), any(), eq(request.getUserRole()), eq(request.getUsernamePrefix()));
        verify(databaseUserRepository, times(2)).persist(any(DatabaseUser.class));
        verify(databaseRegistryDbaasRepository).saveAnyTypeLogDb(any());
    }


    @Test
    void findDatabaseUserTest() {
        UUID userId = UUID.randomUUID();
        DatabaseUser user = new DatabaseUser();
        UserOperationRequest request = createDeleteUserRequest(userId.toString());
        when(databaseUserRepository.findByIdOptional(userId)).thenReturn(Optional.of(user));
        DatabaseUser foundedUser = userService.findUser(request).get();
        assertEquals(foundedUser, user);
    }

    @Test
    void findDatabaseUserTestWhenUserIdIsNull() {
        DatabaseUser user = new DatabaseUser();
        user.setLogicalUserId("test-logical-user-id");
        Database database = new Database();
        database.setId(UUID.randomUUID());
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<DatabaseRegistry>();
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);
        databaseRegistry.setDatabase(database);
        SortedMap<String, Object> classifier = simpleClassifier();
        UserOperationRequest request = new UserOperationRequest(classifier, "test-logical-user-id", "postgresql", null);
        when(databaseUserRepository.findByLogicalDatabaseId(any())).thenReturn(Collections.singletonList(user));
        when(dBaaService.findDatabaseByClassifierAndType(eq(classifier), eq("postgresql"), eq(true))).thenReturn(databaseRegistry);
        DatabaseUser foundedUser = userService.findUser(request).get();
        assertEquals(foundedUser, user);
    }

    @Test
    void findDatabaseUserTestWhenUserIdIsNullAndDatabaseNotFound() {
        SortedMap<String, Object> classifier = simpleClassifier();
        UserOperationRequest request = new UserOperationRequest(classifier, "test-logical-user-id",
                "postgresql", null);
        when(dBaaService.findDatabaseByClassifierAndType(eq(classifier), eq("postgresql"), eq(true)))
                .thenReturn(null);
        assertThrows(DbNotFoundException.class, () -> userService.findUser(request));
    }

    @Test
    void deleteUserTest() {
        String adapterId = "adapterId";
        DatabaseUser user = new DatabaseUser();
        DatabaseRegistry database = createDatabase(simpleClassifier(), "postgresql", adapterId,
                "username", "test-name");
        database.getResources().add(new DbResource("user", "username"));
        user.setDatabase(database.getDatabase());
        Map<String, Object> cp = new HashMap<>();
        cp.put("username", "username");
        user.setConnectionProperties(cp);
        DbaasAdapterRESTClientV2 adapter = spy(new DbaasAdapterRESTClientV2("", "", null, adapterId, Mockito.mock(AdapterActionTrackerClient.class)));
        when(physicalDatabasesService.getAdapterByPhysDbId(eq(database.getPhysicalDatabaseId()))).thenReturn(adapter);
        doReturn(true).when(adapter).deleteUser(any());
        assertTrue(userService.deleteUser(user));
        verify(databaseUserRepository).delete(user);
        verify(databaseRegistryDbaasRepository).saveAnyTypeLogDb(any());
    }

    @Test
    void RestoreUsersTestWithClassifier(){
        RestoreUsersRequest restoreUsersRequest = createSampleRestoreUsersRequest();
        DatabaseRegistry database = new DatabaseRegistry();
        database.setDatabase(new Database());
        database.setAdapterId("test-id");
        Map<String, Object> cp = new HashMap<>();
        cp.put("username", "username");
        cp.put("password", "password");
        cp.put("role", "admin");

        database.setConnectionProperties(List.of(cp));
        DbaasAdapter adapter = mock(DbaasAdapter.class);
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(eq(restoreUsersRequest.getClassifier()),
                eq(restoreUsersRequest.getType())))
                .thenReturn(Optional.of(database));
        when(physicalDatabasesService.getAdapterById(eq(database.getAdapterId()))).thenReturn(adapter);
        when(adapter.ensureUser(any(), any(), any(), any())).thenReturn(new EnsuredUser());
        assertDoesNotThrow(() -> userService.restoreUsers(restoreUsersRequest));
    }

    @Test
    void RestoreUsersTestWithClassifierDbNotFound(){
        RestoreUsersRequest restoreUsersRequest = createSampleRestoreUsersRequest();
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(eq(restoreUsersRequest.getClassifier()),
                eq(restoreUsersRequest.getType())))
                .thenReturn(Optional.empty());
        assertThrows(DbNotFoundException.class, () -> userService.restoreUsers(restoreUsersRequest));
    }

    private UserOperationRequest createDeleteUserRequest(String userId) {
        UserOperationRequest req = new UserOperationRequest();
        req.setUserId(userId);
        return req;
    }


    private Database createDatabase(GetOrCreateUserRequest request, String adapterId, String phyDbId, String dbName) {
        Database database = new Database();
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(database);
        database.setClassifier(request.getClassifier());
        database.setPhysicalDatabaseId("postgresql");
        database.setAdapterId(adapterId);
        database.setPhysicalDatabaseId(phyDbId);
        database.setName(dbName);
        database.setResources(new ArrayList<>());
        database.setConnectionProperties(new ArrayList(Arrays.asList(new HashMap<>())));
        database.setDatabaseRegistry(List.of(databaseRegistry));
        return database;
    }

    private GetOrCreateUserRequest createGetOrCreateUserRequest() {
        SortedMap<String, Object> classifier = simpleClassifier();
        return new GetOrCreateUserRequest(
                classifier,
                "new-logical-user-id",
                "postgresql",
                "postgresql",
                "some-prefix",
                "rw");
    }

    private SortedMap<String, Object> simpleClassifier() {
        return new TreeMap<>() {{
            put("namespace", "test-namespace");
            put("microserviceName", "test-service");
            put("scope", "service");
        }};
    }

    private DatabaseRegistry createDatabase(Map<String, Object> classifier, String type, String adapterId, String username, String dbName) {
        Database database = new Database();
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(database);
        database.setId(UUID.randomUUID());
        database.setTimeDbCreation(new Date());
        database.setClassifier(new TreeMap<>(classifier));
        database.setType(type);
        database.setConnectionProperties(new ArrayList<>(List.of(new HashMap<String, Object>() {{
            put("username", username);
            put(ROLE, Role.ADMIN.toString());
        }})));
        database.setName(dbName);
        database.setAdapterId(adapterId);
        database.setSettings(new HashMap<String, Object>() {{
            put("setting-one", "value-one");
        }});
        database.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        database.setResources(new LinkedList<>(Arrays.asList(new DbResource("username", username),
                new DbResource("database", dbName))));
        database.setDatabaseRegistry(List.of(databaseRegistry));
        return databaseRegistry;
    }

    private RestoreUsersRequest createSampleRestoreUsersRequest(){
        SortedMap<String, Object> classifier = simpleClassifier();
        return new RestoreUsersRequest(classifier, "postgresql", null);
    }

}
