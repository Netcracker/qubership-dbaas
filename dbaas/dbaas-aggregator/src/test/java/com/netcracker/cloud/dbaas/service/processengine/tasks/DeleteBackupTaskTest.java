package com.netcracker.cloud.dbaas.service.processengine.tasks;

import com.netcracker.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import com.netcracker.cloud.dbaas.dto.declarative.DatabaseDeclaration;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import com.netcracker.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.BackupsRepository;
import com.netcracker.cloud.dbaas.service.BlueGreenService;
import com.netcracker.core.scheduler.po.DataContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.TreeMap;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class DeleteBackupTaskTest {
    @Test
    void executeTask() {
        UUID backupId = UUID.randomUUID();
        String namespace = "namespace";

        DataContext dataContext = Mockito.mock(DataContext.class);
        CloneDatabaseProcessObject processObject = new CloneDatabaseProcessObject();
        processObject.setBackupId(backupId);
        processObject.setSourceNamespace(namespace);
        processObject.setConfig(new DatabaseDeclarativeConfig(new DatabaseDeclaration(), new TreeMap<>(), namespace));
        doReturn(processObject).when(dataContext).get(eq("processObject"));
        NamespaceBackup namespaceBackup = new NamespaceBackup();

        BlueGreenService blueGreenService = mock(BlueGreenService.class);
        BackupsRepository backupsRepository = mock(BackupsRepository.class);
        doReturn(namespaceBackup).when(backupsRepository).findById(backupId);

        DeleteBackupTask deleteBackupTask = new DeleteBackupTask();
        deleteBackupTask.blueGreenService = blueGreenService;
        deleteBackupTask.executeTask(dataContext);
        verify(blueGreenService, times(1)).deleteBackup(backupId);
    }
}
