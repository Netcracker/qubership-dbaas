package org.qubership.cloud.dbaas.dao.jpa;

import org.qubership.cloud.dbaas.repositories.h2.H2PhysicalDatabaseRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.PhysicalDatabasesRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PhysicalDatabaseDbaasRepositoryImplTest {

    PhysicalDatabaseDbaasRepositoryImpl physicalDatabaseDbaasRepository;

    @Mock
    H2PhysicalDatabaseRepository h2PhysicalDatabaseRepository;
    @Mock
    PhysicalDatabasesRepository physicalDatabasesRepository;

    @BeforeEach
    public void before() {
        physicalDatabaseDbaasRepository = new PhysicalDatabaseDbaasRepositoryImpl(physicalDatabasesRepository, h2PhysicalDatabaseRepository);
    }

    @Test
    void testReloadH2Cache() {
        when(physicalDatabasesRepository.listAll()).thenThrow(new RuntimeException("test-exception"));
        Assertions.assertThrows(RuntimeException.class, () -> physicalDatabaseDbaasRepository.reloadH2Cache());
        verify(h2PhysicalDatabaseRepository, never()).listAll();
        verify(h2PhysicalDatabaseRepository, never()).deleteAll();
    }
}