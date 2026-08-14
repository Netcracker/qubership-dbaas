package com.netcracker.cloud.dbaas.service.processengine.tasks;

import com.netcracker.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import com.netcracker.cloud.dbaas.dto.declarative.DatabaseDeclaration;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.shared.AbstractDbState;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import com.netcracker.cloud.dbaas.service.BlueGreenService;
import com.netcracker.cloud.dbaas.utils.DatabaseBuilder;
import com.netcracker.core.scheduler.po.DataContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static com.netcracker.cloud.dbaas.entity.shared.AbstractDbState.DatabaseStateStatus.CREATED;
import static com.netcracker.cloud.dbaas.entity.shared.AbstractDbState.DatabaseStateStatus.PROCESSING;
import static com.netcracker.cloud.dbaas.utils.DatabaseBuilder.PG_TYPE;
import static com.netcracker.cloud.dbaas.utils.DatabaseBuilder.POSTGRES_ADAPTER_ID;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BackupDatabaseTaskTest {
    private final UUID backupId = UUID.randomUUID();
    private final String namespace = "namespace";

    @Mock
    DataContext dataContext;
    @Mock
    BlueGreenService blueGreenService;
    @Mock
    LogicalDbDbaasRepository logicalDbDbaasRepository;
    @Mock
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @InjectMocks
    BackupDatabaseTask backupDatabaseTask;

    private CloneDatabaseProcessObject processObject;

    @BeforeEach
    void setUp() {
        DatabaseDeclaration declaration = new DatabaseDeclaration();
        declaration.setType(PG_TYPE);

        processObject = new CloneDatabaseProcessObject();
        processObject.setBackupId(backupId);
        processObject.setSourceNamespace(namespace);
        processObject.setSourceClassifier(new TreeMap<>());
        processObject.setConfig(new DatabaseDeclarativeConfig(declaration, new TreeMap<>(), namespace));

        when(dataContext.get("processObject")).thenReturn(processObject);
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
    }

    @Test
    void testExecuteTask_shouldWaitUntilSourceDatabaseIsReady() {
        DatabaseRegistry processingRegistryWithoutAdapter = databaseRegistry(PROCESSING, null);
        DatabaseRegistry processingRegistry = databaseRegistry(PROCESSING, POSTGRES_ADAPTER_ID);
        DatabaseRegistry readyRegistry = databaseRegistry(CREATED, POSTGRES_ADAPTER_ID);
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(
                processObject.getSourceClassifier(), processObject.getConfig().getType()))
                .thenReturn(Optional.empty(), Optional.of(processingRegistryWithoutAdapter),
                        Optional.of(processingRegistry), Optional.of(readyRegistry));

        backupDatabaseTask.executeTask(dataContext);

        verify(databaseRegistryDbaasRepository, times(4)).getDatabaseByClassifierAndType(
                processObject.getSourceClassifier(), processObject.getConfig().getType());
        verify(blueGreenService).createDatabaseBackup(backupId, namespace, readyRegistry.getId());
    }

    @Test
    void testExecuteTask_shouldStartBackupImmediatelyForReadySourceDatabase() {
        DatabaseRegistry readyRegistry = databaseRegistry(CREATED, POSTGRES_ADAPTER_ID);
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(
                processObject.getSourceClassifier(), processObject.getConfig().getType()))
                .thenReturn(Optional.of(readyRegistry));

        backupDatabaseTask.executeTask(dataContext);

        verify(databaseRegistryDbaasRepository).getDatabaseByClassifierAndType(
                processObject.getSourceClassifier(), processObject.getConfig().getType());
        verify(blueGreenService).createDatabaseBackup(backupId, namespace, readyRegistry.getId());
    }

    private DatabaseRegistry databaseRegistry(AbstractDbState.DatabaseStateStatus state,
                                              String adapterId) {
        return new DatabaseBuilder()
                .state(state)
                .adapterId(adapterId)
                .registry()
                .build()
                .getDatabaseRegistry()
                .getFirst();
    }
}
