package org.qubership.cloud.dbaas.dao.h2;

import org.qubership.cloud.dbaas.dao.jpa.DatabaseRegistryDbaasRepositoryImpl;
import org.qubership.cloud.dbaas.entity.h2.Database;
import org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry;
import org.qubership.cloud.dbaas.repositories.h2.H2DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.repositories.h2.H2DatabaseRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class H2DatabaseRegistryRepositoryTest {

    private static MockedStatic<QuarkusTransaction> mockedStatic;

    private DatabaseRegistryDbaasRepositoryImpl databaseRegistryDbaasRepositoryImpl;

    @Mock
    private DatabasesRepository databasesRepository;
    @Mock
    private H2DatabaseRepository h2DatabaseRepository;
    @Mock
    private H2DatabaseRegistryRepository h2DatabaseRegistryRepository;
    @Mock
    private DatabaseRegistryRepository databaseRegistryRepository;

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
        databaseRegistryDbaasRepositoryImpl = new DatabaseRegistryDbaasRepositoryImpl(databasesRepository, h2DatabaseRepository, databaseRegistryRepository, h2DatabaseRegistryRepository);
    }

    @Test
    void testFindAllInternalDatabases() {
        when(databaseRegistryRepository.listAll()).thenThrow(NullPointerException.class);
        databaseRegistryDbaasRepositoryImpl.findAllInternalDatabases();
        verify(databaseRegistryRepository, times(1)).listAll();
        verify(h2DatabaseRegistryRepository, times(1)).listAll();

    }

    @Test
    void testFindAllDatabasesAnyLogType() {
        when(databaseRegistryRepository.listAll()).thenThrow(NullPointerException.class);
        databaseRegistryDbaasRepositoryImpl.findAllDatabaseRegistersAnyLogType();
        verify(databaseRegistryRepository, times(1)).listAll();
        verify(h2DatabaseRegistryRepository, times(1)).listAll();
    }

    @Test
    void testGetDatabaseByClassifierAndType() {
        DatabaseRegistry db_1 = generateRandomDatabase(true);

        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", db_1.getNamespace());

        when(databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(classifier, "someType")).thenThrow(NullPointerException.class);
        when(h2DatabaseRegistryRepository.findDatabaseRegistryByClassifierAndType(classifier, "someType")).thenReturn(Optional.of(db_1.getDatabaseRegistry().get(0)));
        databaseRegistryDbaasRepositoryImpl.getDatabaseByClassifierAndType(classifier, "someType");
        verify(databaseRegistryRepository, times(1)).findDatabaseRegistryByClassifierAndType(classifier, "someType");
        verify(h2DatabaseRegistryRepository, times(1)).findDatabaseRegistryByClassifierAndType(classifier, "someType");

    }

    @Test
    void testFindDatabasesByMicroserviceNameAndNamespace() {
        when(databaseRegistryRepository.findByNamespace("namespace")).thenThrow(NullPointerException.class);
        databaseRegistryDbaasRepositoryImpl.findDatabasesByMicroserviceNameAndNamespace("microserviceName", "namespace");
        verify(databaseRegistryRepository, times(1)).findByNamespace("namespace");
        verify(h2DatabaseRegistryRepository, times(1)).findByNamespace("namespace");
    }


    private DatabaseRegistry generateRandomDatabase(boolean isServiceDb) {
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
        return databaseRegistry;
    }


}