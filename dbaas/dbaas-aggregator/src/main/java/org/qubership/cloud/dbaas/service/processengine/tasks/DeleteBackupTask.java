package org.qubership.cloud.dbaas.service.processengine.tasks;

import org.qubership.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import org.qubership.cloud.dbaas.service.BlueGreenService;
import org.qubership.core.scheduler.po.DataContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

@Slf4j
@ApplicationScoped
public class DeleteBackupTask extends AbstractDbaaSTask implements Serializable {
    @Inject
    BlueGreenService blueGreenService;

    protected DeleteBackupTask() {
        super(DeleteBackupTask.class.getName());
    }

    @Override
    protected void executeTask(DataContext context) {
        CloneDatabaseProcessObject cloneDatabaseProcessObject = (CloneDatabaseProcessObject) context.get("processObject");

        updateState(context, "Deleting backup for DB with target classifier " + cloneDatabaseProcessObject.getConfig().getClassifier());
        blueGreenService.deleteBackup(cloneDatabaseProcessObject.getBackupId());
        log.debug("Done '{}' task with backupId = {}", super.getName(), cloneDatabaseProcessObject.getBackupId());
    }
}
