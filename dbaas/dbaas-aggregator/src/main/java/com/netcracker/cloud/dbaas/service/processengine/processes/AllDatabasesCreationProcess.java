package com.netcracker.cloud.dbaas.service.processengine.processes;

import com.netcracker.cloud.dbaas.dto.bluegreen.AbstractDatabaseProcessObject;
import com.netcracker.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import com.netcracker.cloud.dbaas.dto.bluegreen.NewDatabaseProcessObject;
import com.netcracker.cloud.dbaas.service.processengine.tasks.*;
import com.netcracker.core.scheduler.po.ProcessDefinition;
import com.netcracker.core.scheduler.po.task.NamedTask;

import java.io.Serializable;
import java.util.List;

import static com.netcracker.cloud.dbaas.service.processengine.Const.*;


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
