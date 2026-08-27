package com.netcracker.cloud.dbaas.service.processengine.processes;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.dbaas.dto.bluegreen.AbstractDatabaseProcessObject;
import com.netcracker.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import com.netcracker.cloud.dbaas.dto.bluegreen.NewDatabaseProcessObject;
import com.netcracker.cloud.dbaas.service.processengine.tasks.*;
import com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextObject;
import com.netcracker.core.scheduler.po.ProcessDefinition;
import com.netcracker.core.scheduler.po.task.NamedTask;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.netcracker.cloud.dbaas.service.processengine.Const.*;
import static com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextObject.X_REQUEST_ID;


public class AllDatabasesCreationProcess extends ProcessDefinition implements Serializable {

    public AllDatabasesCreationProcess(List<AbstractDatabaseProcessObject> processObjects, String namespace, String operation, String version) {
        super("create_all_databases");
        String[] taskNames = new String[processObjects.size()];
        int counter = 0;
        for (AbstractDatabaseProcessObject processObject : processObjects) {
            if (processObject instanceof NewDatabaseProcessObject) {
                String taskName = NEW_DATABASE_TASK + ":" + processObject.getId().toString();
                NamedTask newDbTask = new NamedTask(NewDatabaseTask.class, taskName);
                addTask(newDbTask);
                addTaskContext(newDbTask, Map.of("processObject", processObject));
                taskNames[counter] = taskName;
            }
            if (processObject instanceof CloneDatabaseProcessObject) {
                NamedTask backupTask = new NamedTask(BackupDatabaseTask.class, BACKUP_TASK + ":" + processObject.getId().toString());
                addTask(backupTask);
                addTaskContext(backupTask, Map.of("processObject", processObject));

                NamedTask restoreTask = new NamedTask(RestoreDatabaseTask.class, RESTORE_TASK + ":" + processObject.getId().toString());
                addTask(restoreTask, BACKUP_TASK + ":" + processObject.getId().toString());
                addTaskContext(restoreTask, Map.of("processObject", processObject));

                NamedTask deleteBackupTask = new NamedTask(DeleteBackupTask.class, DELETE_BACKUP_TASK + ":" + processObject.getId().toString());
                addTask(deleteBackupTask, RESTORE_TASK + ":" + processObject.getId().toString());
                addTaskContext(deleteBackupTask, Map.of("processObject", processObject));

                taskNames[counter] = DELETE_BACKUP_TASK + ":" + processObject.getId().toString();
            }
            counter++;
        }

        NamedTask updateBgStateTask = new NamedTask(UpdateBgStateTask.class, UPDATE_BG_STATE_TASK);
        addTask(updateBgStateTask, taskNames);
        HashMap<String, Object> bgParams = new HashMap<>();
        bgParams.put("namespace", namespace);
        bgParams.put("operation", operation);
        if (version != null) {
            bgParams.put("version", version);
        }
        addTaskContext(updateBgStateTask, bgParams);
    }

    @Override
    public ProcessDefinition addTaskContext(NamedTask task, Map<String, Object> additionalFields) {
        Map<String, Object> newContext = new HashMap<>();
        if (additionalFields != null) {
            newContext.putAll(additionalFields);
        }
        newContext.put(X_REQUEST_ID, ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId());
        super.addTaskContext(task, newContext);
        return this;
    }
}
