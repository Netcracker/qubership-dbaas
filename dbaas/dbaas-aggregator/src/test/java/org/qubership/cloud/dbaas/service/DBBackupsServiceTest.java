package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.DatabaseType;
import org.qubership.cloud.dbaas.dto.EnsuredUser;
import org.qubership.cloud.dbaas.dto.backup.DeleteResult;
import org.qubership.cloud.dbaas.dto.backup.NamespaceBackupDeletion;
import org.qubership.cloud.dbaas.dto.backup.Status;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceRestoration;
import org.qubership.cloud.dbaas.entity.pg.backup.RestoreResult;
import org.qubership.cloud.dbaas.exceptions.MultiValidationException;
import org.qubership.cloud.dbaas.exceptions.NamespaceBackupDeletionFailedException;
import org.qubership.cloud.dbaas.exceptions.NamespaceRestorationFailedException;
import org.qubership.cloud.dbaas.repositories.dbaas.BackupsDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.rest.DbaasAdapterRestClientV2;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.quality.Strictness.LENIENT;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = LENIENT)
public class DBBackupsServiceTest {
    private BackupsDbaasRepository backupsDbaasRepository;
    private PhysicalDatabasesService physicalDatabasesService;
    private DbaasAdapter dbaasAdapter;
    private PasswordEncryption encryption;
    private EntityManager entityManager;
    private EntityManager buckupEntityManager;
    private DBaaService dBaaService;
    private DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    private DbaaSHelper dbaaSHelper;
    private DBBackupsService dbBackupsService;

    private final String TEST_NAMESPACE = "test-namespace";
    private final String TEST_ADAPTER_ID = "test-adapter-id";
    private final boolean ALLOW_EVICTION = false;


    @BeforeEach
    public void beforeEach() {
        backupsDbaasRepository = Mockito.mock(BackupsDbaasRepository.class);
        buckupEntityManager = Mockito.mock(EntityManager.class);
        when(backupsDbaasRepository.getEntityManager()).thenReturn(buckupEntityManager);
        when(buckupEntityManager.contains(any())).thenReturn(Boolean.FALSE);
        physicalDatabasesService = Mockito.mock(PhysicalDatabasesService.class);
        dbaasAdapter = Mockito.mock(DbaasAdapter.class);
        encryption = Mockito.mock(PasswordEncryption.class);
        entityManager = Mockito.mock(EntityManager.class);
        dBaaService = Mockito.mock(DBaaService.class);
        databaseRegistryDbaasRepository = Mockito.mock(DatabaseRegistryDbaasRepository.class);
        dbaaSHelper = Mockito.mock(DbaaSHelper.class);
        dbBackupsService = Mockito.spy(new DBBackupsService(dBaaService, physicalDatabasesService, databaseRegistryDbaasRepository, backupsDbaasRepository, encryption, entityManager, dbaaSHelper));
        doReturn(new Object()).when(dbBackupsService).runInNewTransaction(any());
    }

    @Test
    public void testCollectBackup() {
        final UUID id = UUID.randomUUID();
        final DatabaseRegistry database = getDatabaseSample();
        when(databaseRegistryDbaasRepository.findInternalDatabaseRegistryByNamespace(TEST_NAMESPACE)).thenReturn(Collections.singletonList(database));
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();
        when(dbaasAdapter.backup(any(), eq(ALLOW_EVICTION))).thenReturn(databasesBackup);
        final NamespaceBackup actualNamespaceBackup = dbBackupsService.collectBackup(TEST_NAMESPACE, id, ALLOW_EVICTION);

        assertEquals(TEST_NAMESPACE, actualNamespaceBackup.getNamespace());
        assertEquals(id, actualNamespaceBackup.getId());
        assertEquals(NamespaceBackup.Status.ACTIVE.name(), actualNamespaceBackup.getStatus().name());
        assertNotNull(actualNamespaceBackup.getDatabases());
        assertEquals(1, actualNamespaceBackup.getDatabases().size());
        assertEquals(TEST_ADAPTER_ID, actualNamespaceBackup.getDatabases().get(0).getAdapterId());
        assertNotNull(actualNamespaceBackup.getBackups());
        assertEquals(1, actualNamespaceBackup.getBackups().size());
        assertEquals(Status.SUCCESS.name(), actualNamespaceBackup.getBackups().get(0).getStatus().name());
    }

