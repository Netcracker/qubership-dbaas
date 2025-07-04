package org.qubership.cloud.dbaas.dao.h2;

import org.qubership.cloud.dbaas.dao.jpa.DatabaseDbaasRepositoryImpl;
import org.qubership.cloud.dbaas.entity.h2.Database;
import org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry;
import org.qubership.cloud.dbaas.repositories.h2.H2DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.repositories.h2.H2DatabaseRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class H2DatabaseRepositoriesTest {

    private static MockedStatic<QuarkusTransaction> mockedStatic;

    private DatabaseDbaasRepositoryImpl databaseDbaasRepositoryImpl;
    @Mock
    private DatabasesRepository databasesRepository;
    @Mock
    private H2DatabaseRepository h2DatabaseRepository;
    @Mock
    private H2DatabaseRegistryRepository h2DatabaseRegistryRepository;

    @Mock
    private EntityManager entityManager;

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
    void before() {
        databaseDbaasRepositoryImpl = new DatabaseDbaasRepositoryImpl(databasesRepository, h2DatabaseRepository, h2DatabaseRegistryRepository);
    }

    @Test
    void testFindAnyLogDbTypeByNamespace() {
        Database db_1 = generateRandomDatabase(true);
        List<Database> dbs = Stream.of(db_1).collect(Collectors.toList());

        when(databasesRepository.findByNamespace("something")).thenThrow(NullPointerException.class);
        when(h2DatabaseRepository.findByNamespace("something")).thenReturn(dbs);
        databaseDbaasRepositoryImpl.findAnyLogDbTypeByNamespace("something");
        verify(databasesRepository).findByNamespace("something");
        verify(h2DatabaseRepository).findByNamespace("something");
    }

    @Test
    void testFindInternalDatabaseByNamespace() {
        Database db_1 = generateRandomDatabase(true);
        List<Database> dbs = Stream.of(db_1).collect(Collectors.toList());

        when(databasesRepository.findByNamespace("something")).thenThrow(NullPointerException.class);
        when(h2DatabaseRepository.findByNamespace("something")).thenReturn(dbs);
        databaseDbaasRepositoryImpl.findInternalDatabaseByNamespace("something");
        verify(databasesRepository).findByNamespace("something");
        verify(h2DatabaseRepository).findByNamespace("something");
    }

    @Test
    void testFindById() {
        Database db_1 = generateRandomDatabase(true);
        UUID id = UUID.randomUUID();

        when(databasesRepository.findByIdOptional(id)).thenThrow(NullPointerException.class);
        when(h2DatabaseRepository.findByIdOptional(id)).thenReturn(Optional.of(db_1));
        databaseDbaasRepositoryImpl.findById(id);
        verify(databasesRepository).findByIdOptional(id);
        verify(h2DatabaseRepository).findByIdOptional(id);
    }

    @Test
    void testFindExternalDatabasesByNamespace() {
        Database db_1 = generateRandomDatabase(true);
        List<Database> dbs = Stream.of(db_1).collect(Collectors.toList());

        when(databasesRepository.findByNamespace("something")).thenThrow(NullPointerException.class);
        when(h2DatabaseRepository.findByNamespace("something")).thenReturn(dbs);
        databaseDbaasRepositoryImpl.findExternalDatabasesByNamespace("something");
        verify(databasesRepository).findByNamespace("something");
        verify(h2DatabaseRepository).findByNamespace("something");
    }

    @Test
    void testReloadH2CacheById() {
        Database db_1 = generateRandomDatabase(true);
        UUID id = db_1.getId();
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());

        databaseRegistry.setDatabase(db_1);
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        databaseRegistries.add(databaseRegistry);
        db_1.setDatabaseRegistry(databaseRegistries);


        when(databasesRepository.findByIdOptional(id)).thenReturn(Optional.of(db_1.asPgEntity()));
        when(h2DatabaseRepository.findByIdOptional(id)).thenReturn(Optional.of(db_1));
        when(h2DatabaseRegistryRepository.findByIdOptional(databaseRegistry.getId())).thenReturn(Optional.of(databaseRegistry));

        databaseDbaasRepositoryImpl.reloadH2Cache(id);

        verify(databasesRepository).findByIdOptional(id);
        verify(h2DatabaseRepository).findByIdOptional(id);
        verify(h2DatabaseRepository).deleteById(db_1.getId());
        verify(h2DatabaseRegistryRepository).findByIdOptional(databaseRegistry.getId());
        verify(h2DatabaseRepository).merge(db_1);
    }

    @Test
    void testReloadH2CacheByIdNotExist() {
        Database db_1 = generateRandomDatabase(true);
        UUID id = db_1.getId();

        when(databasesRepository.findByIdOptional(id)).thenReturn(Optional.of(db_1.asPgEntity()));

        databaseDbaasRepositoryImpl.reloadH2Cache(id);

        verify(databasesRepository).findByIdOptional(id);
        verify(h2DatabaseRepository).findByIdOptional(id);
        verify(h2DatabaseRepository, times(0)).deleteById(id);
        verify(h2DatabaseRepository).merge(db_1);
    }

    @Test
    void testReloadH2CacheByIdNotFound() {
        Database db_1 = generateRandomDatabase(true);
        UUID id = db_1.getId();
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setDatabase(db_1);
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        databaseRegistries.add(databaseRegistry);
        db_1.setDatabaseRegistry(databaseRegistries);

        when(databasesRepository.findByIdOptional(id)).thenReturn(Optional.empty());

        when(h2DatabaseRegistryRepository.findByIdOptional(databaseRegistry.getId())).thenReturn(Optional.of(databaseRegistry));
        when(h2DatabaseRepository.findByIdOptional(id)).thenReturn(Optional.of(db_1));

        databaseDbaasRepositoryImpl.reloadH2Cache(id);

        verify(databasesRepository, times(1)).findByIdOptional(id);
        verify(h2DatabaseRepository, times(1)).findByIdOptional(id);
        verify(h2DatabaseRepository, times(1)).deleteById(db_1.getId());
        verify(h2DatabaseRepository, times(0)).merge(db_1);
    }

    private Database generateRandomDatabase(boolean isServiceDb) {
        Database db = new Database();
        db.setId(UUID.randomUUID());

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
        databaseRegistry.setType("type-" + UUID.randomUUID().toString());
        databaseRegistry.setNamespace(namespace);
        db.setDatabaseRegistry(Arrays.asList(databaseRegistry));
        db.getDatabaseRegistry().get(0).setDatabase(db);
        HashMap<String, Object> connectionProperties = new HashMap<>();
        connectionProperties.put("username", UUID.randomUUID().toString());
        connectionProperties.put("password", UUID.randomUUID().toString());
        connectionProperties.put("encryptedPassword", connectionProperties.get("password"));

        db.setConnectionProperties(Arrays.asList(connectionProperties));
        return db;
    }
}
