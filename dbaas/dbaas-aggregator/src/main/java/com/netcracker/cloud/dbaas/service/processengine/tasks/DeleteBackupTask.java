package com.netcracker.cloud.dbaas.service.processengine.tasks;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.netcracker.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import com.netcracker.cloud.dbaas.service.BlueGreenService;
import com.netcracker.core.scheduler.po.DataContext;
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
StructuredLog.debug(log, "Done '' task with backupId =", "backupId", super.getName(), "database", cloneDatabaseProcessObject.getBackupId());
    }
}