    @Test
    public void testDeleteBackup() throws NamespaceBackupDeletionFailedException {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();
        databasesBackup.setAdapterId(TEST_ADAPTER_ID);
        databasesBackup.setLocalId(UUID.randomUUID().toString());
        namespaceBackup.getBackups().add(databasesBackup);
        final DatabaseRegistry database = getDatabaseSample();
        namespaceBackup.getDatabases().add(database.getDatabase());
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(dbaasAdapter.identifier()).thenReturn(TEST_ADAPTER_ID);
        final DeleteResult deleteResult = new DeleteResult();
        deleteResult.setStatus(Status.SUCCESS);
        deleteResult.setAdapterId(TEST_ADAPTER_ID);
        when(dbaasAdapter.delete(any(DatabasesBackup.class))).thenReturn(deleteResult);

        final NamespaceBackupDeletion namespaceDeletion = dbBackupsService.deleteBackup(namespaceBackup);
        assertEquals(deleteResult.getStatus(), namespaceDeletion.getStatus());
        assertNotNull(namespaceDeletion.getDeleteResults());
        assertEquals(1, namespaceDeletion.getDeleteResults().size());
        assertEquals(TEST_ADAPTER_ID, namespaceDeletion.getDeleteResults().get(0).getAdapterId());
    }

    @Test
    public void testDeleteWithExpectedNamespaceBackupDeletionFailedException() throws NamespaceBackupDeletionFailedException {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();
        namespaceBackup.getBackups().add(databasesBackup);
        databasesBackup.setAdapterId(TEST_ADAPTER_ID);
        databasesBackup.setLocalId(UUID.randomUUID().toString());
        namespaceBackup.getBackups().add(databasesBackup);
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(dbaasAdapter.identifier()).thenReturn(TEST_ADAPTER_ID);
        final DeleteResult deleteResult = new DeleteResult();
        deleteResult.setAdapterId(TEST_ADAPTER_ID);
        deleteResult.setStatus(Status.FAIL);
        when(dbaasAdapter.delete(any(DatabasesBackup.class))).thenReturn(deleteResult);
        Assertions.assertThrows(NamespaceBackupDeletionFailedException.class, () -> {
            final NamespaceBackupDeletion namespaceDeletion = dbBackupsService.deleteBackup(namespaceBackup);
            assertEquals(deleteResult.getStatus(), namespaceDeletion.getStatus());
            assertNotNull(namespaceDeletion.getDeleteResults());
            assertEquals(1, namespaceDeletion.getDeleteResults().size());
            assertEquals(TEST_ADAPTER_ID, namespaceDeletion.getDeleteResults().get(0).getAdapterId());
        });
    }

    @Test
    void deleteRestoreFalse() {
        NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        namespaceBackup.setStatus(NamespaceBackup.Status.RESTORING);
        when(backupsDbaasRepository.findById(namespaceBackup.getId())).thenReturn(Optional.of(namespaceBackup));
        assertEquals(namespaceBackup, dbBackupsService.deleteRestore(namespaceBackup.getId(), UUID.randomUUID()));
    }

