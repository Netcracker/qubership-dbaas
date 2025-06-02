package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.API_VERSION;
import org.qubership.cloud.dbaas.dto.EnsuredUser;
import org.qubership.cloud.dbaas.dto.HttpBasicCredentials;
import org.qubership.cloud.dbaas.dto.migration.RegisterDatabaseResponseBuilder;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.DatabaseResponseV3ListCP;
import org.qubership.cloud.dbaas.dto.v3.RegisterDatabaseRequestV3;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.exceptions.UnregisteredPhysicalDatabaseException;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.qubership.cloud.dbaas.DbaasApiPath.VERSION_2;
import static org.qubership.cloud.dbaas.service.PasswordEncryption.PASSWORD_FIELD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class MigrationServiceTest {

    @Mock
    private PhysicalDatabasesService physicalDatabasesService;

    @Mock
    private DbaasAdapter dbaasAdapter;

    @Mock
    private DbaasAdapter secondDbaasAdapter;

    @Mock
    private DBaaService dBaaService;

    @Mock
    private DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @InjectMocks
    private MigrationService migrationService;

    private final String TEST_NAMESPACE = "test-namespace";
    private final String TEST_ADAPTER_ID = "test-adapter-id";
    private final String TEST_PHYDBID = "test-phydbid";
    private final String TEST_SECOND_ADAPTER_ID = "test-second-adapter-id";
    private final String TEST_SECOND_PHYDBID = "test-second-phydbid";
    private final String TEST_DB_NAME = "test-db";
    private final String TEST_TYPE = "test-type";

    @Test
    public void testRegisterRequestValidation() {
        final RegisterDatabaseRequestV3 wrongAdapterId = getRegisterDatabaseRequestSample();
        wrongAdapterId.setAdapterId("wrong-adapter-id");

        final RegisterDatabaseRequestV3 wrongPhyDbId = getRegisterDatabaseRequestSample();
        wrongPhyDbId.setAdapterId(null);
        wrongPhyDbId.setPhysicalDatabaseId("wrong-phydb-id");

        final RegisterDatabaseRequestV3 conflictingAdapter = getRegisterDatabaseRequestSample();
        conflictingAdapter.setAdapterId(TEST_ADAPTER_ID);
        conflictingAdapter.setPhysicalDatabaseId(TEST_SECOND_PHYDBID);

        final RegisterDatabaseRequestV3 conflictingPhysicalDb = getRegisterDatabaseRequestSample();
        conflictingPhysicalDb.setAdapterId(TEST_SECOND_ADAPTER_ID);
        conflictingPhysicalDb.setPhysicalDatabaseId(TEST_PHYDBID);

        final List<RegisterDatabaseRequestV3> registerDatabaseRequestList = List.of(wrongAdapterId, wrongPhyDbId, conflictingAdapter, conflictingPhysicalDb);

        final RegisterDatabaseResponseBuilder registerDatabaseResponseBuilder = migrationService.registerDatabases(registerDatabaseRequestList, API_VERSION.V1, false);
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), registerDatabaseResponseBuilder.buildAndResponse().getStatus());

        verify(databaseRegistryDbaasRepository, times(0)).saveInternalDatabase(any(DatabaseRegistry.class));
    }

    @Test
    public void testRegisterDatabasesWithAdapterId() {
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(physicalDatabasesService.getByAdapterId(TEST_ADAPTER_ID)).thenReturn(getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_PHYDBID));
        when(dbaasAdapter.getDatabases()).thenReturn(Collections.singleton(TEST_DB_NAME));
        when(dbaasAdapter.identifier()).thenReturn(TEST_ADAPTER_ID);
        mockConnectionPropertiesResponse(dBaaService);
        final List<RegisterDatabaseRequestV3> registerDatabaseRequestList = Collections.singletonList(getRegisterDatabaseRequestSample());

        final RegisterDatabaseResponseBuilder registerDatabaseResponseBuilder = migrationService.registerDatabases(registerDatabaseRequestList, API_VERSION.V1, false);
        assertEquals(Response.Status.OK.getStatusCode(), registerDatabaseResponseBuilder.buildAndResponse().getStatus());

        verify(dBaaService).encryptAndSaveDatabaseEntity(any(DatabaseRegistry.class));
        verify(physicalDatabasesService, times(2)).getAdapterById(TEST_ADAPTER_ID);
        verify(dbaasAdapter).getDatabases();
        verify(dbaasAdapter).identifier();
        verifyNoMoreInteractions(databaseRegistryDbaasRepository, physicalDatabasesService, dbaasAdapter);
    }

    @Test
    void testRegisterDatabasesWithAdapterIdV3() {
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(physicalDatabasesService.getByAdapterId(TEST_ADAPTER_ID)).thenReturn(getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_PHYDBID));
        when(dbaasAdapter.getDatabases()).thenReturn(Collections.singleton(TEST_DB_NAME));
        when(dbaasAdapter.identifier()).thenReturn(TEST_ADAPTER_ID);

        RegisterDatabaseRequestV3 registerDatabaseRequest = new RegisterDatabaseRequestV3();
        registerDatabaseRequest.setName(TEST_DB_NAME);
        registerDatabaseRequest.setNamespace(TEST_NAMESPACE);
        registerDatabaseRequest.setAdapterId(TEST_ADAPTER_ID);
        registerDatabaseRequest.setType(TEST_TYPE);
        final Map<String, Object> connectionProperties = new HashMap<>();
        connectionProperties.put(PASSWORD_FIELD, "test-password");
        connectionProperties.put(ROLE, Role.ADMIN.toString());
        registerDatabaseRequest.setConnectionProperties(Arrays.asList(connectionProperties));

        final List<RegisterDatabaseRequestV3> registerDatabaseRequestList = Collections.singletonList(registerDatabaseRequest);
        mockConnectionPropertiesResponse(dBaaService);
        final RegisterDatabaseResponseBuilder registerDatabaseResponseBuilder = migrationService.registerDatabases(registerDatabaseRequestList, API_VERSION.V3, false);
        assertEquals(Response.Status.OK.getStatusCode(), registerDatabaseResponseBuilder.buildAndResponse().getStatus());

        verify(dBaaService).encryptAndSaveDatabaseEntity(any(DatabaseRegistry.class));
        verify(physicalDatabasesService, times(2)).getAdapterById(TEST_ADAPTER_ID);
        verify(dbaasAdapter).getDatabases();
        verify(dbaasAdapter).identifier();
        verifyNoMoreInteractions(databaseRegistryDbaasRepository, physicalDatabasesService, dbaasAdapter);
    }

    @Test
    void testRegisterDatabasesWithUserCreation() {
        PhysicalDatabase physicalDatabaseSample = getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_PHYDBID);
        physicalDatabaseSample.setRoles(Arrays.asList("admin", "rw"));
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(TEST_PHYDBID)).thenReturn(physicalDatabaseSample);
        when(physicalDatabasesService.getByAdapterId(TEST_ADAPTER_ID)).thenReturn(physicalDatabaseSample);
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(dbaasAdapter.getDatabases()).thenReturn(Collections.singleton(TEST_DB_NAME));
        when(dbaasAdapter.identifier()).thenReturn(TEST_ADAPTER_ID);
        doNothing().when(dbaasAdapter).changeMetaData(eq(TEST_DB_NAME), anyMap());

        when(dBaaService.recreateUsers(dbaasAdapter, null, TEST_DB_NAME, null, "admin"))
                .thenReturn(createEnsureUser(getConnectionProp("admin")));
        when(dBaaService.recreateUsers(dbaasAdapter, null, TEST_DB_NAME, null, "rw"))
                .thenReturn(createEnsureUser(getConnectionProp("rw")));

        RegisterDatabaseRequestV3 registerDatabaseRequest = new RegisterDatabaseRequestV3();
        registerDatabaseRequest.setName(TEST_DB_NAME);
        registerDatabaseRequest.setNamespace(TEST_NAMESPACE);
        registerDatabaseRequest.setPhysicalDatabaseId(TEST_PHYDBID);
        registerDatabaseRequest.setType(TEST_TYPE);
        registerDatabaseRequest.setClassifier(getClassifier());

        final List<RegisterDatabaseRequestV3> registerDatabaseRequestList = Collections.singletonList(registerDatabaseRequest);
        mockConnectionPropertiesResponse(dBaaService);
        final RegisterDatabaseResponseBuilder registerDatabaseResponseBuilder = migrationService.registerDatabases(registerDatabaseRequestList, API_VERSION.V3, true);
        Response responseEntity = registerDatabaseResponseBuilder.buildAndResponse();
        assertEquals(Response.Status.OK.getStatusCode(), responseEntity.getStatus());
        Map<String, RegisterDatabaseResponseBuilder.MigrationResult> responseBody = (Map<String, RegisterDatabaseResponseBuilder.MigrationResult>) responseEntity.getEntity();
        assertEquals(1, responseBody.get(TEST_TYPE).getMigratedDbInfo().size());
        assertEquals(2, responseBody.get(TEST_TYPE).getMigratedDbInfo().get(0).getConnectionProperties().size());
        assertEquals(TEST_DB_NAME, responseBody.get(TEST_TYPE).getMigrated().get(0));

        verify(dBaaService).encryptAndSaveDatabaseEntity(any(DatabaseRegistry.class));
        verify(physicalDatabasesService, times(4)).getAdapterById(TEST_ADAPTER_ID);
        verify(dbaasAdapter).getDatabases();
        verify(dbaasAdapter).identifier();
        verify(dBaaService, times(2)).recreateUsers(any(), any(), any(), any(), any());
        verifyNoMoreInteractions(databaseRegistryDbaasRepository, physicalDatabasesService, dbaasAdapter);
    }

    private void mockConnectionPropertiesResponse(DBaaService dBaaService) {
        when(dBaaService.processConnectionPropertiesV3(any())).thenAnswer(i -> new DatabaseResponseV3ListCP((DatabaseRegistry) i.getArguments()[0], ((DatabaseRegistry) i.getArguments()[0]).getDatabase().getPhysicalDatabaseId()));
    }

    private static Map<String, Object> getConnectionProp(String role) {
        final Map<String, Object> connectionProperties = new HashMap<>();
        connectionProperties.put(PASSWORD_FIELD, "test-password");
        connectionProperties.put("username", "user-1");
        connectionProperties.put(ROLE, role);
        return connectionProperties;
    }

    private static TreeMap<String, Object> getClassifier() {
        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", "TEST_NAMESPACE");
        classifier.put("scope", "service");
        classifier.put("microserviceName", "test_name");
        return classifier;
    }

    private EnsuredUser createEnsureUser(Map<String, Object> connectionProperties) {
        EnsuredUser ensuredUser = new EnsuredUser();
        ensuredUser.setConnectionProperties(connectionProperties);
        ensuredUser.setResources(Collections.singletonList(new DbResource("test-db", "test-name")));
        return ensuredUser;
    }

    @Test
    public void testRegisterDatabasesWithAdapterIdAndPhybId() {
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(TEST_PHYDBID)).thenReturn(getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_PHYDBID));

        when(dbaasAdapter.getDatabases()).thenReturn(Collections.singleton(TEST_DB_NAME));
        when(dbaasAdapter.identifier()).thenReturn(TEST_ADAPTER_ID);

        final RegisterDatabaseRequestV3 registerDatabaseRequest = getRegisterDatabaseRequestSample();
        registerDatabaseRequest.setPhysicalDatabaseId(TEST_PHYDBID);
        final List<RegisterDatabaseRequestV3> registerDatabaseRequestList = Collections.singletonList(registerDatabaseRequest);
        mockConnectionPropertiesResponse(dBaaService);
        final RegisterDatabaseResponseBuilder registerDatabaseResponseBuilder = migrationService.registerDatabases(registerDatabaseRequestList, API_VERSION.V1, false);
        assertEquals(Response.Status.OK.getStatusCode(), registerDatabaseResponseBuilder.buildAndResponse().getStatus());

        verify(dBaaService).encryptAndSaveDatabaseEntity(any(DatabaseRegistry.class));
        verify(physicalDatabasesService).getAdapterById(TEST_ADAPTER_ID);
        verify(physicalDatabasesService).getByPhysicalDatabaseIdentifier(TEST_PHYDBID);
        verify(dbaasAdapter).getDatabases();
        verify(dbaasAdapter).identifier();
        verifyNoMoreInteractions(databaseRegistryDbaasRepository, physicalDatabasesService, dbaasAdapter);
    }

    @Test
    public void testRegisterDatabasesWithPhybId() {
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(TEST_PHYDBID)).thenReturn(getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_PHYDBID));

        when(dbaasAdapter.getDatabases()).thenReturn(Collections.singleton(TEST_DB_NAME));
        when(dbaasAdapter.identifier()).thenReturn(TEST_ADAPTER_ID);

        final RegisterDatabaseRequestV3 registerDatabaseRequest = getRegisterDatabaseRequestSample();
        registerDatabaseRequest.setPhysicalDatabaseId(TEST_PHYDBID);
        registerDatabaseRequest.setAdapterId(null);
        final List<RegisterDatabaseRequestV3> registerDatabaseRequestList = Collections.singletonList(registerDatabaseRequest);

        mockConnectionPropertiesResponse(dBaaService);
        final RegisterDatabaseResponseBuilder registerDatabaseResponseBuilder = migrationService.registerDatabases(registerDatabaseRequestList, API_VERSION.V1, false);
        assertEquals(Response.Status.OK.getStatusCode(), registerDatabaseResponseBuilder.buildAndResponse().getStatus());

        verify(dBaaService, times(1)).encryptAndSaveDatabaseEntity(any(DatabaseRegistry.class));
        verify(physicalDatabasesService, times(1)).getAdapterById(TEST_ADAPTER_ID);
        verify(physicalDatabasesService, times(1)).getByPhysicalDatabaseIdentifier(TEST_PHYDBID);
        verify(dbaasAdapter, times(1)).getDatabases();
        verify(dbaasAdapter, times(1)).identifier();
        verifyNoMoreInteractions(databaseRegistryDbaasRepository, physicalDatabasesService, dbaasAdapter);
    }

    @Test
    public void testRegisterDatabasesWithoutAdapterIdAndPhybId() {
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(dbaasAdapter, secondDbaasAdapter));
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(physicalDatabasesService.getByAdapterId(TEST_ADAPTER_ID)).thenReturn(getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_PHYDBID));

        when(dbaasAdapter.getDatabases()).thenReturn(Collections.singleton(TEST_DB_NAME));
        when(dbaasAdapter.identifier()).thenReturn(TEST_ADAPTER_ID);
        when(dbaasAdapter.type()).thenReturn(TEST_TYPE);

        when(secondDbaasAdapter.identifier()).thenReturn(TEST_SECOND_ADAPTER_ID);
        final RegisterDatabaseRequestV3 registerDatabaseRequest = getRegisterDatabaseRequestSample();
        registerDatabaseRequest.setAdapterId(null);
        final List<RegisterDatabaseRequestV3> registerDatabaseRequestList = Collections.singletonList(registerDatabaseRequest);
        mockConnectionPropertiesResponse(dBaaService);
        final RegisterDatabaseResponseBuilder registerDatabaseResponseBuilder = migrationService.registerDatabases(registerDatabaseRequestList, API_VERSION.V1, false);
        assertEquals(Response.Status.OK.getStatusCode(), registerDatabaseResponseBuilder.buildAndResponse().getStatus());

        verify(dBaaService, times(1)).encryptAndSaveDatabaseEntity(any(DatabaseRegistry.class));
        verify(physicalDatabasesService, times(1)).getAdapterById(TEST_ADAPTER_ID);
        verify(physicalDatabasesService, times(1)).getAllAdapters();
        verify(dbaasAdapter, times(2)).identifier();
        verify(dbaasAdapter, times(1)).type();
        verify(dbaasAdapter, times(1)).getDatabases();
        verifyNoMoreInteractions(databaseRegistryDbaasRepository, physicalDatabasesService, dbaasAdapter);
    }

    @Test
    public void testRegisterDatabasesWithoutAdapterIdAndPhybIdConflict() {
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(dbaasAdapter, secondDbaasAdapter));
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(physicalDatabasesService.getAdapterById(TEST_SECOND_ADAPTER_ID)).thenReturn(secondDbaasAdapter);

        when(dbaasAdapter.getDatabases()).thenReturn(Collections.singleton(TEST_DB_NAME));
        when(dbaasAdapter.identifier()).thenReturn(TEST_ADAPTER_ID);
        when(dbaasAdapter.type()).thenReturn(TEST_TYPE);

        when(secondDbaasAdapter.identifier()).thenReturn(TEST_SECOND_ADAPTER_ID);
        when(secondDbaasAdapter.type()).thenReturn(TEST_TYPE);
        when(secondDbaasAdapter.getDatabases()).thenReturn(Collections.singleton(TEST_DB_NAME));

        final RegisterDatabaseRequestV3 registerDatabaseRequest = getRegisterDatabaseRequestSample();
        registerDatabaseRequest.setAdapterId(null);
        final List<RegisterDatabaseRequestV3> registerDatabaseRequestList = Collections.singletonList(registerDatabaseRequest);

        final RegisterDatabaseResponseBuilder registerDatabaseResponseBuilder = migrationService.registerDatabases(registerDatabaseRequestList, API_VERSION.V1, false);
        assertEquals(Response.Status.CONFLICT.getStatusCode(), registerDatabaseResponseBuilder.buildAndResponse().getStatus());

        verify(databaseRegistryDbaasRepository, times(0)).saveInternalDatabase(any(DatabaseRegistry.class));
        verify(physicalDatabasesService, times(1)).getAdapterById(TEST_ADAPTER_ID);
        verify(physicalDatabasesService, times(1)).getAdapterById(TEST_SECOND_ADAPTER_ID);
        verify(physicalDatabasesService, times(1)).getAllAdapters();
        verify(dbaasAdapter, times(2)).identifier();
        verify(dbaasAdapter, times(1)).type();
        verify(dbaasAdapter, times(1)).getDatabases();
        verify(secondDbaasAdapter, times(2)).identifier();
        verify(secondDbaasAdapter, times(1)).type();
        verify(secondDbaasAdapter, times(1)).getDatabases();
        verifyNoMoreInteractions(databaseRegistryDbaasRepository, physicalDatabasesService, dbaasAdapter, secondDbaasAdapter);
    }

    @Test
    public void testRegisterDatabasesFail() {
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(physicalDatabasesService.getByAdapterId(TEST_ADAPTER_ID)).thenReturn(getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_PHYDBID));
        when(dbaasAdapter.getDatabases()).thenReturn(Collections.singleton(TEST_DB_NAME));
        when(dbaasAdapter.identifier()).thenReturn(TEST_ADAPTER_ID);

        final List<RegisterDatabaseRequestV3> registerDatabaseRequestList = Collections.singletonList(getRegisterDatabaseRequestSample());
        when(dbaasAdapter.getDatabases()).thenReturn(Collections.singleton("another-db-name"));

        final RegisterDatabaseResponseBuilder registerDatabaseResponseBuilder = migrationService.registerDatabases(registerDatabaseRequestList, API_VERSION.V1, false);
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), registerDatabaseResponseBuilder.buildAndResponse().getStatus());

        verify(databaseRegistryDbaasRepository, times(0)).saveInternalDatabase(any(DatabaseRegistry.class));
        verify(physicalDatabasesService, times(2)).getAdapterById(TEST_ADAPTER_ID);
        verify(dbaasAdapter, times(1)).getDatabases();
        verify(dbaasAdapter, times(1)).identifier();
        verifyNoMoreInteractions(databaseRegistryDbaasRepository, physicalDatabasesService, dbaasAdapter);
    }

    @Test
    public void testRegisterDatabasesOldAdapter() {
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(physicalDatabasesService.getByAdapterId(TEST_ADAPTER_ID)).thenReturn(getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_PHYDBID));
        when(dbaasAdapter.getDatabases()).thenReturn(Collections.singleton(TEST_DB_NAME));
        when(dbaasAdapter.identifier()).thenReturn(TEST_ADAPTER_ID);

        final List<RegisterDatabaseRequestV3> registerDatabaseRequestList = Collections.singletonList(getRegisterDatabaseRequestSample());

        when(dbaasAdapter.getDatabases()).thenThrow(new ForbiddenException());
        mockConnectionPropertiesResponse(dBaaService);
        final RegisterDatabaseResponseBuilder registerDatabaseResponseBuilder = migrationService.registerDatabases(registerDatabaseRequestList, API_VERSION.V1, false);
        assertEquals(Response.Status.OK.getStatusCode(), registerDatabaseResponseBuilder.buildAndResponse().getStatus());

        verify(dBaaService, times(1)).encryptAndSaveDatabaseEntity(any(DatabaseRegistry.class));
        verify(physicalDatabasesService, times(2)).getAdapterById(TEST_ADAPTER_ID);
        verify(dbaasAdapter, times(1)).getDatabases();
        verify(dbaasAdapter, times(1)).identifier();
        verifyNoMoreInteractions(databaseRegistryDbaasRepository, physicalDatabasesService, dbaasAdapter);
    }

    @Test
    void checkAutodiscoveryOfPhysicalDbByHostNoDatabases() {
        RegisterDatabaseRequestV3 testRequest = getRegisterDatabaseRequestSample();
        testRequest.setAdapterId("");
        testRequest.setDbHost(TEST_TYPE + "." + TEST_NAMESPACE);
        when(physicalDatabasesService.getPhysicalDatabaseByAdapterHost(TEST_NAMESPACE)).thenReturn(Collections.emptyList());
        assertThrows(UnregisteredPhysicalDatabaseException.class, () -> migrationService.registerDatabases(List.of(testRequest), API_VERSION.V1, true));
    }

    @Test
    void checkAutodiscoveryOfPhysicalDbByHostOneDatabase() {
        PhysicalDatabase physDb = getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_PHYDBID);
        physDb.setPhysicalDatabaseIdentifier(TEST_PHYDBID);
        when(physicalDatabasesService.getPhysicalDatabaseByAdapterHost(TEST_NAMESPACE)).thenReturn(Collections.singletonList(physDb));

        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(TEST_PHYDBID)).thenReturn(getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_PHYDBID));

        when(dbaasAdapter.getDatabases()).thenReturn(Collections.singleton(TEST_DB_NAME));
        when(dbaasAdapter.identifier()).thenReturn(TEST_ADAPTER_ID);

        RegisterDatabaseRequestV3 testRequest = getRegisterDatabaseRequestSample();
        testRequest.setAdapterId(null);
        testRequest.setDbHost(TEST_TYPE + "." + TEST_NAMESPACE);
        final List<RegisterDatabaseRequestV3> registerDatabaseRequestList = Collections.singletonList(testRequest);

        mockConnectionPropertiesResponse(dBaaService);
        final RegisterDatabaseResponseBuilder registerDatabaseResponseBuilder = migrationService.registerDatabases(registerDatabaseRequestList, API_VERSION.V1, false);
        assertEquals(Response.Status.OK.getStatusCode(), registerDatabaseResponseBuilder.buildAndResponse().getStatus());
    }

    @Test
    void checkAutodiscoveryOfPhysicalDbByHostSeveralDatabases() {
        PhysicalDatabase targetPhysDb = getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_PHYDBID);
        targetPhysDb.setPhysicalDatabaseIdentifier(TEST_PHYDBID);
        PhysicalDatabase anotherPhysDb = getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_SECOND_PHYDBID);
        anotherPhysDb.setPhysicalDatabaseIdentifier(TEST_SECOND_PHYDBID);
        when(physicalDatabasesService.getPhysicalDatabaseByAdapterHost(TEST_NAMESPACE)).thenReturn(List.of(anotherPhysDb, targetPhysDb));

        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(dbaasAdapter.getDatabases()).thenReturn(Collections.singleton("some-name"))
                .thenReturn(Collections.singleton(TEST_DB_NAME))
                .thenReturn(Collections.singleton(TEST_DB_NAME));

        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(TEST_PHYDBID)).thenReturn(getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_PHYDBID));

        when(dbaasAdapter.identifier()).thenReturn(TEST_ADAPTER_ID);

        RegisterDatabaseRequestV3 testRequest = getRegisterDatabaseRequestSample();
        testRequest.setAdapterId(null);
        testRequest.setDbHost(TEST_TYPE + "." + TEST_NAMESPACE);
        final List<RegisterDatabaseRequestV3> registerDatabaseRequestList = Collections.singletonList(testRequest);

        mockConnectionPropertiesResponse(dBaaService);
        final RegisterDatabaseResponseBuilder registerDatabaseResponseBuilder = migrationService.registerDatabases(registerDatabaseRequestList, API_VERSION.V1, false);
        assertEquals(Response.Status.OK.getStatusCode(), registerDatabaseResponseBuilder.buildAndResponse().getStatus());
    }

    @Test
    void testRegisterExternalAsInternal() {
        PhysicalDatabase physDb = getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_PHYDBID);
        physDb.setPhysicalDatabaseIdentifier(TEST_PHYDBID);
        when(physicalDatabasesService.getPhysicalDatabaseByAdapterHost(TEST_NAMESPACE)).thenReturn(Collections.singletonList(physDb));

        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(TEST_PHYDBID)).thenReturn(getPhysicalDatabaseSample(TEST_ADAPTER_ID, TEST_PHYDBID));

        when(dbaasAdapter.getDatabases()).thenReturn(Collections.singleton(TEST_DB_NAME));
        when(dbaasAdapter.identifier()).thenReturn(TEST_ADAPTER_ID);

        RegisterDatabaseRequestV3 testRequest = getRegisterDatabaseRequestSample();
        testRequest.setAdapterId(null);
        testRequest.setDbHost(TEST_TYPE + "." + TEST_NAMESPACE);

        Database testDb = new Database();
        testDb.setName(TEST_DB_NAME);
        testDb.setPhysicalDatabaseId(TEST_PHYDBID);
        testDb.setExternallyManageable(true);
        testDb.setClassifier(testRequest.getClassifier());
        testDb.setConnectionProperties(List.of(Map.of("username", "username", "password", "password")));
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(testDb);
        testDb.setDatabaseRegistry(Arrays.asList(databaseRegistry));
        when(dBaaService.findDatabaseByOldClassifierAndType(testRequest.getClassifier(), TEST_TYPE, true)).thenReturn(databaseRegistry);
        final List<RegisterDatabaseRequestV3> registerDatabaseRequestList = Collections.singletonList(testRequest);

        mockConnectionPropertiesResponse(dBaaService);
        final RegisterDatabaseResponseBuilder registerDatabaseResponseBuilder = migrationService.registerDatabases(registerDatabaseRequestList, API_VERSION.V1, false);
        assertEquals(Response.Status.OK.getStatusCode(), registerDatabaseResponseBuilder.buildAndResponse().getStatus());

        verify(physicalDatabasesService, times(1)).getPhysicalDatabaseByAdapterHost(TEST_NAMESPACE);
        verify(physicalDatabasesService, times(1)).getAdapterById(TEST_ADAPTER_ID);
        verify(physicalDatabasesService, times(1)).getByPhysicalDatabaseIdentifier(TEST_PHYDBID);
        verify(dbaasAdapter, times(1)).getDatabases();
        verify(dbaasAdapter, times(1)).identifier();
        verifyNoMoreInteractions(databaseRegistryDbaasRepository, physicalDatabasesService, dbaasAdapter);
    }

    private RegisterDatabaseRequestV3 getRegisterDatabaseRequestSample() {
        final RegisterDatabaseRequestV3 registerDatabaseRequest = new RegisterDatabaseRequestV3();
        registerDatabaseRequest.setName(TEST_DB_NAME);
        registerDatabaseRequest.setNamespace(TEST_NAMESPACE);
        registerDatabaseRequest.setAdapterId(TEST_ADAPTER_ID);
        registerDatabaseRequest.setType(TEST_TYPE);
        final Map<String, Object> connectionProperties = new HashMap<>();
        connectionProperties.put(PASSWORD_FIELD, "test-password");
        connectionProperties.put(ROLE, Role.ADMIN.toString());
        registerDatabaseRequest.setConnectionProperties(Arrays.asList(connectionProperties));
        return registerDatabaseRequest;
    }

    private PhysicalDatabase getPhysicalDatabaseSample(String adapterId, String physicalDatabaseId) {
        final PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(physicalDatabaseId);
        physicalDatabase.setAdapter(new ExternalAdapterRegistrationEntry(adapterId, "test-address", new HttpBasicCredentials("", ""), VERSION_2, null));
        physicalDatabase.setType(TEST_TYPE);
        return physicalDatabase;
    }
}
