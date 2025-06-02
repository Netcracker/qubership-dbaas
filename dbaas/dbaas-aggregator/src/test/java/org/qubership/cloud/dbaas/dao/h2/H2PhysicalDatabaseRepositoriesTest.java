package org.qubership.cloud.dbaas.dao.h2;

import org.qubership.cloud.dbaas.dao.jpa.PhysicalDatabaseDbaasRepositoryImpl;
import org.qubership.cloud.dbaas.entity.h2.PhysicalDatabase;
import org.qubership.cloud.dbaas.repositories.h2.H2PhysicalDatabaseRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.PhysicalDatabasesRepository;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class H2PhysicalDatabaseRepositoriesTest {

    private static MockedStatic<QuarkusTransaction> mockedStatic;

    private PhysicalDatabaseDbaasRepositoryImpl physicalDatabaseDbaasRepositoryImpl;
    @Mock
    private PhysicalDatabasesRepository physicalDatabasesRepository;
    @Mock
    private H2PhysicalDatabaseRepository h2PhysicalDatabaseRepository;

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
        physicalDatabaseDbaasRepositoryImpl = new PhysicalDatabaseDbaasRepositoryImpl(physicalDatabasesRepository, h2PhysicalDatabaseRepository);
    }

    @Test
    void testFindAnyLogDbTypeByNamespace() {
        PhysicalDatabase db_1 = generateRandomDatabase();
        List<PhysicalDatabase> dbs = Stream.of(db_1).collect(Collectors.toList());

        when(physicalDatabasesRepository.findByType("something")).thenThrow(NullPointerException.class);
        when(h2PhysicalDatabaseRepository.findByType("something")).thenReturn(dbs);
        physicalDatabaseDbaasRepositoryImpl.findByType("something");
        verify(physicalDatabasesRepository).findByType("something");
        verify(h2PhysicalDatabaseRepository).findByType("something");
    }

    @Test
    void testFindInternalDatabaseByNamespace() {
        PhysicalDatabase db_1 = generateRandomDatabase();


        when(physicalDatabasesRepository.findByPhysicalDatabaseIdentifier("something")).thenThrow(NullPointerException.class);
        when(h2PhysicalDatabaseRepository.findByPhysicalDatabaseIdentifier("something")).thenReturn(Optional.of(db_1));
        physicalDatabaseDbaasRepositoryImpl.findByPhysicalDatabaseIdentifier("something");
        verify(physicalDatabasesRepository).findByPhysicalDatabaseIdentifier("something");
        verify(h2PhysicalDatabaseRepository).findByPhysicalDatabaseIdentifier("something");
    }

    @Test
    void testFindById() {
        PhysicalDatabase db_1 = generateRandomDatabase();

        when(physicalDatabasesRepository.findByAdapterAddress("something")).thenThrow(NullPointerException.class);
        when(h2PhysicalDatabaseRepository.findByAdapterAddress("something")).thenReturn(Optional.of(db_1));
        physicalDatabaseDbaasRepositoryImpl.findByAdapterAddress("something");
        verify(physicalDatabasesRepository).findByAdapterAddress("something");
        verify(h2PhysicalDatabaseRepository).findByAdapterAddress("something");
    }


    @Test
    void testFindAllInternalDatabases() {
        PhysicalDatabase db_1 = generateRandomDatabase();
        List<PhysicalDatabase> dbs = Stream.of(db_1).collect(Collectors.toList());

        when(physicalDatabasesRepository.listAll()).thenThrow(NullPointerException.class);
        when(h2PhysicalDatabaseRepository.listAll()).thenReturn(dbs);
        physicalDatabaseDbaasRepositoryImpl.findAll();
        verify(physicalDatabasesRepository).listAll();
        verify(h2PhysicalDatabaseRepository).listAll();

    }

    @Test
    void testFindExternalDatabasesByNamespace() {
        PhysicalDatabase db_1 = generateRandomDatabase();

        when(physicalDatabasesRepository.findByAdapterId("something")).thenThrow(NullPointerException.class);
        when(h2PhysicalDatabaseRepository.findByAdapterId("something")).thenReturn(Optional.of(db_1));
        physicalDatabaseDbaasRepositoryImpl.findByAdapterId("something");
        verify(physicalDatabasesRepository).findByAdapterId("something");
        verify(h2PhysicalDatabaseRepository).findByAdapterId("something");
    }


    @Test
    void testFindAllDatabasesAnyLogType() {
        PhysicalDatabase db_1 = generateRandomDatabase();
        List<PhysicalDatabase> dbs = Stream.of(db_1).collect(Collectors.toList());

        when(physicalDatabasesRepository.findByTypeAndGlobal("something", true)).thenThrow(NullPointerException.class);
        when(h2PhysicalDatabaseRepository.findByTypeAndGlobal("something", true)).thenReturn(dbs);
        physicalDatabaseDbaasRepositoryImpl.findGlobalByType("something");
        verify(physicalDatabasesRepository).findByTypeAndGlobal("something", true);
        verify(h2PhysicalDatabaseRepository).findByTypeAndGlobal("something", true);

    }


    @Test
    void testReloadH2CacheById() {
        PhysicalDatabase db_1 =  generateRandomDatabase();
        String id = UUID.randomUUID().toString();

        when(h2PhysicalDatabaseRepository.existsById(id)).thenReturn(true);
        when(physicalDatabasesRepository.findByIdOptional(id)).thenReturn(Optional.of(db_1.asPgEntity()));

        physicalDatabaseDbaasRepositoryImpl.reloadH2Cache(id);

        verify(physicalDatabasesRepository).findByIdOptional(id);
        verify(h2PhysicalDatabaseRepository).existsById(id);
        verify(h2PhysicalDatabaseRepository).deleteById(id);
        verify(h2PhysicalDatabaseRepository).merge(db_1);
    }

    @Test
    void testReloadH2CacheByIdNotExist() {
        PhysicalDatabase db_1 = generateRandomDatabase();
        String id = UUID.randomUUID().toString();

        when(h2PhysicalDatabaseRepository.existsById(id)).thenReturn(false);
        when(physicalDatabasesRepository.findByIdOptional(id)).thenReturn(Optional.of(db_1.asPgEntity()));

        physicalDatabaseDbaasRepositoryImpl.reloadH2Cache(id);

        verify(physicalDatabasesRepository).findByIdOptional(id);
        verify(h2PhysicalDatabaseRepository).existsById(id);
        verify(h2PhysicalDatabaseRepository, times(0)).deleteById(id);
        verify(h2PhysicalDatabaseRepository).merge(db_1);
    }

    @Test
    void testReloadH2CacheByIdNotFound() {
        PhysicalDatabase db_1 = generateRandomDatabase();
        String id = UUID.randomUUID().toString();

        when(h2PhysicalDatabaseRepository.existsById(id)).thenReturn(true);
        when(physicalDatabasesRepository.findByIdOptional(id)).thenReturn(Optional.empty());

        physicalDatabaseDbaasRepositoryImpl.reloadH2Cache(id);

        verify(physicalDatabasesRepository, times(1)).findByIdOptional(id);
        verify(h2PhysicalDatabaseRepository, times(1)).existsById(id);
        verify(h2PhysicalDatabaseRepository, times(1)).deleteById(id);
        verify(h2PhysicalDatabaseRepository, times(0)).merge(db_1);
    }

    private PhysicalDatabase generateRandomDatabase() {
        PhysicalDatabase db = new PhysicalDatabase();
        db.setId(UUID.randomUUID().toString());
        db.setType("type-" + UUID.randomUUID());
        return db;
    }
}
