package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.CreatedDatabaseV3;
import org.qubership.cloud.dbaas.dto.v3.DatabaseCreateRequestV3;
import org.qubership.cloud.dbaas.dto.v3.DatabaseResponseV3SingleCP;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.exceptions.NotSupportedServiceRoleException;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BgNamespaceRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseDeclarativeConfigRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.service.dbsettings.LogicalDbSettingsService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.qubership.cloud.dbaas.Constants.*;
import static jakarta.ws.rs.core.Response.Status.*;
import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.quality.Strictness.LENIENT;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = LENIENT)
public class AggregatedDatabaseAdministrationServiceTest {

    private static final String NAMESPACE = "namespace1";

    private static MockedStatic<QuarkusTransaction> mockedStatic;

    @Mock
    DatabaseDeclarativeConfigRepository declarativeConfigRepository;
    @Mock
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Mock
    DatabaseRegistryRepository databaseRegistryRepository;
    @Mock
    PhysicalDatabasesService physicalDatabasesService;
    @Mock
    DBaaService dBaaService;
    @Mock
    BgNamespaceRepository bgNamespaceRepository;
    @Mock
    Database database;
    @Mock
    ProcessConnectionPropertiesService connectionPropertiesService;
    @Mock
    DatabaseRegistry databaseRegistry;
    @Mock
    LogicalDbSettingsService logicalDbSettingsService;

    @InjectMocks
    AggregatedDatabaseAdministrationService aggregatedDatabaseAdministrationService;

    private final FunctionProvidePassword<Database, String> password = (database, any) -> "pass";
    private DatabaseCreateRequestV3 createRequest;

    @BeforeAll
    static void beforeAll() {
        mockedStatic = mockStatic(QuarkusTransaction.class);
        TransactionRunnerOptions txRunner = mock(TransactionRunnerOptions.class);
        doAnswer(invocationOnMock -> {
            ((Runnable) invocationOnMock.getArgument(0)).run();
            return null;
        }).when(txRunner).run(any());
        mockedStatic.when(QuarkusTransaction::requiringNew).thenReturn(txRunner);
    }

    @AfterAll
    static void afterAll() {
        mockedStatic.close();
    }

    @BeforeEach
    public void setUp() throws Exception {
        Mockito.reset();
        createRequest = new DatabaseCreateRequestV3();
        createRequest.setType("postgresql");
        createRequest.setClassifier(new HashMap<>());
        createRequest.setSettings(new HashMap<>());
        createRequest.getClassifier().put("namespace", NAMESPACE);
        when(declarativeConfigRepository.findFirstByClassifierAndType(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    public void whenCreateDatabaseFromRequest_catchWebApplicationException() {
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenThrow(new ConstraintViolationException("error", new PSQLException("exception", PSQLState.UNIQUE_VIOLATION), "constraint_name"));
        when(databaseRegistry.getDatabase()).thenReturn(database);
        when(dBaaService.findDatabaseByClassifierAndType(eq(createRequest.getClassifier()), eq(createRequest.getType()), eq(true))).thenReturn(databaseRegistry);
        String clientResponseBody = "this is internal server error from client";

        WebApplicationException exception = new WebApplicationException("client message", Response.serverError().entity(clientResponseBody).build());

        when(logicalDbSettingsService.updateSettings(eq(databaseRegistry), eq(createRequest.getSettings()))).thenThrow(exception);

        Response result = aggregatedDatabaseAdministrationService.createDatabaseFromRequest(createRequest, NAMESPACE, password, Role.ADMIN.toString(), null);

        Assertions.assertEquals(INTERNAL_SERVER_ERROR.getStatusCode(), result.getStatus());
        Assertions.assertTrue(Objects.requireNonNull(result.getEntity()).toString().contains(clientResponseBody));
    }

    @Test
    public void whenCreateDatabaseFromRequest_WithBlueGreenDomain() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(connectionPropertiesService);
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenThrow(new ConstraintViolationException("error", new PSQLException("exception", PSQLState.UNIQUE_VIOLATION), "constraint_name"));
        when(database.getDbState()).thenReturn(new DbState(DbState.DatabaseStateStatus.CREATED));
        when(database.getConnectionProperties()).thenReturn(Collections.singletonList(new HashMap<String, Object>() {{
            put("role", Role.ADMIN.toString());
        }}));
        when(dBaaService.detach(databaseRegistry)).thenReturn(databaseRegistry);
        when(databaseRegistry.getDatabase()).thenReturn(database);

        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespace1 = new BgNamespace();
        bgNamespace1.setNamespace(NAMESPACE);
        BgNamespace bgNamespace2 = new BgNamespace();
        bgNamespace2.setNamespace("namespace2");
        bgDomain.setNamespaces(Arrays.asList(bgNamespace1, bgNamespace2));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NAMESPACE)).thenReturn(Optional.of(bgNamespace1));

        when(dBaaService.findDatabaseByClassifierAndType(eq(createRequest.getClassifier()), eq(createRequest.getType()), eq(true))).thenReturn(databaseRegistry);
        Response result = aggregatedDatabaseAdministrationService.createDatabaseFromRequest(createRequest, NAMESPACE, password, Role.ADMIN.toString(), null);

        Assertions.assertEquals(OK.getStatusCode(), result.getStatus());
    }