    @Test
    public void deleteRestore() {
        NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        namespaceBackup.setStatus(NamespaceBackup.Status.FAIL);
        NamespaceRestoration namespaceRestoration = new NamespaceRestoration();
        UUID restoreId = UUID.randomUUID();
        namespaceRestoration.setId(restoreId);
        ArrayList<NamespaceRestoration> restorations = new ArrayList<>();
        restorations.add(namespaceRestoration);
        namespaceBackup.setRestorations(restorations);
        NamespaceBackup savedBackup = new NamespaceBackup();
        when(backupsDbaasRepository.save(namespaceBackup)).thenReturn(savedBackup);
        when(backupsDbaasRepository.findById(namespaceBackup.getId())).thenReturn(Optional.of(namespaceBackup));
        assertEquals(savedBackup, dbBackupsService.deleteRestore(namespaceBackup.getId(), restoreId));

        verify(backupsDbaasRepository).save(namespaceBackup);
    }

    @Test
    public void testValidateBackup() {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        final DatabasesBackup firstDatabasesBackup = getDatabasesBackupSample();
        final DatabasesBackup secondDatabasesBackup = getDatabasesBackupSample();
        namespaceBackup.getBackups().add(firstDatabasesBackup);
        namespaceBackup.getBackups().add(secondDatabasesBackup);

        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        when(dbaasAdapter.validate(firstDatabasesBackup)).thenReturn(true);
        when(dbaasAdapter.validate(secondDatabasesBackup)).thenReturn(true);
        boolean actual = dbBackupsService.validateBackup(namespaceBackup);

        assertTrue(actual);
        verify(physicalDatabasesService, times(2)).getAdapterById(TEST_ADAPTER_ID);
        verifyNoMoreInteractions(physicalDatabasesService);

        when(dbaasAdapter.validate(secondDatabasesBackup)).thenReturn(false);
        actual = dbBackupsService.validateBackup(namespaceBackup);
        assertFalse(actual);
    }

    @Test
    public void testRestoreSameNamespace() throws NamespaceRestorationFailedException {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        final UUID restorationId = UUID.randomUUID();
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();
        namespaceBackup.getBackups().add(databasesBackup);
        final DatabaseRegistry database = getDatabaseSample();
        namespaceBackup.getDatabases().add(database.getDatabase());
        when(databaseRegistryDbaasRepository.findInternalDatabaseRegistryByNamespace(TEST_NAMESPACE)).thenReturn(database.getDatabaseRegistry());
        when(backupsDbaasRepository.findById(namespaceBackup.getId())).thenReturn(Optional.of(namespaceBackup));
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        final RestoreResult restoreResult = new RestoreResult(TEST_ADAPTER_ID);
        restoreResult.setStatus(Status.SUCCESS);
        when(dbaasAdapter.restore(anyString(), any(DatabasesBackup.class), any(Boolean.class), anyList(), anyMap())).thenReturn(restoreResult);

        final NamespaceRestoration namespaceRestoration = dbBackupsService.restore(namespaceBackup, restorationId, TEST_NAMESPACE);
        assertEquals(restoreResult.getStatus(), namespaceRestoration.getStatus());
        assertNotNull(namespaceRestoration.getRestoreResults());
        assertEquals(1, namespaceRestoration.getRestoreResults().size());
        assertEquals(TEST_ADAPTER_ID, namespaceRestoration.getRestoreResults().get(0).getAdapterId());
    }

    @Test
    public void testRestoreSameNamespaceWithoutDelete() throws NamespaceRestorationFailedException {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        final UUID restorationId = UUID.randomUUID();
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();
        namespaceBackup.getBackups().add(databasesBackup);
        final DatabaseRegistry database = getDatabaseSample();
        namespaceBackup.getDatabases().add(database.getDatabase());
        namespaceBackup.setDatabaseRegistries(database.getDatabaseRegistry());
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        final RestoreResult restoreResult = new RestoreResult(TEST_ADAPTER_ID);
        restoreResult.setStatus(Status.SUCCESS);
        String newdbName = "newdbName";
        Map<String, String> changedDbNames = new HashMap<>() {{
            put(database.getName(), newdbName);
        }};

        TreeMap<String, Object> targetClassifier = new TreeMap<>(database.getDatabaseRegistry().get(0).getClassifier());
        targetClassifier.put("target", "target");
        restoreResult.setChangedNameDb(changedDbNames);
        when(dbaasAdapter.restore(anyString(), any(DatabasesBackup.class), any(Boolean.class), anyList(), anyMap())).thenReturn(restoreResult);

        final NamespaceRestoration namespaceRestoration = dbBackupsService.restore(namespaceBackup, restorationId, TEST_NAMESPACE,
                true, "v2", targetClassifier, new HashMap<>());
        assertEquals(restoreResult.getStatus(), namespaceRestoration.getStatus());
        assertNotNull(namespaceRestoration.getRestoreResults());
        assertEquals(1, namespaceRestoration.getRestoreResults().size());
        assertEquals(TEST_ADAPTER_ID, namespaceRestoration.getRestoreResults().get(0).getAdapterId());
    }


