package org.qubership.cloud.dbaas.service.processengine.tasks;

import org.qubership.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import org.qubership.cloud.dbaas.dto.declarative.DatabaseDeclaration;
import org.qubership.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import org.qubership.cloud.dbaas.service.BlueGreenService;
import org.qubership.core.scheduler.po.DataContext;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;

class BackupDatabaseTaskTest {
    @Test
    void executeTask() {
        UUID registryId = UUID.randomUUID();
        UUID backupId = UUID.randomUUID();
        String namespace = "namespace";

        DataContext dataContext = Mockito.mock(DataContext.class);
        CloneDatabaseProcessObject processObject = new CloneDatabaseProcessObject();
        processObject.setBackupId(backupId);
        processObject.setSourceNamespace(namespace);
        processObject.setConfig(new DatabaseDeclarativeConfig(new DatabaseDeclaration(), new TreeMap<>(), namespace));
        doReturn(processObject).when(dataContext).get(eq("processObject"));

        BlueGreenService blueGreenService = mock(BlueGreenService.class);
        LogicalDbDbaasRepository logicalDbDbaasRepository = mock(LogicalDbDbaasRepository.class);
        DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository = mock(DatabaseRegistryDbaasRepository.class);
        DatabaseRegistry databaseRegistry = mock(DatabaseRegistry.class);

        doReturn(null).when(blueGreenService).createDatabaseBackup(backupId, namespace, registryId);
        doReturn(databaseRegistryDbaasRepository).when(logicalDbDbaasRepository).getDatabaseRegistryDbaasRepository();
        doReturn(registryId).when(databaseRegistry).getId();
        AtomicInteger retryCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (retryCount.incrementAndGet() < 5) {
                return Optional.empty();
            } else {
                return Optional.of(databaseRegistry);
            }
        }).when(databaseRegistryDbaasRepository).getDatabaseByClassifierAndType(processObject.getSourceClassifier(), processObject.getConfig().getType());

        BackupDatabaseTask backupDatabaseTask = new BackupDatabaseTask();
        backupDatabaseTask.blueGreenService = blueGreenService;
        backupDatabaseTask.logicalDbDbaasRepository = logicalDbDbaasRepository;
        backupDatabaseTask.executeTask(dataContext);
        verify(databaseRegistryDbaasRepository, times(5)).getDatabaseByClassifierAndType(processObject.getSourceClassifier(), processObject.getConfig().getType());
        verify(blueGreenService, times(1)).createDatabaseBackup(backupId, namespace, registryId);
    }
}
