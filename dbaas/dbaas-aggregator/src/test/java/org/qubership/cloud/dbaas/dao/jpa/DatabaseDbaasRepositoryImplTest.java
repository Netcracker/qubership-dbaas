package org.qubership.cloud.dbaas.dao.jpa;

import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.repositories.h2.H2DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.repositories.h2.H2DatabaseRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
public class DatabaseDbaasRepositoryImplTest {

    private static MockedStatic<QuarkusTransaction> mockedStatic;

    private DatabaseDbaasRepositoryImpl databaseDbaasRepositoryImpl;

    @Mock
    DatabasesRepository databasesRepository;
    @Mock
    H2DatabaseRepository h2DatabaseRepository;
    @Mock
    H2DatabaseRegistryRepository h2DatabaseRegistryRepository;

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
        databaseDbaasRepositoryImpl = new DatabaseDbaasRepositoryImpl(databasesRepository, h2DatabaseRepository, h2DatabaseRegistryRepository);
    }

    @Test
    public void testFindAnyLogDbTypeByNamespace() {
        DatabaseRegistry db_1 = generateRandomDatabase(true);
        DatabaseRegistry db_2 = generateRandomDatabase(true);
        List<DatabaseRegistry> dbs = Stream.of(db_1, db_2).toList();

        dbs.forEach(db -> {
            when(databasesRepository.findByNamespace(eq(db.getNamespace()))).then(invocationOnMock -> Stream.of(db).map(DatabaseRegistry::getDatabase).collect(Collectors.toList()));

            List<Database> anyLogDbTypeByNamespace = databaseDbaasRepositoryImpl.findAnyLogDbTypeByNamespace(db.getNamespace());
            Optional<Database> result = anyLogDbTypeByNamespace.stream().findFirst();
            Assertions.assertTrue(result.isPresent());
            Assertions.assertEquals(db.getDatabase(), result.get());
        });
    }

    @Test
    public void testFindInternalDatabaseByNamespace() {
        DatabaseRegistry db_1 = generateRandomDatabase(true);
        db_1.setExternallyManageable(true);
        DatabaseRegistry db_2 = generateRandomDatabase(true);
        db_2.setExternallyManageable(false);
        List<DatabaseRegistry> dbs = Stream.of(db_1, db_2).toList();

        dbs.forEach(db -> {
            when(databasesRepository.findByNamespace(eq(db.getNamespace()))).then(invocationOnMock -> Stream.of(db).map(DatabaseRegistry::getDatabase).collect(Collectors.toList()));

            List<Database> anyLogDbTypeByNamespace = databaseDbaasRepositoryImpl.findInternalDatabaseByNamespace(db.getNamespace());
            Optional<Database> result = anyLogDbTypeByNamespace.stream().findFirst();
            Assertions.assertEquals(!db.isExternallyManageable(), result.isPresent());
        });
    }

    @Test
    public void testFindInternalDatabasesByNamespaceAndType() {
        DatabaseRegistry db_1 = generateRandomDatabase(true);
        db_1.setExternallyManageable(true);
        DatabaseRegistry db_2 = generateRandomDatabase(true);
        db_2.setExternallyManageable(false);
        List<DatabaseRegistry> dbs = Stream.of(db_1, db_2).toList();

        dbs.forEach(db -> {
            when(databasesRepository.findByNamespaceAndType(eq(db.getNamespace()), eq(db.getType()))).then(invocationOnMock -> Stream.of(db).map(DatabaseRegistry::getDatabase).collect(Collectors.toList()));

            List<Database> anyLogDbTypeByNamespace = databaseDbaasRepositoryImpl.findInternalDatabasesByNamespaceAndType(db.getNamespace(), db.getType());
            Optional<Database> result = anyLogDbTypeByNamespace.stream().findFirst();
            Assertions.assertEquals(!db.isExternallyManageable(), result.isPresent());
        });
    }

    @Test
    public void testFindById() {
        Database db = generateRandomDatabase(true).getDatabase();
        when(databasesRepository.findByIdOptional(eq(db.getId()))).then(invocationOnMock -> Optional.of(db));

        Optional<Database> result = databaseDbaasRepositoryImpl.findById(db.getId());
        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals(db, result.get());
    }


    @Test
    void testReloadH2Cache() {
        when(databasesRepository.listAll()).thenThrow(new RuntimeException("test-exception"));
        Assertions.assertThrows(RuntimeException.class, () -> databaseDbaasRepositoryImpl.reloadH2Cache());
        verify(h2DatabaseRepository, never()).listAll();
        verify(h2DatabaseRepository, never()).deleteAll();
    }

    @Test
    public void testFindExternalDatabasesByNamespace() {
        DatabaseRegistry db_1 = generateRandomDatabase(true);
        db_1.setExternallyManageable(true);
        DatabaseRegistry db_2 = generateRandomDatabase(true);
        db_2.setExternallyManageable(false);
        List<DatabaseRegistry> dbs = Stream.of(db_1, db_2).toList();

        dbs.forEach(db -> {
            when(databasesRepository.findByNamespace(eq(db.getNamespace()))).then(invocationOnMock -> Stream.of(db).map(DatabaseRegistry::getDatabase).collect(Collectors.toList()));

            List<Database> anyLogDbTypeByNamespace = databaseDbaasRepositoryImpl.findExternalDatabasesByNamespace(db.getNamespace());
            Optional<Database> result = anyLogDbTypeByNamespace.stream().findFirst();
            Assertions.assertEquals(db.isExternallyManageable(), result.isPresent());
        });
    }

    @Test
    public void testDeleteAll() {
        DatabaseRegistry db_1 = generateRandomDatabase(true);
        db_1.setExternallyManageable(true);
        DatabaseRegistry db_2 = generateRandomDatabase(true);
        db_2.setExternallyManageable(false);
        List<Database> dbs = Stream.of(db_1, db_2).map(DatabaseRegistry::getDatabase).collect(Collectors.toList());

        databaseDbaasRepositoryImpl.deleteAll(dbs);

        verify(databasesRepository).deleteById(db_1.getDatabase().getId());
        verify(databasesRepository).deleteById(db_2.getDatabase().getId());
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