    @Test
    public void testRestoreAnotherNamespace() throws NamespaceRestorationFailedException {
        final String anotherNamespace = "another-namespace";
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        final UUID restorationId = UUID.randomUUID();
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();
        namespaceBackup.getBackups().add(databasesBackup);
        final DatabaseRegistry database = getDatabaseSample();
        namespaceBackup.getDatabases().add(database.getDatabase());
        when(databaseRegistryDbaasRepository.findInternalDatabaseRegistryByNamespace(anotherNamespace)).thenReturn(database.getDatabaseRegistry());
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        final RestoreResult restoreResult = new RestoreResult(TEST_ADAPTER_ID);
        final Map<String, String> changedNameDb = new HashMap<>();
        changedNameDb.put("name", "test-name");
        restoreResult.setChangedNameDb(changedNameDb);
        restoreResult.setStatus(Status.SUCCESS);
        when(dbaasAdapter.restore(anyString(), any(DatabasesBackup.class), any(Boolean.class), anyList(), anyMap())).thenReturn(restoreResult);

        final NamespaceRestoration namespaceRestoration = dbBackupsService.restore(namespaceBackup, restorationId, anotherNamespace);
        assertEquals(restoreResult.getStatus(), namespaceRestoration.getStatus());
        assertNotNull(namespaceRestoration.getRestoreResults());
        assertEquals(1, namespaceRestoration.getRestoreResults().size());
        assertEquals(TEST_ADAPTER_ID, namespaceRestoration.getRestoreResults().get(0).getAdapterId());
    }

    @Test
    public void testRestoreWithOldClassifier() throws NamespaceRestorationFailedException {
        NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        UUID restorationId = UUID.randomUUID();
        DatabasesBackup databasesBackup = getDatabasesBackupSample();
        namespaceBackup.getBackups().add(databasesBackup);
        DatabaseRegistry database = getDatabaseSample();
        database.setConnectionProperties(List.of(new HashMap<>() {{
            put(ROLE, Role.ADMIN.toString());
        }}));
        database.setOldClassifier(new TreeMap<>(Map.of("old", "yes"))); // !!
        namespaceBackup.getDatabases().add(database.getDatabase());
        namespaceBackup.setDatabaseRegistries(database.getDatabaseRegistry());
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);

        TreeMap<String, Object> targetClassifier = new TreeMap<>(database.getDatabaseRegistry().getFirst().getClassifier());
        targetClassifier.put("target", "target");

        RestoreResult restoreResult = new RestoreResult(TEST_ADAPTER_ID);
        restoreResult.setStatus(Status.SUCCESS);
        restoreResult.setChangedNameDb(Map.of(database.getName(), "newdbName"));
        when(dbaasAdapter.restore(anyString(), any(DatabasesBackup.class), any(Boolean.class), anyList(), anyMap())).thenReturn(restoreResult);
        when(dbaasAdapter.isUsersSupported()).thenReturn(true);
        when(dbaasAdapter.ensureUser(any(), any(), anyString(), anyString())).thenReturn(
                new EnsuredUser("ensuredUser", database.getConnectionProperties().getFirst(), List.of(), true)
        );

