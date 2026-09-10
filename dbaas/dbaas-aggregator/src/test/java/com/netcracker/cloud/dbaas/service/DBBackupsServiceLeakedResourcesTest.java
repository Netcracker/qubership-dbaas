package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.dto.EnsuredUser;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import com.netcracker.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import com.netcracker.cloud.dbaas.utils.DatabaseBuilder;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static com.netcracker.cloud.dbaas.utils.DatabaseBuilder.POSTGRES_ADAPTER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class DBBackupsServiceLeakedResourcesTest {

    private static final String TEST_NS = "backup-restore-resource-leak-test";

    @Inject
    DBBackupsService dbBackupsService;
    @Inject
    DatabaseDbaasRepository databaseDbaasRepository;
    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    DatabaseRegistryRepository databaseRegistryRepository;
    @InjectMock
    PhysicalDatabasesService physicalDatabasesService;

    @BeforeEach
    void setUp() {
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Transactional
    public void clean() {
        databaseDbaasRepository.deleteAll(databaseDbaasRepository.findAnyLogDbTypeByNamespace(TEST_NS));
        databaseRegistryRepository.getEntityManager()
                .createNativeQuery("DELETE FROM db_resources WHERE id NOT IN (SELECT resources_id FROM database_db_resources)")
                .executeUpdate();
    }

    @Test
    void testUserEnsure_noOrphanedDbResourceRows_whenAdapterReturnsUserOnlyResources() {
        Database database = new DatabaseBuilder()
                .registry()
                .build();
        DatabaseRegistry registry = database.getDatabaseRegistry().getFirst();
        DatabaseRegistry persistedRegistry = QuarkusTransaction.requiringNew().call(() ->
                databaseRegistryDbaasRepository.saveAnyTypeLogDb(registry));

        List<UUID> savedResourceIds = persistedRegistry.getDatabase().getResources().stream()
                .map(DbResource::getId)
                .toList();

        DbaasAdapter adapter = mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAdapterById(POSTGRES_ADAPTER_ID)).thenReturn(adapter);
        when(adapter.isUsersSupported()).thenReturn(true);
        when(adapter.getSupportedVersion()).thenReturn("v2");
        EnsuredUser userOnly = new EnsuredUser(
                DatabaseBuilder.ADMIN_USER_NAME,
                new HashMap<>(),
                List.of(new DbResource("user", DatabaseBuilder.ADMIN_USER_NAME)),
                true);
        when(adapter.ensureUser(any(), any(), anyString(), anyString())).thenReturn(userOnly);

        NamespaceBackup backup = new NamespaceBackup();
        backup.setId(UUID.randomUUID());
        backup.setNamespace(TEST_NS);


        QuarkusTransaction.requiringNew().run(() ->
                dbBackupsService.userEnsure(backup, List.of(persistedRegistry), false));

        databaseRegistryRepository.getEntityManager().clear();

        for (UUID originalId : savedResourceIds) {
            Long orphanedCount = (Long) databaseRegistryRepository.getEntityManager()
                    .createNativeQuery(
                            "SELECT COUNT(*) FROM db_resources"
                                    + " WHERE id = CAST(:id AS UUID)"
                                    + " AND id NOT IN (SELECT resources_id FROM database_db_resources)")
                    .setParameter("id", originalId.toString())
                    .getSingleResult();
            assertEquals(0L, orphanedCount,
                    "db_resource row " + originalId + " is orphaned after userEnsure:"
                            + " the row exists in db_resources but is not referenced by database_db_resources");
        }
    }

    @Test
    void testUserEnsure_noOrphanedDbResourceRows_whenRegistryJustSavedByRecreateRemovedDatabases() {
        Database database = new DatabaseBuilder()
                .type("cassandra")
                .registry(b -> b.namespace(TEST_NS))
                .build();
        database.setNamespace(TEST_NS);
        DatabaseRegistry registry = database.getDatabaseRegistry().get(0);

        List<DatabaseRegistry> saved = QuarkusTransaction.requiringNew().call(() ->
                databaseRegistryDbaasRepository.saveAll(List.of(registry)));
        DatabaseRegistry savedRegistry = saved.get(0);

        List<UUID> savedResourceIds = savedRegistry.getDatabase().getResources().stream()
                .map(DbResource::getId)
                .toList();

        DbaasAdapter adapter = mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAdapterById(POSTGRES_ADAPTER_ID)).thenReturn(adapter);
        when(adapter.isUsersSupported()).thenReturn(true);
        when(adapter.getSupportedVersion()).thenReturn("v2");
        EnsuredUser userOnly = new EnsuredUser(
                DatabaseBuilder.ADMIN_USER_NAME,
                new HashMap<>(),
                List.of(new DbResource("user", DatabaseBuilder.ADMIN_USER_NAME)),
                true);
        when(adapter.ensureUser(any(), any(), anyString(), anyString())).thenReturn(userOnly);

        NamespaceBackup backup = new NamespaceBackup();
        backup.setId(UUID.randomUUID());
        backup.setNamespace(TEST_NS);

        QuarkusTransaction.requiringNew().run(() ->
                dbBackupsService.userEnsure(backup, List.of(savedRegistry), false));

        databaseRegistryRepository.getEntityManager().clear();

        for (UUID originalId : savedResourceIds) {
            Long orphanedCount = (Long) databaseRegistryRepository.getEntityManager()
                    .createNativeQuery(
                            "SELECT COUNT(*) FROM db_resources"
                                    + " WHERE id = CAST(:id AS UUID)"
                                    + " AND id NOT IN (SELECT resources_id FROM database_db_resources)")
                    .setParameter("id", originalId.toString())
                    .getSingleResult();
            assertEquals(0L, orphanedCount,
                    "db_resource row " + originalId + " is orphaned after userEnsure following saveAll:"
                            + " reproduces the Case B leak where recreateRemovedBackupedDatabases saves resources"
                            + " that userEnsure immediately orphans");
        }
    }
}
