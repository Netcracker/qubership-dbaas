package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.dto.HttpBasicCredentials;
import com.netcracker.cloud.dbaas.dto.RegisteredPhysicalDatabasesDTO;
import com.netcracker.cloud.dbaas.dto.role.Role;
import com.netcracker.cloud.dbaas.dto.v3.*;
import com.netcracker.cloud.dbaas.entity.pg.*;
import com.netcracker.cloud.dbaas.exceptions.AdapterUnavailableException;
import com.netcracker.cloud.dbaas.exceptions.NotFoundException;
import com.netcracker.cloud.dbaas.exceptions.PhysicalDatabaseRegistrationConflictException;
import com.netcracker.cloud.dbaas.exceptions.UnregisteredPhysicalDatabaseException;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.netcracker.cloud.dbaas.DbaasApiPath.VERSION_2;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PhysicalDatabasesServiceTest {

    private static final String TEST_USERNAME = "test-username";
    private static final String TEST_PASSWORD = "test-password";
    private static final String TEST_ADAPTER_ID = "test-adapter-id";
    private static final String TEST_ADAPTER_ADDRESS = "test-adapter-address";
    private static final String TEST_TYPE = "test-type";
    private static final String TEST_PHYDBID = "test-phydbid";
    private static final String TEST_LABEL_KEY = "db_cpq_domain";
    private static final String TEST_LABEL_VALUE = "cpq";
    private static final String TEST_RO_HOST = "test-ro-host";
    private static final Map<String, String> TEST_LABELS = new HashMap<String, String>() {{
        put(TEST_LABEL_KEY, TEST_LABEL_VALUE);
    }};

    @Mock
    private PasswordEncryption encryption;
    @Mock
    private PhysicalDatabaseDbaasRepository physicalDatabaseDbaasRepository;
    @Mock
    private PhysicalDatabaseRegistrationHandshakeClient handshakeClient;
    @Mock
    private AdapterActionTrackerClient tracker;
    @Mock
    private DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Mock
    private LogicalDbDbaasRepository logicalDbDbaasRepository;
    @Mock
    private DbaasAdapterRESTClientFactory dbaasAdapterRESTClientFactory;

    @InjectMocks
    private PhysicalDatabasesService physicalDatabasesService;

    @Test
    void testPhysicalDatabaseCache() {
        PhysicalDatabase physicalDatabaseMock = new PhysicalDatabase();
        physicalDatabaseMock.setRoHost("ro-host");
        when(physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(eq("test-id"))).thenReturn(physicalDatabaseMock);
        PhysicalDatabase physicalDatabase = physicalDatabasesService.searchInPhysicalDatabaseCache("test-id");
        assertEquals(physicalDatabaseMock, physicalDatabase);

    }

    @Test
    void testPhysicalDatabaseCache_dbNotFound() {
        assertThrows(NotFoundException.class,
                () -> physicalDatabasesService.searchInPhysicalDatabaseCache("phyDbId"));
    }

    @Test
    void testGetRegisteredDatabases() {
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        when(physicalDatabaseDbaasRepository.findByType(TEST_TYPE)).thenReturn(Stream.of(physicalDatabase));
        List<PhysicalDatabase> actualPhysicalDBList = physicalDatabasesService.getRegisteredDatabases(TEST_TYPE);
        assertNotNull(actualPhysicalDBList);
        assertEquals(1, actualPhysicalDBList.size());
        assertEquals(physicalDatabase.getId(), actualPhysicalDBList.get(0).getId());
        assertEquals(physicalDatabase.getAdapter().getAdapterId(), actualPhysicalDBList.get(0).getAdapter().getAdapterId());

        verify(physicalDatabaseDbaasRepository).findByType(TEST_TYPE);
        verifyNoMoreInteractions(physicalDatabaseDbaasRepository);
    }

    @Test
    void testGetByPhysicalDatabaseIdentifier() {
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        when(physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(TEST_PHYDBID)).thenReturn(physicalDatabase);
        PhysicalDatabase actualPhysicalDB = physicalDatabasesService.getByPhysicalDatabaseIdentifier(TEST_PHYDBID);

        assertNotNull(actualPhysicalDB);
        assertEquals(physicalDatabase.getId(), actualPhysicalDB.getId());
        assertEquals(physicalDatabase.getAdapter().getAdapterId(), actualPhysicalDB.getAdapter().getAdapterId());

        verify(physicalDatabaseDbaasRepository).findByPhysicalDatabaseIdentifier(TEST_PHYDBID);
        verifyNoMoreInteractions(physicalDatabaseDbaasRepository);
    }

    @Test
    void writeChanges_updatesExistingDatabaseWithRequestValues() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistrationRequestSample();
        physicalDatabaseRegistryRequest.setAdapterAddress("http://updated-adapter:8080");
        physicalDatabaseRegistryRequest.setLabels(Map.of("db_ext_domain", "ext"));
        physicalDatabaseRegistryRequest.getMetadata().setSupportedRoles(new ArrayList<>(List.of("admin", "readwrite")));
        physicalDatabaseRegistryRequest.getMetadata().setApiVersions(new ApiVersion(List.of(new ApiVersion.Spec("/api", 2, 3, List.of(2)))));
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();

        physicalDatabasesService.writeChanges(TEST_PHYDBID, physicalDatabaseRegistryRequest, physicalDatabase);

        verify(encryption).deletePassword(physicalDatabase);
        verify(encryption).encryptPassword(physicalDatabaseRegistryRequest);
        verify(physicalDatabaseDbaasRepository).save(physicalDatabase);
        assertEquals(Map.of("db_ext_domain", "ext"), physicalDatabase.getLabels());
        assertEquals(List.of("admin", "readwrite"), physicalDatabase.getRoles());
        assertEquals(Map.of("multiusers", true), physicalDatabase.getFeatures());
        assertEquals(TEST_RO_HOST, physicalDatabase.getRoHost());
        assertEquals("http://updated-adapter:8080", physicalDatabase.getAdapter().getAddress());
        assertEquals(physicalDatabaseRegistryRequest.getMetadata().getApiVersion(), physicalDatabase.getAdapter().getSupportedVersion());
        assertEquals(new ApiVersion(List.of(new ApiVersion.Spec("/api", 2, 3, List.of(2)))), physicalDatabase.getAdapter().getApiVersions());
        assertSame(physicalDatabaseRegistryRequest.getHttpBasicCredentials(), physicalDatabase.getAdapter().getHttpBasicCredentials());
    }

    @Test
    void foundPhysicalDatabase_allowsChangingAdapterProtocol() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistrationRequestSample();
        physicalDatabaseRegistryRequest.setAdapterAddress("http://some-adapter");
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        physicalDatabase.getAdapter().setAddress("https://some-adapter");
        when(physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(TEST_PHYDBID)).thenReturn(physicalDatabase);

        final Optional<PhysicalDatabase> foundPhysicalDatabase =
                physicalDatabasesService.foundPhysicalDatabase(TEST_PHYDBID, TEST_TYPE, physicalDatabaseRegistryRequest);

        assertTrue(foundPhysicalDatabase.isPresent());
        assertSame(physicalDatabase, foundPhysicalDatabase.get());
    }

    @Test
    void foundPhysicalDatabase_throwsWhenAdapterAddressOfRegisteredDatabaseDiffers() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistrationRequestSample();
        physicalDatabaseRegistryRequest.setAdapterAddress("http://another-adapter");
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        physicalDatabase.getAdapter().setAddress("http://some-adapter");
        when(physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(TEST_PHYDBID)).thenReturn(physicalDatabase);

        assertThrows(PhysicalDatabaseRegistrationConflictException.class,
                () -> physicalDatabasesService.foundPhysicalDatabase(TEST_PHYDBID, TEST_TYPE, physicalDatabaseRegistryRequest));
    }

    @Test
    void testFoundPhysicalDatabase_missingHttpBasicCredentialsFailsBeforeHandshake() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistryRequestSample();
        physicalDatabaseRegistryRequest.setHttpBasicCredentials(null);

        // The registration endpoint maps this exception to a 500 response.
        assertThrows(NullPointerException.class,
                () -> physicalDatabasesService.foundPhysicalDatabase(TEST_PHYDBID, TEST_TYPE, physicalDatabaseRegistryRequest));
        verifyNoInteractions(handshakeClient, physicalDatabaseDbaasRepository);
    }

    @Test
    void testBalanceByType() {
        final PhysicalDatabase physicalDatabaseWithGlobalFalse = getPhysicalDatabaseSample();
        physicalDatabaseWithGlobalFalse.setGlobal(false);
        physicalDatabaseWithGlobalFalse.setId("another-id");
        final PhysicalDatabase physicalDatabaseWithGlobalTrue = getPhysicalDatabaseSample();
        physicalDatabaseWithGlobalTrue.setGlobal(true);
        when(physicalDatabaseDbaasRepository.findByType(TEST_TYPE)).thenReturn(Stream.of(physicalDatabaseWithGlobalFalse, physicalDatabaseWithGlobalTrue));

        final PhysicalDatabase actualPhysicalDatabase = physicalDatabasesService.balanceByType(TEST_TYPE);

        assertEquals(TEST_PHYDBID, actualPhysicalDatabase.getId());
        assertTrue(actualPhysicalDatabase.isGlobal());

        verify(physicalDatabaseDbaasRepository, times(1)).findByType(TEST_TYPE);
        verifyNoMoreInteractions(physicalDatabaseDbaasRepository);
    }

    @Test
    void testBalanceTypeWithUnregisteredPhysicalDatabaseException() {
        when(physicalDatabaseDbaasRepository.findByType(TEST_TYPE)).thenReturn(Stream.empty());
        Assertions.assertThrows(UnregisteredPhysicalDatabaseException.class, () -> {
            physicalDatabasesService.balanceByType(TEST_TYPE);
        });
    }

    @Test
    void testAdapterById() {
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        when(physicalDatabaseDbaasRepository.findByAdapterId(TEST_ADAPTER_ID)).thenReturn(physicalDatabase);
        when(dbaasAdapterRESTClientFactory.createDbaasAdapterClientV2(any(), any(), any(), eq(TEST_TYPE), eq(TEST_ADAPTER_ID), any(), eq(physicalDatabase.getAdapter().getApiVersions())))
                .thenReturn(new DbaasAdapterRESTClientV2("test-address", TEST_TYPE, null, TEST_ADAPTER_ID, tracker));
        when(encryption.decrypt(any())).thenAnswer((invocation) -> invocation.getArgument(0));
        final DbaasAdapter actualDbaasAdapter = physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID);
        assertNotNull(actualDbaasAdapter);
        assertEquals(TEST_ADAPTER_ID, actualDbaasAdapter.identifier());
        assertEquals(TEST_TYPE, actualDbaasAdapter.type());
    }

    @Test
    void testAdapterByIdUsesCache() {
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        when(physicalDatabaseDbaasRepository.findByAdapterId(TEST_ADAPTER_ID)).thenReturn(physicalDatabase);
        when(dbaasAdapterRESTClientFactory.createDbaasAdapterClientV2(any(), any(), any(), eq(TEST_TYPE), eq(TEST_ADAPTER_ID), any(), eq(physicalDatabase.getAdapter().getApiVersions())))
                .thenReturn(new DbaasAdapterRESTClientV2("test-address", TEST_TYPE, null, TEST_ADAPTER_ID, tracker));
        when(encryption.decrypt(any())).thenAnswer((invocation) -> invocation.getArgument(0));
        physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID);
        physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID);
        Mockito.verify(physicalDatabaseDbaasRepository, Mockito.times(1))
                .findByAdapterId(TEST_ADAPTER_ID);
        Mockito.verify(dbaasAdapterRESTClientFactory, Mockito.times(1))
                .createDbaasAdapterClientV2(any(), any(), any(), eq(TEST_TYPE), eq(TEST_ADAPTER_ID), any(), eq(physicalDatabase.getAdapter().getApiVersions()));
    }

    @Test
    void testPhyDbContainsConnectedLogicalDb() {
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        final DatabaseRegistry database = generateRandomDatabase(true, physicalDatabase.getAdapter().getAdapterId());
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.findAllInternalDatabases()).thenReturn(Collections.singletonList(database));
        assertTrue(physicalDatabasesService.checkContainsConnectedLogicalDb(physicalDatabase));
    }

    @Test
    void testPhyDbContainsConnectedLogicalDbMarkedForDrop() {
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        final DatabaseRegistry database = generateRandomDatabase(true, physicalDatabase.getAdapter().getAdapterId());
        database.setMarkedForDrop(true);
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.findAllInternalDatabases()).thenReturn(Collections.singletonList(database));
        assertFalse(physicalDatabasesService.checkContainsConnectedLogicalDb(physicalDatabase));
    }

    @Test
    void testPhyDbDoesNotContainsConnectedLogicalDb() {
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.findAllInternalDatabases()).thenReturn(Collections.emptyList());
        assertFalse(physicalDatabasesService.checkContainsConnectedLogicalDb(physicalDatabase));
    }

    @Test
    void testGetPhysicalDatabaseContainsLabel() {
        PhysicalDatabase testPhysicalDatabase1 = getPhysicalDatabaseSample();
        PhysicalDatabase testPhysicalDatabase2 = getPhysicalDatabaseSample();
        testPhysicalDatabase2.setLabels(new HashMap<String, String>() {{
            put("db_ext_domain", "ext");
        }});

        Stream<PhysicalDatabase> databases = Stream.of(testPhysicalDatabase1, testPhysicalDatabase2);
        when(physicalDatabaseDbaasRepository.findByType(TEST_TYPE)).thenReturn(databases);

        List<PhysicalDatabase> result = physicalDatabasesService.getPhysicalDatabaseContainsLabel(TEST_LABEL_KEY, TEST_LABEL_VALUE, TEST_TYPE);
        assertEquals(1, result.size());
        assertEquals(result.get(0), testPhysicalDatabase1);
    }

    @Test
    void testGetPhysicalDatabaseContainsLabelWhenNullLabelsFromAdapter() {
        PhysicalDatabase testPhysicalDatabase1 = getPhysicalDatabaseSample();
        testPhysicalDatabase1.setLabels(Collections.emptyMap());
        PhysicalDatabase testPhysicalDatabase2 = getPhysicalDatabaseSample();
        testPhysicalDatabase2.setLabels(null);

        Stream<PhysicalDatabase> databases = Stream.of(testPhysicalDatabase1, testPhysicalDatabase2);
        when(physicalDatabaseDbaasRepository.findByType(TEST_TYPE)).thenReturn(databases);

        assertDoesNotThrow(() -> {
            List<PhysicalDatabase> foundPhysDbs = physicalDatabasesService.getPhysicalDatabaseContainsLabel(TEST_LABEL_KEY, TEST_LABEL_VALUE, TEST_TYPE);
            assertTrue(foundPhysDbs.isEmpty());
        });
    }

    @Test
    void foundPhysicalDatabaseTest() throws AdapterUnavailableException,
            PhysicalDatabaseRegistrationConflictException {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistryRequestV3Sample();
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        when(physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(TEST_PHYDBID)).thenReturn(physicalDatabase);
        when(physicalDatabaseDbaasRepository.findByAdapterAddress(TEST_ADAPTER_ADDRESS)).thenReturn(physicalDatabase);

        physicalDatabase.setPhysicalDatabaseIdentifier(TEST_PHYDBID);
        Optional<PhysicalDatabase> testPhysicalDatabase = physicalDatabasesService.foundPhysicalDatabase(TEST_PHYDBID, TEST_TYPE, physicalDatabaseRegistryRequest);
        assertTrue(testPhysicalDatabase.isPresent());

        verify(physicalDatabaseDbaasRepository, times(1)).findByPhysicalDatabaseIdentifier(TEST_PHYDBID);
        verify(physicalDatabaseDbaasRepository, times(1)).findByAdapterAddress(TEST_ADAPTER_ADDRESS);
        verifyNoMoreInteractions(handshakeClient, physicalDatabaseDbaasRepository);

    }


    @Test
    void foundPhysicalDatabaseTestWithException() throws AdapterUnavailableException,
            PhysicalDatabaseRegistrationConflictException {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistryRequestV3Sample();
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        when(physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(TEST_PHYDBID)).thenReturn(physicalDatabase);
        when(physicalDatabaseDbaasRepository.findByAdapterAddress(TEST_ADAPTER_ADDRESS)).thenReturn(physicalDatabase);

        physicalDatabase.setPhysicalDatabaseIdentifier("another-id");

        Assertions.assertThrows(PhysicalDatabaseRegistrationConflictException.class, () -> {
            physicalDatabasesService.foundPhysicalDatabase(TEST_PHYDBID, TEST_TYPE, physicalDatabaseRegistryRequest);
        });

    }

    @Test
    void notFoundPhysicalDatabaseTest() throws AdapterUnavailableException,
            PhysicalDatabaseRegistrationConflictException {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistryRequestV3Sample();
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        when(physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(TEST_PHYDBID)).thenReturn(null);
        when(physicalDatabaseDbaasRepository.findByAdapterAddress(TEST_ADAPTER_ADDRESS)).thenReturn(null);

        physicalDatabase.setPhysicalDatabaseIdentifier(TEST_PHYDBID);
        Optional<PhysicalDatabase> testPhysicalDatabase = physicalDatabasesService.foundPhysicalDatabase(TEST_PHYDBID, TEST_TYPE, physicalDatabaseRegistryRequest);
        assertFalse(testPhysicalDatabase.isPresent());

        verify(physicalDatabaseDbaasRepository, times(1)).findByPhysicalDatabaseIdentifier(TEST_PHYDBID);
        verify(physicalDatabaseDbaasRepository, times(1)).findByAdapterAddress(TEST_ADAPTER_ADDRESS);
        verifyNoMoreInteractions(handshakeClient, physicalDatabaseDbaasRepository);

    }

    @Test
    void physicalDatabaseRegistration_persistsValuesFromRegistrationRequest() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistrationRequestSample();
        when(physicalDatabaseDbaasRepository.findByType(TEST_TYPE)).thenReturn(Stream.empty());
        when(physicalDatabaseDbaasRepository.save(any(PhysicalDatabase.class))).thenAnswer((invocation) -> invocation.getArgument(0));

        final PhysicalDatabase physicalDatabase =
                physicalDatabasesService.physicalDatabaseRegistration(TEST_PHYDBID, TEST_TYPE, physicalDatabaseRegistryRequest);

        final ArgumentCaptor<PhysicalDatabase> savedPhysicalDatabase = ArgumentCaptor.forClass(PhysicalDatabase.class);
        verify(physicalDatabaseDbaasRepository).findByType(TEST_TYPE);
        verify(physicalDatabaseDbaasRepository).save(savedPhysicalDatabase.capture());
        verifyNoMoreInteractions(physicalDatabaseDbaasRepository);
        verify(encryption).encryptPassword(physicalDatabaseRegistryRequest);
        // the adapter handshake and the conflict checks belong to foundPhysicalDatabase, not to the registration itself
        verifyNoInteractions(handshakeClient);
        assertSame(savedPhysicalDatabase.getValue(), physicalDatabase, "The saved physical database must be returned");

        assertEquals(TEST_PHYDBID, physicalDatabase.getPhysicalDatabaseIdentifier());
        assertEquals(TEST_TYPE, physicalDatabase.getType());
        assertEquals(TEST_LABELS, physicalDatabase.getLabels());
        assertEquals(TEST_RO_HOST, physicalDatabase.getRoHost());
        assertEquals(Map.of("multiusers", true), physicalDatabase.getFeatures());
        assertFalse(physicalDatabase.isUnidentified());
        assertNotNull(physicalDatabase.getRegistrationDate());
        assertEquals(TEST_ADAPTER_ADDRESS, physicalDatabase.getAdapter().getAddress());
        assertSame(physicalDatabaseRegistryRequest.getHttpBasicCredentials(), physicalDatabase.getAdapter().getHttpBasicCredentials());
        assertNotNull(physicalDatabase.getAdapter().getAdapterId());
    }

    @Test
    void physicalDatabaseRegistration_usesAdapterApiVersionsFromRequest() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistrationRequestSample();
        when(physicalDatabaseDbaasRepository.findByType(TEST_TYPE)).thenReturn(Stream.empty());
        when(physicalDatabaseDbaasRepository.save(any(PhysicalDatabase.class))).thenAnswer((invocation) -> invocation.getArgument(0));

        final PhysicalDatabase physicalDatabase =
                physicalDatabasesService.physicalDatabaseRegistration(TEST_PHYDBID, TEST_TYPE, physicalDatabaseRegistryRequest);

        assertSame(physicalDatabaseRegistryRequest.getMetadata().getApiVersions(), physicalDatabase.getAdapter().getApiVersions());
        assertFalse(physicalDatabase.getAdapter().getApiVersions().getSpecs().isEmpty());
        assertEquals(physicalDatabaseRegistryRequest.getMetadata().getApiVersion(), physicalDatabase.getAdapter().getSupportedVersion());
    }

    @Test
    void physicalDatabaseRegistration_storesRolesFromRequestInLowerCase() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistrationRequestSample();
        physicalDatabaseRegistryRequest.getMetadata().setSupportedRoles(new ArrayList<>(List.of("ADMIN", "ReadWrite")));
        when(physicalDatabaseDbaasRepository.findByType(TEST_TYPE)).thenReturn(Stream.empty());
        when(physicalDatabaseDbaasRepository.save(any(PhysicalDatabase.class))).thenAnswer((invocation) -> invocation.getArgument(0));

        final PhysicalDatabase physicalDatabase =
                physicalDatabasesService.physicalDatabaseRegistration(TEST_PHYDBID, TEST_TYPE, physicalDatabaseRegistryRequest);

        assertEquals(List.of("admin", "readwrite"), physicalDatabase.getRoles());
        assertEquals(List.of("admin", "readwrite"), physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles(),
                "Roles of the request are normalized, the migration and isDbActual rely on it");
    }

    @Test
    void physicalDatabaseRegistration_marksGlobalWhenItIsFirstDatabaseOfTheType() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistrationRequestSample();
        when(physicalDatabaseDbaasRepository.findByType(TEST_TYPE)).thenReturn(Stream.empty());
        when(physicalDatabaseDbaasRepository.save(any(PhysicalDatabase.class))).thenAnswer((invocation) -> invocation.getArgument(0));

        final PhysicalDatabase physicalDatabase =
                physicalDatabasesService.physicalDatabaseRegistration(TEST_PHYDBID, TEST_TYPE, physicalDatabaseRegistryRequest);

        assertTrue(physicalDatabase.isGlobal());
    }

    @Test
    void physicalDatabaseRegistration_doesNotMarkGlobalWhenTypeIsAlreadyRegistered() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistrationRequestSample();
        when(physicalDatabaseDbaasRepository.findByType(TEST_TYPE)).thenReturn(Stream.of(getPhysicalDatabaseSample()));
        when(physicalDatabaseDbaasRepository.save(any(PhysicalDatabase.class))).thenAnswer((invocation) -> invocation.getArgument(0));

        final PhysicalDatabase physicalDatabase =
                physicalDatabasesService.physicalDatabaseRegistration(TEST_PHYDBID, TEST_TYPE, physicalDatabaseRegistryRequest);

        assertFalse(physicalDatabase.isGlobal());
    }

    @Test
    void writeChanges_identifiesUnidentifiedPhysicalDatabase() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistrationRequestSample();
        final PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        physicalDatabase.setPhysicalDatabaseIdentifier("identifier-from-previous-release");
        physicalDatabase.setUnidentified(true);

        physicalDatabasesService.writeChanges(TEST_PHYDBID, physicalDatabaseRegistryRequest, physicalDatabase);

        assertEquals(TEST_PHYDBID, physicalDatabase.getPhysicalDatabaseIdentifier());
        assertFalse(physicalDatabase.isUnidentified());
        verify(physicalDatabaseDbaasRepository).save(physicalDatabase);
    }


    private PhysicalDatabaseRegistryRequestV3 getPhysicalDatabaseRegistryRequestV3Sample() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequestV3 = new PhysicalDatabaseRegistryRequestV3();
        Map<String, Boolean> features = new HashMap<>();
        features.put("multiusers", true);
        physicalDatabaseRegistryRequestV3.setAdapterAddress("test-address");
        physicalDatabaseRegistryRequestV3.setHttpBasicCredentials(new HttpBasicCredentials(TEST_USERNAME, TEST_PASSWORD));
        physicalDatabaseRegistryRequestV3.setAdapterAddress(TEST_ADAPTER_ADDRESS);
        physicalDatabaseRegistryRequestV3.setStatus("running");
        physicalDatabaseRegistryRequestV3.setMetadata(new Metadata("API version",
                Arrays.asList(Role.ADMIN.toString(), "ro"), features));
        return physicalDatabaseRegistryRequestV3;
    }


    private PhysicalDatabase getPhysicalDatabaseSample() {
        final PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(TEST_PHYDBID);
        physicalDatabase.setPhysicalDatabaseIdentifier(TEST_PHYDBID);
        physicalDatabase.setType(TEST_TYPE);
        physicalDatabase.setLabels(TEST_LABELS);
        physicalDatabase.setRoles(Collections.singletonList(Role.ADMIN.toString()));
        physicalDatabase.setAdapter(new ExternalAdapterRegistrationEntry(TEST_ADAPTER_ID, TEST_ADAPTER_ADDRESS,
                getHttpBasicCredentialsSample(), VERSION_2,
                new ApiVersion(List.of(new ApiVersion.Spec("/api", 1, 2, List.of(1))))));
        return physicalDatabase;
    }

    private PhysicalDatabaseRegistryRequestV3 getPhysicalDatabaseRegistryRequestSample() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = new PhysicalDatabaseRegistryRequestV3();
        physicalDatabaseRegistryRequest.setAdapterAddress(TEST_ADAPTER_ADDRESS);
        physicalDatabaseRegistryRequest.setHttpBasicCredentials(getHttpBasicCredentialsSample());
        Metadata metadata = new Metadata();
        metadata.setApiVersion("v2");
        metadata.setSupportedRoles(List.of("admin"));
        metadata.setApiVersions(new ApiVersion(List.of(new ApiVersion.Spec("/api", 1, 2, List.of(1)))));
        physicalDatabaseRegistryRequest.setMetadata(metadata);
        return physicalDatabaseRegistryRequest;
    }

    /**
     * A complete V3 registration request: labels, features, RO host and API versions are all filled in.
     */
    private PhysicalDatabaseRegistryRequestV3 getPhysicalDatabaseRegistrationRequestSample() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = new PhysicalDatabaseRegistryRequestV3();
        physicalDatabaseRegistryRequest.setAdapterAddress(TEST_ADAPTER_ADDRESS);
        physicalDatabaseRegistryRequest.setHttpBasicCredentials(getHttpBasicCredentialsSample());
        physicalDatabaseRegistryRequest.setLabels(TEST_LABELS);
        physicalDatabaseRegistryRequest.setStatus("running");
        final Metadata metadata = new Metadata();
        metadata.setApiVersion(VERSION_2);
        metadata.setSupportedRoles(new ArrayList<>(List.of("admin", "ro")));
        metadata.setFeatures(new HashMap<>(Map.of("multiusers", true)));
        metadata.setApiVersions(new ApiVersion(List.of(new ApiVersion.Spec("/api", 2, 1, List.of(2)))));
        metadata.setRoHost(TEST_RO_HOST);
        physicalDatabaseRegistryRequest.setMetadata(metadata);
        return physicalDatabaseRegistryRequest;
    }

    @SuppressWarnings("unchecked")
    private <T> ConcurrentHashMap<String, T> readCache(String cacheName) throws Exception {
        final Field cacheField = PhysicalDatabasesService.class.getDeclaredField(cacheName);
        cacheField.setAccessible(true);
        return (ConcurrentHashMap<String, T>) cacheField.get(physicalDatabasesService);
    }

    private HttpBasicCredentials getHttpBasicCredentialsSample() {
        return new HttpBasicCredentials(TEST_USERNAME, TEST_PASSWORD);
    }

    private DatabaseRegistry generateRandomDatabase(boolean isServiceDb, String adapterId) {
        Database db = new Database();
        db.setId(UUID.randomUUID());
        db.setType(TEST_TYPE);
        db.setAdapterId(adapterId);
        db.setName("name-" + UUID.randomUUID().toString());
        String namespace = "namespace-" + UUID.randomUUID().toString();
        db.setNamespace(namespace);
        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", namespace);
        classifier.put("microserviceName", "microserviceName-" + UUID.randomUUID().toString());
        if (isServiceDb) {
            classifier.put("isServiceDb", true);
        } else {
            classifier.put("tenantId", UUID.randomUUID().toString());
        }
        db.setClassifier(classifier);
        HashMap<String, Object> connectionProperties = new HashMap<>();
        connectionProperties.put("username", UUID.randomUUID().toString());
        connectionProperties.put("password", UUID.randomUUID().toString());
        connectionProperties.put("encryptedPassword", connectionProperties.get("password"));

        db.setConnectionProperties(Arrays.asList(connectionProperties));

        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType(TEST_TYPE);
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setDatabase(db);
        db.setDatabaseRegistry(List.of(databaseRegistry));
        return databaseRegistry;
    }

    @Test
    void getAdapterByPhysDbIdAndPhysicalDbExists() {
        String physicDbId = "123", adapterId = "adapterId_123";
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setType(TEST_TYPE);
        physicalDatabase.setAdapter(new ExternalAdapterRegistrationEntry(adapterId, TEST_ADAPTER_ADDRESS, new HttpBasicCredentials("user", "pwd"), VERSION_2, null));
        Mockito.when(physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicDbId)).thenReturn(physicalDatabase);
        Mockito.when(physicalDatabaseDbaasRepository.findByAdapterId(adapterId)).thenReturn(physicalDatabase);
        when(dbaasAdapterRESTClientFactory.createDbaasAdapterClientV2(any(), any(), eq(TEST_ADAPTER_ADDRESS), eq(TEST_TYPE), eq(adapterId), any(), eq(physicalDatabase.getAdapter().getApiVersions())))
                .thenReturn(new DbaasAdapterRESTClientV2(TEST_ADAPTER_ID, TEST_TYPE, null, adapterId, tracker));
        when(encryption.decrypt(any())).thenAnswer((invocation) -> invocation.getArgument(0));

        DbaasAdapter adapter = physicalDatabasesService.getAdapterByPhysDbId(physicDbId);

        verify(physicalDatabaseDbaasRepository).findByPhysicalDatabaseIdentifier(physicDbId);
        verify(physicalDatabaseDbaasRepository).findByAdapterId(adapterId);
        verifyNoMoreInteractions(physicalDatabaseDbaasRepository);
        Assertions.assertEquals(adapterId, adapter.identifier());
    }

    @Test
    void getAdapterByPhysDbIdAndPhysicalDbNotExists() {
        String physicDbId = "123";
        Mockito.when(physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicDbId)).thenReturn(null);
        Assertions.assertThrows(UnregisteredPhysicalDatabaseException.class, () -> {
            physicalDatabasesService.getAdapterByPhysDbId(physicDbId);
        });
    }

    @Test
    void presentPhysicalDatabases() {
        Map<String, String> labels = new HashMap<>();
        labels.put("test-label", "test");
        Map<String, Boolean> supports = new HashMap<>();
        supports.put("test-supports", true);
        String physicalDatabaseIdentifier = "test-physicalDatabaseIdentifier";
        boolean global = true;
        String id = "test-id";
        boolean unidentified = true;
        String type = "test-type";
        Date registrationDate = new Date();
        List<String> roles = new ArrayList(Arrays.asList("admin", "ro", "rw"));
        Map<String, Boolean> features = Map.of("tls", false, "multiusers", true);
        ExternalAdapterRegistrationEntry externalAdapterRegistrationEntry = mock(ExternalAdapterRegistrationEntry.class);
        HttpBasicCredentials httpBasicCredentials = new HttpBasicCredentials("test-username", "test-password");
        String address = "test.address";
        DbaasAdapter dbaasAdapter = Mockito.mock(DbaasAdapter.class);

        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setPhysicalDatabaseIdentifier(physicalDatabaseIdentifier);
        physicalDatabase.setGlobal(global);
        physicalDatabase.setId(id);
        physicalDatabase.setUnidentified(unidentified);
        physicalDatabase.setType(type);
        physicalDatabase.setRegistrationDate(registrationDate);
        physicalDatabase.setAdapter(externalAdapterRegistrationEntry);
        physicalDatabase.setLabels(labels);
        physicalDatabase.setRoles(roles);
        physicalDatabase.setFeatures(features);
        List<PhysicalDatabase> source = Stream.<PhysicalDatabase>builder()
                .add(physicalDatabase).build().collect(Collectors.toList());

        when(externalAdapterRegistrationEntry.getAdapterId()).thenReturn(id);
        when(externalAdapterRegistrationEntry.getAddress()).thenReturn(address);
        when(externalAdapterRegistrationEntry.getSupportedVersion()).thenReturn(VERSION_2);
        when(externalAdapterRegistrationEntry.getHttpBasicCredentials()).thenReturn(httpBasicCredentials);
        ApiVersion apiVersions = null;
        when(externalAdapterRegistrationEntry.getApiVersions()).thenReturn(apiVersions);
        when(dbaasAdapterRESTClientFactory.createDbaasAdapterClientV2(any(), any(), eq(address), eq(type), eq(id), any(AdapterActionTrackerClient.class), eq(apiVersions)))
                .thenReturn(dbaasAdapter);
        when(encryption.decrypt(any())).then(ctx -> ctx.getArgument(0));
        when(dbaasAdapter.supports()).thenReturn(supports);

        when(physicalDatabaseDbaasRepository.findByAdapterId(eq(id))).thenReturn(physicalDatabase);
        RegisteredPhysicalDatabasesDTO databasesDTO = physicalDatabasesService.presentPhysicalDatabases(source);
        assertNotNull(databasesDTO);
        Map<String, PhysicalDatabaseRegistrationResponseDTOV3> identified = databasesDTO.getIdentified();
        assertNotNull(identified);
        assertFalse(identified.isEmpty());
        PhysicalDatabaseRegistrationResponseDTOV3 dto = (PhysicalDatabaseRegistrationResponseDTOV3) identified.get(physicalDatabase.getPhysicalDatabaseIdentifier());
        assertNotNull(dto);
        assertEquals(id, dto.getAdapterId());
        assertEquals(type, dto.getType());
        assertEquals(address, dto.getAdapterAddress());
        assertEquals(labels, dto.getLabels());
        assertEquals(supports, dto.getSupports());
        assertEquals(global, dto.isGlobal());
        assertTrue(roles.containsAll(dto.getSupportedRoles()));
        assertEquals(features, dto.getFeatures());
    }

    @Test
    void testMakeGlobal_Ok() {
        PhysicalDatabase oldGlobal = getPhysicalDatabaseSample();
        oldGlobal.setGlobal(true);
        String newGlobalId = TEST_PHYDBID + "_2";
        PhysicalDatabase newGlobal = getPhysicalDatabaseSample();
        newGlobal.setId(newGlobalId);
        newGlobal.setGlobal(false);

        Mockito.when(physicalDatabaseDbaasRepository.findGlobalByType(TEST_TYPE)).thenReturn(Optional.of(oldGlobal));

        physicalDatabasesService.makeGlobal(newGlobal);
        verify(physicalDatabaseDbaasRepository, times(1)).save(same(oldGlobal));
        verify(physicalDatabaseDbaasRepository, times(1)).save(same(newGlobal));
        assertFalse(oldGlobal.isGlobal());
        assertTrue(newGlobal.isGlobal());
    }

    @Test
    void testMakeGlobal_AlreadyGlobal() {
        PhysicalDatabase oldGlobal = getPhysicalDatabaseSample();
        oldGlobal.setGlobal(true);

        physicalDatabasesService.makeGlobal(oldGlobal);
        verifyNoMoreInteractions(physicalDatabaseDbaasRepository);
        assertTrue(oldGlobal.isGlobal());
    }

    @Test
    void testIsDbActual_ActuallyActual() {
        Mockito.doAnswer(invocation -> {
                    PhysicalDatabaseRegistryRequestV3 request = invocation.getArgument(0, PhysicalDatabaseRegistryRequestV3.class);
                    HttpBasicCredentials httpBasicCredentials = request.getHttpBasicCredentials();
                    httpBasicCredentials.setPassword(httpBasicCredentials.getPassword() + "-encrypted");
                    return null;
                })
                .when(encryption).encryptPassword(any(PhysicalDatabaseRegistryRequestV3.class));
        when(encryption.decrypt(any())).thenAnswer(invocation -> invocation.getArgument(0, String.class).replace("-encrypted", ""));

        Map<String, String> labels = Map.of("label1", "value1", "label2", "value2");
        List<String> supportedRoles = List.of("role1", "role2");
        Map<String, Boolean> features = Map.of("feature1", false, "feature2", true);
        String roHost = "roHost";
        String adapterAddress = "addr";
        HttpBasicCredentials httpBasicCredentials = new HttpBasicCredentials("username", "password");
        String supportedVersion = "v1";
        ApiVersion apiVersions = new ApiVersion(List.of(new ApiVersion.Spec("/roolUrl", 1, 2, List.of(1, 2))));

        HttpBasicCredentials encryptedHttpBasicCredentials = new HttpBasicCredentials(httpBasicCredentials.getUsername(), httpBasicCredentials.getPassword());
        PhysicalDatabaseRegistryRequestV3 tempEncryptedRequest = new PhysicalDatabaseRegistryRequestV3();
        tempEncryptedRequest.setHttpBasicCredentials(encryptedHttpBasicCredentials);
        encryption.encryptPassword(tempEncryptedRequest);

        PhysicalDatabaseRegistryRequestV3 request = new PhysicalDatabaseRegistryRequestV3();
        request.setAdapterAddress(adapterAddress);
        request.setHttpBasicCredentials(httpBasicCredentials);
        request.setLabels(labels);
        request.setMetadata(new Metadata(supportedVersion, apiVersions, supportedRoles, features, roHost));
        request.setStatus("testing");

        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setLabels(labels);
        physicalDatabase.setRoles(supportedRoles);
        physicalDatabase.setFeatures(features);
        physicalDatabase.setRoHost(roHost);
        physicalDatabase.setAdapter(new ExternalAdapterRegistrationEntry(
                "123", adapterAddress, encryptedHttpBasicCredentials, supportedVersion, apiVersions));

        assertTrue(physicalDatabasesService.isDbActual(request, physicalDatabase));

        physicalDatabase.setRoles(supportedRoles.reversed());
        assertTrue(physicalDatabasesService.isDbActual(request, physicalDatabase));
    }

    @Test
    void testIsDbActual_ActuallyNotActual() {
        Map<String, String> labels = Map.of("label1", "value1", "label2", "value2");
        List<String> supportedRoles = List.of("role1", "role2");
        Map<String, Boolean> features = Map.of("feature1", false, "feature2", true);
        String roHost = "roHost";
        String adapterAddress = "addr";
        HttpBasicCredentials httpBasicCredentials = new HttpBasicCredentials("username", "password");
        String supportedVersion = "v1";
        ApiVersion apiVersions = new ApiVersion(List.of(new ApiVersion.Spec("/roolUrl", 1, 2, List.of(1, 2))));

        PhysicalDatabaseRegistryRequestV3 request = new PhysicalDatabaseRegistryRequestV3();
        request.setAdapterAddress(adapterAddress);
        request.setHttpBasicCredentials(httpBasicCredentials);
        request.setLabels(labels);
        request.setMetadata(new Metadata(supportedVersion, apiVersions, supportedRoles, features, roHost));
        request.setStatus("testing");

        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setLabels(labels);
        physicalDatabase.setRoles(supportedRoles);
        physicalDatabase.setFeatures(features);
        physicalDatabase.setRoHost(roHost);
        physicalDatabase.setAdapter(new ExternalAdapterRegistrationEntry(
                "123", adapterAddress, httpBasicCredentials, supportedVersion, apiVersions));

        physicalDatabase.setLabels(Map.of("label2", "value2"));
        assertFalse(physicalDatabasesService.isDbActual(request, physicalDatabase));
        physicalDatabase.setLabels(Map.of("label1", "value2", "label2", "value1"));
        assertFalse(physicalDatabasesService.isDbActual(request, physicalDatabase));
        physicalDatabase.setLabels(labels);

        physicalDatabase.setRoles(List.of("role2", "role1", "role3"));
        assertFalse(physicalDatabasesService.isDbActual(request, physicalDatabase));
        physicalDatabase.setRoles(supportedRoles);

        physicalDatabase.setFeatures(Map.of("feature1", true, "feature2", false));
        assertFalse(physicalDatabasesService.isDbActual(request, physicalDatabase));
        physicalDatabase.setFeatures(features);

        physicalDatabase.setRoHost("anotherRoHost");
        assertFalse(physicalDatabasesService.isDbActual(request, physicalDatabase));
        physicalDatabase.setRoHost(roHost);

        physicalDatabase.getAdapter().setAddress("anotherAddress");
        assertFalse(physicalDatabasesService.isDbActual(request, physicalDatabase));
        physicalDatabase.getAdapter().setAddress(adapterAddress);

        physicalDatabase.getAdapter().setHttpBasicCredentials(new HttpBasicCredentials("username1", "password2"));
        assertFalse(physicalDatabasesService.isDbActual(request, physicalDatabase));
        physicalDatabase.getAdapter().setHttpBasicCredentials(httpBasicCredentials);

        physicalDatabase.getAdapter().setSupportedVersion("v2");
        assertFalse(physicalDatabasesService.isDbActual(request, physicalDatabase));
        physicalDatabase.getAdapter().setSupportedVersion(supportedVersion);

        physicalDatabase.getAdapter().setApiVersions(new ApiVersion(List.of(new ApiVersion.Spec("/anotherRoolUrl", 2, 3, List.of(4, 5)))));
        assertFalse(physicalDatabasesService.isDbActual(request, physicalDatabase));
        physicalDatabase.getAdapter().setApiVersions(apiVersions);
    }

    @Test
    void checkDiffInSupportedRolesTest() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistryRequestV3Sample();
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setRoles(Collections.singletonList(Role.ADMIN.toString()));
        boolean isRolesDifferent = physicalDatabasesService.isRolesDifferent(physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles(),
                physicalDatabase.getRoles());
        Assertions.assertTrue(isRolesDifferent);
    }

    @Test
    void startMigrationProcedureWithOnePortionTest() {
        int count = 10;
        List<Database> logicalDatabases = generateListOfLogicalDatabase(count);
        Instruction instruction = physicalDatabasesService.buildInstructionForAdditionalRoles(logicalDatabases);
        Assertions.assertEquals(count, instruction.getAdditionalRoles().size());
    }

    private List<Database> generateListOfLogicalDatabase(int count) {
        List<Database> logicalDatabases = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Database logicalDatabase = new Database();
            logicalDatabase.setId(UUID.randomUUID());
            Map<String, Object> connectionPropertiesForAdmin = new HashMap<>();
            connectionPropertiesForAdmin.put("id", i);
            connectionPropertiesForAdmin.put("role", "admin");
            Map<String, Object> connectionPropertiesForRo = new HashMap<>();
            connectionPropertiesForRo.put("id", i);
            connectionPropertiesForRo.put("role", "ro");
            logicalDatabase.setConnectionProperties(Arrays.asList(connectionPropertiesForAdmin, connectionPropertiesForRo));
            logicalDatabase.setResources(Collections.singletonList(new DbResource("someKind", "someName")));
            logicalDatabase.setName("test-dbname");
            logicalDatabases.add(logicalDatabase);
        }
        return logicalDatabases;
    }
}
