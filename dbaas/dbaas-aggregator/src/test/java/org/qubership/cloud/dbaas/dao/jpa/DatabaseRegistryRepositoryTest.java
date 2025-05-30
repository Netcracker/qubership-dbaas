package org.qubership.cloud.dbaas.dao.jpa;

import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.repositories.h2.H2DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.repositories.h2.H2DatabaseRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatabaseRegistryRepositoryTest {

    private static MockedStatic<QuarkusTransaction> mockedStatic;

    private DatabaseRegistryDbaasRepositoryImpl databaseRegistryDbaasRepository;

    @Mock
    DatabaseRegistryRepository databaseRegistryRepository;
    @Mock
    DatabasesRepository databasesRepository;
    @Mock
    H2DatabaseRepository h2DatabaseRepository;
    @Mock
    H2DatabaseRegistryRepository h2DatabaseRegistryRepository;
    @Mock
    EntityManager entityManager;

    @BeforeAll
    static void beforeAll() {
        mockedStatic = mockStatic(QuarkusTransaction.class);
        TransactionRunnerOptions txRunner = mock(TransactionRunnerOptions.class);
        doAnswer(invocationOnMock -> {
            ((Runnable) invocationOnMock.getArgument(0)).run();
            return null;
        }).when(txRunner).run(any());
        doAnswer(invocationOnMock -> ((Callable) invocationOnMock.getArgument(0)).call()).when(txRunner).call(any());
        mockedStatic.when(QuarkusTransaction::requiringNew).thenReturn(txRunner);
    }

    @AfterAll
    static void afterAll() {
        mockedStatic.close();
    }

    @BeforeEach
    public void before() {
        databaseRegistryDbaasRepository = new DatabaseRegistryDbaasRepositoryImpl(databasesRepository, h2DatabaseRepository, databaseRegistryRepository, h2DatabaseRegistryRepository);
    }

    @Test
    public void testSaveAll() {
        DatabaseRegistry db_1 = generateRandomDatabase(true);
        DatabaseRegistry db_2 = generateRandomDatabase(true);
        List<DatabaseRegistry> dbs = Stream.of(db_1, db_2).collect(Collectors.toList());

        when(databasesRepository.getEntityManager()).thenReturn(entityManager);
        databaseRegistryDbaasRepository.saveAll(dbs);

        verify(databasesRepository, times(dbs.size())).getEntityManager();
        verify(entityManager, times(dbs.size())).merge(any());
    }

    @Test
    void testFindAnyLogDbTypeByNamespaceAndName() {
        DatabaseRegistry db_1 = generateRandomDatabase(true);
        DatabaseRegistry db_2 = generateRandomDatabase(true);
        List<DatabaseRegistry> dbs = Stream.of(db_1, db_2).toList();

        dbs.forEach(db -> {
            when(databaseRegistryRepository.findByNamespace(db.getNamespace())).thenReturn(Collections.singletonList(db));

            List<DatabaseRegistry> anyLogDbTypeByNamespace = databaseRegistryDbaasRepository.findAnyLogDbTypeByNameAndOptionalParams(db.getName(), db.getNamespace());
            Assertions.assertEquals(1, anyLogDbTypeByNamespace.size());
            Optional<DatabaseRegistry> result = anyLogDbTypeByNamespace.stream().findFirst();
            Assertions.assertTrue(result.isPresent());
            Assertions.assertEquals(db, result.get());
        });
    }

    @Test
    void testFindAnyLogDbTypeByName() {
        DatabaseRegistry db_1 = generateRandomDatabase(true);
        DatabaseRegistry db_2 = generateRandomDatabase(true);
        List<DatabaseRegistry> dbs = Stream.of(db_1, db_2).toList();

        dbs.forEach(db -> {
            when(databasesRepository.findAnyLogDbTypeByName(db.getName())).thenReturn(Collections.singletonList(db.getDatabase()));

            List<DatabaseRegistry> anyLogDbTypeByNamespace = databaseRegistryDbaasRepository.findAnyLogDbTypeByNameAndOptionalParams(db.getName(), null);
            Assertions.assertEquals(1, anyLogDbTypeByNamespace.size());
            Optional<DatabaseRegistry> result = anyLogDbTypeByNamespace.stream().findFirst();
            Assertions.assertTrue(result.isPresent());
            Assertions.assertEquals(db, result.get());
        });
    }

    @Test
    public void testSaveExternalDatabase() {
        DatabaseRegistry db = generateRandomDatabase(true);
        db.setExternallyManageable(true);
        when(entityManager.merge(db.getDatabase())).then(invocationOnMock -> invocationOnMock.getArgument(0));
        when(databasesRepository.getEntityManager()).thenReturn(entityManager);

        databaseRegistryDbaasRepository.saveExternalDatabase(db);

        verify(entityManager, times(1)).merge(db.getDatabase());
    }


    @Test
    public void testSaveAnyTypeLogDb() {
        DatabaseRegistry db = generateRandomDatabase(true);
//        when(entityManager.merge(db.getDatabase())).then(invocationOnMock -> invocationOnMock.getArgument(0));
        when(databasesRepository.getEntityManager()).thenReturn(entityManager);
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(db);

        verify(entityManager, times(1)).merge(db.getDatabase());
    }

    @Test
    public void testDelete() {
        DatabaseRegistry db = generateRandomDatabase(true);
        when(databasesRepository.findByIdOptional(db.getDatabase().getId())).thenReturn(Optional.of(db.getDatabase()));
        databaseRegistryDbaasRepository.delete(db);

        verify(databasesRepository, times(1)).delete(eq(db.getDatabase()));
    }

    @Test
    public void testDeleteById() {
        DatabaseRegistry db = generateRandomDatabase(true);

        when(databasesRepository.findByIdOptional(db.getDatabase().getId())).thenReturn(Optional.of(db.getDatabase()));
        when(databaseRegistryRepository.findByIdOptional(db.getId())).thenReturn(Optional.of(db));
        databaseRegistryDbaasRepository.deleteById(db.getId());

        verify(databasesRepository, times(1)).delete(eq(db.getDatabase()));
    }

    @Test
    public void testFindAllInternalDatabases() {
        DatabaseRegistry db_1 = generateRandomDatabase(true);
        db_1.setExternallyManageable(true);
        DatabaseRegistry db_2 = generateRandomDatabase(true);
        db_2.setExternallyManageable(false);
        List<DatabaseRegistry> dbs = Stream.of(db_1, db_2).toList();

        when(databaseRegistryRepository.listAll()).thenReturn(dbs);

        List<DatabaseRegistry> expected = Stream.of(db_2).collect(Collectors.toList());
        List<DatabaseRegistry> result = databaseRegistryDbaasRepository.findAllInternalDatabases();
        Assertions.assertEquals(expected, result);
    }

    @Test
    public void testDeleteExternalDatabases() {
        DatabaseRegistry db_1 = generateRandomDatabase(true);
        db_1.setExternallyManageable(true);
        db_1.setMarkedForDrop(true);
        DatabaseRegistry db_2 = generateRandomDatabase(true);
        db_2.setExternallyManageable(false);
        db_2.setMarkedForDrop(true);
        List<DatabaseRegistry> dbs = Stream.of(db_1, db_2).toList();

        when(databasesRepository.findByIdOptional(db_1.getDatabase().getId())).thenReturn(Optional.of(db_1.getDatabase()));
        when(databaseRegistryRepository.findByIdOptional(db_1.getId())).thenReturn(Optional.of(db_1));

        for (int i = 0; i < dbs.size(); i++) {
            databaseRegistryDbaasRepository.deleteExternalDatabases(Collections.singletonList(dbs.get(i).getDatabase()), dbs.get(i).getNamespace());

            int invocations = dbs.get(i).isExternallyManageable() ? 1 : 0;
            verify(databasesRepository, times(invocations)).delete(eq(dbs.get(i).getDatabase()));
        }
    }

    private DatabaseRegistry generateRandomDatabase(boolean isServiceDb) {
        Database db = new Database();
        db.setId(UUID.randomUUID());
        db.setType("type-" + UUID.randomUUID().toString());
        db.setAdapterId("adapter-id-" + UUID.randomUUID().toString());
        db.setName("name-" + UUID.randomUUID().toString());
        String namespace = "namespace-" + UUID.randomUUID().toString();
        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", namespace);
        classifier.put("microserviceName", "microserviceName-" + UUID.randomUUID().toString());
        if (isServiceDb) {
            classifier.put("isServiceDb", true);
        } else {
            classifier.put("tenantId", UUID.randomUUID().toString());
        }
        db.setClassifier(classifier);
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setDatabase(db);
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setNamespace(namespace);
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        databaseRegistries.add(databaseRegistry);
        db.setDatabaseRegistry(databaseRegistries);
        HashMap<String, Object> connectionProperties = new HashMap<>();
        connectionProperties.put("username", UUID.randomUUID().toString());
        connectionProperties.put("password", UUID.randomUUID().toString());
        connectionProperties.put("encryptedPassword", connectionProperties.get("password"));
        connectionProperties.put("role", Role.ADMIN.toString());

        DbResource resource = new DbResource("someKind", "someName");
        db.setResources(Arrays.asList(resource));
        db.setConnectionProperties(Arrays.asList(connectionProperties));
        return databaseRegistry;
    }

}
