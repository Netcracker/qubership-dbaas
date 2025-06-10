package org.qubership.cloud.dbaas.service.processengine.processes;

import org.qubership.cloud.dbaas.dto.bluegreen.AbstractDatabaseProcessObject;
import org.qubership.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import org.qubership.cloud.dbaas.dto.bluegreen.NewDatabaseProcessObject;
import org.qubership.cloud.dbaas.service.processengine.tasks.*;
import org.qubership.core.scheduler.po.ProcessDefinition;
import org.qubership.core.scheduler.po.task.NamedTask;

import java.io.Serializable;
import java.util.List;

import static org.qubership.cloud.dbaas.service.processengine.Const.*;


public class AllDatabasesCreationProcess extends ProcessDefinition implements Serializable {

    public AllDatabasesCreationProcess(List<AbstractDatabaseProcessObject> processObjects) {
        super("create_all_databases");
        String[] taskNames = new String[processObjects.size()];
        int counter = 0;
        for (AbstractDatabaseProcessObject processObject : processObjects) {
            if (processObject instanceof NewDatabaseProcessObject) {
                String taskName = NEW_DATABASE_TASK + ":" + processObject.getId().toString();
                addTask(new NamedTask(NewDatabaseTask.class, taskName));
                taskNames[counter] = taskName;
            }
            if (processObject instanceof CloneDatabaseProcessObject) {
                addTask(
                        new NamedTask(BackupDatabaseTask.class, BACKUP_TASK + ":" + processObject.getId().toString())
                );
                addTask(
                        new NamedTask(RestoreDatabaseTask.class, RESTORE_TASK + ":" + processObject.getId().toString()),
                        BACKUP_TASK + ":" + processObject.getId().toString()
                );
                addTask(
                        new NamedTask(DeleteBackupTask.class, DELETE_BACKUP_TASK + ":" + processObject.getId().toString()),
                        RESTORE_TASK + ":" + processObject.getId().toString()
                );
                taskNames[counter] = DELETE_BACKUP_TASK + ":" + processObject.getId().toString();
            }
            counter++;

        }

        addTask(
                new NamedTask(UpdateBgStateTask.class, UPDATE_BG_STATE_TASK), taskNames
        );
    }
}