        NamespaceRestoration namespaceRestoration = dbBackupsService.restore(namespaceBackup, restorationId, TEST_NAMESPACE,
                true, "v2", targetClassifier, new HashMap<>());
        assertEquals(restoreResult.getStatus(), namespaceRestoration.getStatus());
        assertNotNull(namespaceRestoration.getRestoreResults());
        assertEquals(1, namespaceRestoration.getRestoreResults().size());
        assertEquals(TEST_ADAPTER_ID, namespaceRestoration.getRestoreResults().get(0).getAdapterId());
        verify(databaseRegistryDbaasRepository, times(1)).saveInternalDatabase(argThat(databaseRegistry ->
                databaseRegistry.getClassifier().get("target").equals(targetClassifier.get("target")) && databaseRegistry.getDatabase().getOldClassifier() == null
        ));
    }

    @Test
    public void testRestoreAnotherNamespaceCopy() throws NamespaceRestorationFailedException {
        final String anotherNamespace = "another-namespace";
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        final UUID restorationId = UUID.randomUUID();
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();
        namespaceBackup.getBackups().add(databasesBackup);
        final DatabaseRegistry database = getDatabaseSample();
        namespaceBackup.getDatabases().add(database.getDatabase());
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        final RestoreResult restoreResult = new RestoreResult(TEST_ADAPTER_ID);
        final Map<String, String> changedNameDb = new HashMap<>();
        changedNameDb.put("name", "test-name");
        restoreResult.setChangedNameDb(changedNameDb);
        restoreResult.setStatus(Status.SUCCESS);
        when(dbaasAdapter.restore(anyString(), any(DatabasesBackup.class), any(Boolean.class), anyList(), anyMap())).thenReturn(restoreResult);

        NamespaceRestoration namespaceRestoration = dbBackupsService.restore(namespaceBackup, restorationId, anotherNamespace,
                true, null);
        assertEquals(restoreResult.getStatus(), namespaceRestoration.getStatus());
        assertNotNull(namespaceRestoration.getRestoreResults());
        assertEquals(1, namespaceRestoration.getRestoreResults().size());
        assertEquals(TEST_ADAPTER_ID, namespaceRestoration.getRestoreResults().get(0).getAdapterId());
    }

    @Test
    void testRetryOnceUserEnsure() {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        DatabaseRegistry database = getDatabaseSample();
        List<Map<String, Object>> connectionList = Arrays.asList(new HashMap<String, Object>() {{
            put("authDbName", "test-encrypt");
            put("password", "this is plain value");
            put(ROLE, Role.ADMIN.toString());
        }});
        database.setConnectionProperties(connectionList);
        when(dbaasAdapter.getSupportedVersion()).thenReturn("v2");
        when(dbaasAdapter.isUsersSupported()).thenReturn(true);
        when(physicalDatabasesService.getAdapterById(any())).thenReturn(dbaasAdapter);
        EnsuredUser ensuredUser = new EnsuredUser();
        ensuredUser.setConnectionProperties(new HashMap<>());
        when(dbaasAdapter.ensureUser(any(), any(), any(), any())).thenThrow(new RuntimeException()).thenReturn(ensuredUser);
        DBBackupsService.BulkUserEnsureResult bulkUserEnsureResult = dbBackupsService.userEnsure(namespaceBackup, List.of(database), true);
        verify(dbaasAdapter, times(2)).ensureUser(any(), any(), any(), any());
        assertEquals(0, bulkUserEnsureResult.fails.size());
        assertEquals(1, bulkUserEnsureResult.successful.size());
    }

    @Test
    void testRetryTwiceUserEnsure() {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        DatabaseRegistry database = getDatabaseSample();
        List<Map<String, Object>> connectionList = Arrays.asList(new HashMap<String, Object>() {{
            put("authDbName", "test-encrypt");
            put("password", "this is plain value");
            put(ROLE, Role.ADMIN.toString());
        }});
        database.setConnectionProperties(connectionList);
        when(dbaasAdapter.getSupportedVersion()).thenReturn("v2");
        when(dbaasAdapter.isUsersSupported()).thenReturn(true);
        when(physicalDatabasesService.getAdapterById(any())).thenReturn(dbaasAdapter);
        when(dbaasAdapter.ensureUser(any(), any(), any(), any())).thenThrow(new RuntimeException());
        DBBackupsService.BulkUserEnsureResult bulkUserEnsureResult = dbBackupsService.userEnsure(namespaceBackup, List.of(database), true);
        verify(dbaasAdapter, times(3)).ensureUser(any(), any(), any(), any());
        assertEquals(1, bulkUserEnsureResult.fails.size());
        assertEquals(0, bulkUserEnsureResult.successful.size());
    }

    private EnsuredUser createEnsureUser(Map<String, Object> connectionProperties) {
        EnsuredUser ensuredUser = new EnsuredUser();
        ensuredUser.setConnectionProperties(connectionProperties);
        return ensuredUser;
    }

    @Test
    public void testRestoreWithExpectedNamespaceRestorationFailedException() throws NamespaceRestorationFailedException {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        final UUID restorationId = UUID.randomUUID();
        final DatabasesBackup databasesBackup = getDatabasesBackupSample();
        namespaceBackup.getBackups().add(databasesBackup);
        when(backupsDbaasRepository.findById(namespaceBackup.getId())).thenReturn(Optional.of(namespaceBackup));
        when(physicalDatabasesService.getAdapterById(TEST_ADAPTER_ID)).thenReturn(dbaasAdapter);
        final RestoreResult restoreResult = new RestoreResult(TEST_ADAPTER_ID);
        restoreResult.setStatus(Status.FAIL);
        when(dbaasAdapter.restore(anyString(), any(DatabasesBackup.class), any(Boolean.class), anyList(), anyMap())).thenReturn(restoreResult);

        Assertions.assertThrows(NamespaceRestorationFailedException.class, () -> {
            final NamespaceRestoration namespaceRestoration = dbBackupsService.restore(namespaceBackup, restorationId, TEST_NAMESPACE);
            assertEquals(restoreResult.getStatus(), namespaceRestoration.getStatus());
            assertNotNull(namespaceRestoration.getRestoreResults());
            assertEquals(1, namespaceRestoration.getRestoreResults().size());
            assertEquals(TEST_ADAPTER_ID, namespaceRestoration.getRestoreResults().get(0).getAdapterId());
        });
    }

    @Test
    public void testRestoreWithNoDbs() throws NamespaceRestorationFailedException {
        final NamespaceBackup namespaceBackup = getNamespaceBackupSample();
        final UUID restorationId = UUID.randomUUID();
        // make sure databases and backup lists are empty
        namespaceBackup.setDatabases(Collections.emptyList());
        namespaceBackup.setBackups(Collections.emptyList());
        when(backupsDbaasRepository.findById(namespaceBackup.getId())).thenReturn(Optional.of(namespaceBackup));
        final NamespaceRestoration namespaceRestoration = dbBackupsService.restore(namespaceBackup, restorationId, TEST_NAMESPACE);
        assertEquals(Status.SUCCESS, namespaceRestoration.getStatus());
    }

    @Test
    public void testCheckAdaptersOnBackupOperation() {
        Map<String, DbaasAdapter> dbaasAdapters = prepareBackupOperation();
        List<DbaasAdapter> unsupportedBackupAdapters = dbBackupsService.checkAdaptersOnBackupOperation(TEST_NAMESPACE);
        assertEquals(1, unsupportedBackupAdapters.size());
        assertEquals("redisAdapterId", unsupportedBackupAdapters.get(0).identifier());
        dbaasAdapters.forEach((adapterName, dbaasAdapter) -> verify(dbaasAdapter).isBackupRestoreSupported());
    }

    @Test
    public void testBackupWithCheckAdapters() {
        final UUID id = UUID.randomUUID();
        Map<String, DbaasAdapter> dbaasAdapters = prepareBackupOperation();
        dbaasAdapters.forEach((adapterName, dbaasAdapter) -> when(physicalDatabasesService.getAdapterById(dbaasAdapter.identifier())).thenReturn(dbaasAdapter));
        DatabasesBackup databasesBackup = getDatabasesBackupSample();
        dbaasAdapters.forEach((adapterName, dbaasAdapter) -> {
            if (dbaasAdapter.isBackupRestoreSupported()) {
                Mockito.doReturn(databasesBackup).when(dbaasAdapter).backup(any(), eq(ALLOW_EVICTION));
            }
        });
        NamespaceBackup actualNamespaceBackup = dbBackupsService.collectBackup(TEST_NAMESPACE, id, ALLOW_EVICTION);
        DbaasAdapter redisAdapter = dbaasAdapters.get("redisAdapter");
        DbaasAdapter mongoAdapter = dbaasAdapters.get("mongoAdapter");
        DbaasAdapter cassandraAdapter = dbaasAdapters.get("cassandraAdapter");
        verify(redisAdapter, times(0)).backup(any(), eq(ALLOW_EVICTION)); // redis does not support backup so its method was not called
        verify(mongoAdapter).backup(any(), eq(ALLOW_EVICTION));
        verify(cassandraAdapter).backup(any(), eq(ALLOW_EVICTION));
    }

    @Test
    public void testBackupWithNoDbs() {
        final UUID id = UUID.randomUUID();
        // return empty list of databases
        when(databaseRegistryDbaasRepository.findInternalDatabaseRegistryByNamespace(eq(TEST_NAMESPACE))).then(invocation -> Collections.emptyList());
        NamespaceBackup backup = dbBackupsService.collectBackup(TEST_NAMESPACE, id, ALLOW_EVICTION);
        assertEquals(NamespaceBackup.Status.ACTIVE, backup.getStatus());
    }

    private Map<String, DbaasAdapter> prepareBackupOperation() {
        DatabaseRegistry redisDb = createDbForCheckBackupSupport("redisDb", "redisAdapterId", false, false);
        DatabaseRegistry mongoDb = createDbForCheckBackupSupport("mongoDb", "mongoAdapterId", false, null);
        DatabaseRegistry cassandraDb = createDbForCheckBackupSupport("cassandraDb", "cassandraAdapterId", false, null);
//        when(databaseDbaasRepository.findInternalDatabaseByNamespace(TEST_NAMESPACE)).thenReturn(Arrays.asList(redisDb, mongoDb, cassandraDb));
        when(databaseRegistryDbaasRepository.findInternalDatabaseRegistryByNamespace(TEST_NAMESPACE)).thenReturn(Arrays.asList(redisDb, mongoDb, cassandraDb));
        DbaasAdapterRestClientV2 redisClient = createMockRestClientForCheckBackupSupport("http://redis-adapter-address", "redis", true, false, true);
        DbaasAdapterRestClientV2 mongoClient = createMockRestClientForCheckBackupSupport("http://mongo-adapter-address", "mongodb", false, null, false);
        DbaasAdapterRestClientV2 cassandraClient = createMockRestClientForCheckBackupSupport("http://cassandra-adapter-address", "cassandra", true, true, true);
        AdapterActionTrackerClient adapterActionTrackerClientMock = mock(AdapterActionTrackerClient.class);
        DbaasAdapterRESTClientV2 redisAdapter = createAdapterForCheckBackupSupport("http://redis-adapter-address", "redis", redisClient, "redisAdapterId", adapterActionTrackerClientMock);
        DbaasAdapterRESTClientV2 mongoAdapter = createAdapterForCheckBackupSupport("http://mongo-adapter-address", "mongodb", mongoClient, "mongoAdapterId", adapterActionTrackerClientMock);
        DbaasAdapterRESTClientV2 cassandraAdapter = createAdapterForCheckBackupSupport("http://cassandra-adapter-address", "cassandra", cassandraClient, "cassandraAdapterId", adapterActionTrackerClientMock);
        when(physicalDatabasesService.getAdapterById("redisAdapterId")).thenReturn(redisAdapter);
        when(physicalDatabasesService.getAdapterById("mongoAdapterId")).thenReturn(mongoAdapter);
        when(physicalDatabasesService.getAdapterById("cassandraAdapterId")).thenReturn(cassandraAdapter);
        return new HashMap<String, DbaasAdapter>() {{
            put("redisAdapter", redisAdapter);
            put("mongoAdapter", mongoAdapter);
            put("cassandraAdapter", cassandraAdapter);

        }};
    }

    private DbaasAdapterRestClientV2 createMockRestClientForCheckBackupSupport(String adapterAddress, String type, Boolean isSettingExist, Boolean value, boolean isApiExist) {
        DbaasAdapterRestClientV2 restClient = mock(DbaasAdapterRestClientV2.class);
        Map<String, Boolean> responseBody = new HashMap<>();
        if (isSettingExist) {
            responseBody.put("backupRestore", value);
        }
        if (isApiExist) {
            when(restClient.supports(any())).thenReturn(responseBody);
        } else {
            when(restClient.supports(any())).thenThrow(new NotFoundException());
        }
        return restClient;
    }

    private DbaasAdapterRESTClientV2 createAdapterForCheckBackupSupport(String adapterAddress, String type, DbaasAdapterRestClientV2 restClient, String identifier, AdapterActionTrackerClient adapterActionTrackerClient) {
        return spy(new DbaasAdapterRESTClientV2(adapterAddress, type, restClient, identifier, adapterActionTrackerClient));
    }

    private DatabaseRegistry getDatabaseSample() {
        final Database database = new Database();
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", TEST_NAMESPACE);
        classifier.put("scope", "service");
        database.setName("test-name");
        database.setType(DatabaseType.POSTGRESQL.name());
        database.setNamespace(TEST_NAMESPACE);
        database.setAdapterId(TEST_ADAPTER_ID);
        DbResource dbResource = new DbResource("someKind", "someName");
        database.setResources(List.of(dbResource));
        database.setBackupDisabled(false);
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setType(DatabaseType.POSTGRESQL.name());
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setDatabase(database);
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);
        return databaseRegistry;
    }

    private DatabasesBackup getDatabasesBackupSample() {
        final DatabasesBackup databasesBackup = new DatabasesBackup();
        databasesBackup.setAdapterId(TEST_ADAPTER_ID);
        databasesBackup.setStatus(Status.SUCCESS);
        databasesBackup.setDatabases(new ArrayList<>());
        return databasesBackup;
    }

    private NamespaceBackup getNamespaceBackupSample() {
        final NamespaceBackup namespaceBackup = new NamespaceBackup(UUID.randomUUID(), TEST_NAMESPACE, new ArrayList<>(), new ArrayList<>());
        namespaceBackup.setBackups(new ArrayList<>());
        return namespaceBackup;
    }

    private DatabaseRegistry createDbForCheckBackupSupport(String databaseName, String adapterId, Boolean isMarkedForDrop, Boolean isBackupDisabled) {
        Database db = new Database();
        DatabaseRegistry dbRegistry = new DatabaseRegistry();
        dbRegistry.setDatabase(db);
        db.setDatabaseRegistry(List.of(dbRegistry));
        dbRegistry.setAdapterId(adapterId);
        dbRegistry.setName(databaseName);
        dbRegistry.setMarkedForDrop(isMarkedForDrop);
        dbRegistry.setBackupDisabled(isBackupDisabled);
        return dbRegistry;
    }
}