    @Test
    public void whenCreateDatabaseFromRequest_WithBlueGreenDomain_ShareTransactional() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(connectionPropertiesService);
        AtomicInteger saveCount = new AtomicInteger(0);
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenAnswer(invocation -> {
            if (saveCount.get() == 0) {
                saveCount.incrementAndGet();
                return null;
            } else {
                throw new ConstraintViolationException("error", new PSQLException("exception", PSQLState.UNIQUE_VIOLATION), "constraint_name");
            }
        });
        HashMap<String, Object> anotherClassifier = new HashMap<>(createRequest.getClassifier());
        anotherClassifier.put("namespace", "namespace2");
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(createRequest.getClassifier(), "postgresql")).thenReturn(Optional.empty());
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(anotherClassifier, "postgresql")).thenReturn(Optional.of(databaseRegistry));
        when(database.getDbState()).thenReturn(new DbState(DbState.DatabaseStateStatus.CREATED));
        when(database.getConnectionProperties()).thenReturn(Collections.singletonList(new HashMap<>() {{
            put("role", Role.ADMIN.toString());
        }}));
        when(databaseRegistry.getDbState()).thenReturn(new DbState(DbState.DatabaseStateStatus.CREATED));
        when(dBaaService.detach(databaseRegistry)).thenReturn(databaseRegistry);
        when(databaseRegistry.getDatabase()).thenReturn(database);

        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespace1 = new BgNamespace();
        bgNamespace1.setNamespace(NAMESPACE);
        bgNamespace1.setState(ACTIVE_STATE);
        bgNamespace1.setBgDomain(bgDomain);
        BgNamespace bgNamespace2 = new BgNamespace();
        bgNamespace2.setNamespace("namespace2");
        bgNamespace2.setState(CANDIDATE_STATE);
        bgNamespace2.setBgDomain(bgDomain);
        bgDomain.setNamespaces(Arrays.asList(bgNamespace1, bgNamespace2));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NAMESPACE)).thenReturn(Optional.of(bgNamespace1));

        when(dBaaService.findDatabaseByClassifierAndType(eq(createRequest.getClassifier()), eq(createRequest.getType()), eq(true))).thenReturn(databaseRegistry);
        aggregatedDatabaseAdministrationService.createDatabaseFromRequest(createRequest, NAMESPACE, password, Role.ADMIN.toString(), null);

        verify(databaseRegistryDbaasRepository).saveAnyTypeLogDb(argThat(dbr -> dbr.getDatabase() == database));
    }

    @Test
    public void testCorrectDatabaseCreationWhenDbStateIsCreated() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(connectionPropertiesService);
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenThrow(new ConstraintViolationException("error", new PSQLException("exception", PSQLState.UNIQUE_VIOLATION), "constraint_name"));
        when(database.getDbState()).thenReturn(new DbState(DbState.DatabaseStateStatus.CREATED));
        when(database.getConnectionProperties()).thenReturn(Collections.singletonList(new HashMap<String, Object>() {{
            put("role", Role.ADMIN.toString());
        }}));
        when(databaseRegistry.getDatabase()).thenReturn(database);
        when(dBaaService.detach(databaseRegistry)).thenReturn(databaseRegistry);

        when(dBaaService.findDatabaseByClassifierAndType(eq(createRequest.getClassifier()), eq(createRequest.getType()), eq(true))).thenReturn(databaseRegistry);
        Response result = aggregatedDatabaseAdministrationService.createDatabaseFromRequest(createRequest, NAMESPACE, password, Role.ADMIN.toString(), null);

        Assertions.assertEquals(OK.getStatusCode(), result.getStatus());
    }

    @Test
    public void testReturnsAcceptedWhenDbStateIsProcessing() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(connectionPropertiesService);
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenThrow(new ConstraintViolationException("error", new PSQLException("exception", PSQLState.UNIQUE_VIOLATION), "constraint_name"));
        when(database.getDbState()).thenReturn(new DbState(DbState.DatabaseStateStatus.PROCESSING));
        when(database.getConnectionProperties()).thenReturn(Collections.singletonList(new HashMap<String, Object>() {{
            put("role", Role.ADMIN.toString());
        }}));
        when(dBaaService.detach(databaseRegistry)).thenReturn(databaseRegistry);
        when(databaseRegistry.getDatabase()).thenReturn(database);

        when(dBaaService.findDatabaseByClassifierAndType(eq(createRequest.getClassifier()), eq(createRequest.getType()), eq(true))).thenReturn(databaseRegistry);
        Response result = aggregatedDatabaseAdministrationService.createDatabaseFromRequest(createRequest, NAMESPACE, password, Role.ADMIN.toString(), null);

        Assertions.assertEquals(ACCEPTED.getStatusCode(), result.getStatus());
    }

    @Test
    void testPodNameIsEmptyInCreatedDatabase() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(connectionPropertiesService);
        DatabaseRegistry[] savedDatabase = new DatabaseRegistry[1];
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenAnswer(invocationOnMock -> {
            savedDatabase[0] = invocationOnMock.getArgument(0);
            return savedDatabase[0];
        });
        when(databaseRegistryDbaasRepository.findDatabaseRegistryById(any())).then(invocationOnMock -> Optional.of(savedDatabase[0]));
        final CreatedDatabaseV3 createdDatabase = new CreatedDatabaseV3();
        createdDatabase.setName("created database");
        createdDatabase.setConnectionProperties(singletonList(new HashMap<String, Object>() {{
            put(ROLE, Role.ADMIN.toString());
        }}));
        when(dBaaService.createDatabase(any(), any(), any())).thenReturn(Optional.of(createdDatabase));
        when(dBaaService.isAdapterExists(any(), any(), any())).thenReturn(true);
        final PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        when(physicalDatabasesService.getByAdapterId(any())).thenReturn(physicalDatabase);
        aggregatedDatabaseAdministrationService.createDatabaseFromRequest(createRequest, NAMESPACE, password, Role.ADMIN.toString(), null);
        Assertions.assertNull(savedDatabase[0].getDbState().getPodName());
    }

    @Test
    void testUpdateDatabaseVersionWithConfiguration() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(connectionPropertiesService);
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class)))
                .thenThrow(new ConstraintViolationException("error", new PSQLException("exception", PSQLState.UNIQUE_VIOLATION), "constraint_name"));
        when(database.getDbState()).thenReturn(new DbState(DbState.DatabaseStateStatus.CREATED));
        when(database.getConnectionProperties()).thenReturn(Collections.singletonList(new HashMap<String, Object>() {{
            put("role", Role.ADMIN.toString());
        }}));
        when(databaseRegistry.getDatabase()).thenReturn(database);
        when(database.getBgVersion()).thenReturn("v1");
        when(dBaaService.detach(databaseRegistry)).thenReturn(databaseRegistry);

        when(dBaaService.findDatabaseByClassifierAndType(eq(createRequest.getClassifier()), eq(createRequest.getType()), eq(true)))
                .thenReturn(databaseRegistry);

        DatabaseDeclarativeConfig databaseDeclarativeConfig = mock(DatabaseDeclarativeConfig.class);
        when(databaseDeclarativeConfig.getClassifier()).thenReturn(new TreeMap<>(createRequest.getClassifier()));
        when(databaseDeclarativeConfig.getType()).thenReturn(createRequest.getType());
        when(databaseDeclarativeConfig.getVersioningType()).thenReturn(STATIC_STATE);
        when(declarativeConfigRepository.
                findFirstByClassifierAndType(createRequest.getClassifier(), createRequest.getType())).thenReturn(Optional.of(databaseDeclarativeConfig));

        Response result = aggregatedDatabaseAdministrationService.createDatabaseFromRequest(createRequest,
                NAMESPACE, password, Role.ADMIN.toString(), null);

        verify(databaseRegistryDbaasRepository).saveInternalDatabase(argThat(dbr -> dbr.getBgVersion() == null));

        Assertions.assertEquals(OK.getStatusCode(), result.getStatus());
    }

    @Test
    void testUpdateDatabaseToVersionWithConfiguration() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(connectionPropertiesService);
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class)))
                .thenThrow(new ConstraintViolationException("error", new PSQLException("exception", PSQLState.UNIQUE_VIOLATION), "constraint_name"));
        when(database.getDbState()).thenReturn(new DbState(DbState.DatabaseStateStatus.CREATED));
        when(database.getConnectionProperties()).thenReturn(Collections.singletonList(new HashMap<String, Object>() {{
            put("role", Role.ADMIN.toString());
        }}));
        when(databaseRegistry.getDatabase()).thenReturn(database);
        when(database.getBgVersion()).thenReturn(null);
        when(dBaaService.detach(databaseRegistry)).thenReturn(databaseRegistry);
        BgNamespace bgNamespace = new BgNamespace();
        bgNamespace.setNamespace("namespace1");
        BgDomain bgDomain = new BgDomain();
        bgDomain.setNamespaces(List.of(bgNamespace));
        bgNamespace.setBgDomain(bgDomain);
        when(bgNamespaceRepository.findBgNamespaceByNamespace(any())).thenReturn(Optional.of(bgNamespace));

        when(dBaaService.findDatabaseByClassifierAndType(eq(createRequest.getClassifier()), eq(createRequest.getType()), eq(true)))
                .thenReturn(databaseRegistry);

        DatabaseDeclarativeConfig databaseDeclarativeConfig = mock(DatabaseDeclarativeConfig.class);
        when(databaseDeclarativeConfig.getClassifier()).thenReturn(new TreeMap<>(createRequest.getClassifier()));
        when(databaseDeclarativeConfig.getType()).thenReturn(createRequest.getType());
        when(databaseDeclarativeConfig.getVersioningType()).thenReturn(VERSION_STATE);
        when(declarativeConfigRepository.
                findFirstByClassifierAndType(createRequest.getClassifier(), createRequest.getType())).thenReturn(Optional.of(databaseDeclarativeConfig));

        Response result = aggregatedDatabaseAdministrationService.createDatabaseFromRequest(createRequest,
                NAMESPACE, password, Role.ADMIN.toString(), "v1");

        verify(database).setBgVersion("v1");

        Assertions.assertEquals(OK.getStatusCode(), result.getStatus());
    }

    @Test
    void testcreateDatabaseFromRequest_GivenOwnerAndNotAdminRole_databaseIsCreated() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(connectionPropertiesService);
        DatabaseRegistry[] savedDatabase = new DatabaseRegistry[1];
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenAnswer(invocationOnMock -> {
            savedDatabase[0] = invocationOnMock.getArgument(0);
            return savedDatabase[0];
        });
        when(databaseRegistryDbaasRepository.findDatabaseRegistryById(any())).then(invocationOnMock -> Optional.of(savedDatabase[0]));
        doAnswer(invocation -> {
            savedDatabase[0] = null;
            return null;
        }).when(databaseRegistryDbaasRepository).delete(any(DatabaseRegistry.class));

        String requestedServiceRole = "ism";

        final CreatedDatabaseV3 createdDatabase = new CreatedDatabaseV3();
        createdDatabase.setName("created database");
        createdDatabase.setConnectionProperties(
                List.of(
                        new HashMap<>() {{
                            put(ROLE, Role.ADMIN.toString());
                        }}, new HashMap<>() {{
                            put(ROLE, requestedServiceRole);
                        }}
                )
        );
        when(dBaaService.createDatabase(any(), any(), any())).thenReturn(Optional.of(createdDatabase));
        when(dBaaService.isAdapterExists(any(), any(), any())).thenReturn(true);
        final PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        when(physicalDatabasesService.getByAdapterId(any())).thenReturn(physicalDatabase);

        // 1 checking not owner
        createRequest.getClassifier().put("microserviceName", "ms_name");
        createRequest.setOriginService("another_ms_name");

        // 1.1 not admin role
        Assertions.assertThrowsExactly(NotSupportedServiceRoleException.class,
                () -> aggregatedDatabaseAdministrationService.createDatabaseFromRequest(createRequest, NAMESPACE, password, requestedServiceRole, null));
        Assertions.assertNull(savedDatabase[0]);

        // 1.2 admin role
        Response response1 = aggregatedDatabaseAdministrationService.createDatabaseFromRequest(createRequest, NAMESPACE, password, Role.ADMIN.toString(), null);
        Assertions.assertNotNull(savedDatabase[0]);
        Assertions.assertEquals(Role.ADMIN.toString(), ((DatabaseResponseV3SingleCP) response1.getEntity()).getConnectionProperties().get(ROLE));

        // 2 checking owner
        createRequest.getClassifier().put("microserviceName", "ms_name");
        createRequest.setOriginService("ms_name");

        // 2.1 not admin role
        Response response2 = aggregatedDatabaseAdministrationService.createDatabaseFromRequest(createRequest, NAMESPACE, password, requestedServiceRole, null);
        Assertions.assertNotNull(savedDatabase[0]);
        Assertions.assertEquals(requestedServiceRole, ((DatabaseResponseV3SingleCP) response2.getEntity()).getConnectionProperties().get(ROLE));

        // 2.2 admin role
        Response response3 = aggregatedDatabaseAdministrationService.createDatabaseFromRequest(createRequest, NAMESPACE, password, Role.ADMIN.toString(), null);
        Assertions.assertNotNull(savedDatabase[0]);
        Assertions.assertEquals(Role.ADMIN.toString(), ((DatabaseResponseV3SingleCP) response3.getEntity()).getConnectionProperties().get(ROLE));
    }
}
