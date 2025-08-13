package com.netcracker.cloud.dbaas.service.processengine.tasks;

import com.netcracker.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.BackupsRepository;
import com.netcracker.cloud.dbaas.service.BlueGreenService;
import com.netcracker.cloud.dbaas.service.DBaaService;
import com.netcracker.core.scheduler.po.DataContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.*;

import static com.netcracker.cloud.dbaas.Constants.SCOPE;
import static com.netcracker.cloud.dbaas.Constants.SCOPE_VALUE_TENANT;
import static com.netcracker.cloud.dbaas.Constants.TENANT_ID;
import static com.netcracker.cloud.dbaas.Constants.VERSION_STATE;

@Slf4j
@ApplicationScoped
public class RestoreDatabaseTask extends AbstractDbaaSTask implements Serializable {

    @Inject
    BlueGreenService blueGreenService;
    @Inject
    BackupsRepository backupsRepository;
    @Inject
    DBaaService dBaaService;

    public RestoreDatabaseTask() {
        super(RestoreDatabaseTask.class.getName());
    }

    @Override
    protected void executeTask(DataContext context) {

        CloneDatabaseProcessObject cloneDatabaseProcessObject = (CloneDatabaseProcessObject) context.get("processObject");

        DatabaseDeclarativeConfig configuration = cloneDatabaseProcessObject.getConfig();
        String version = null;
        if (VERSION_STATE.equals(configuration.getVersioningType())) {
            version = cloneDatabaseProcessObject.getVersion();
        }
        if (SCOPE_VALUE_TENANT.equals(configuration.getClassifier().get(SCOPE))) {
            SortedMap<String, Object> sourceClassifier = cloneDatabaseProcessObject.getSourceClassifier();
            configuration.getClassifier().put(TENANT_ID, sourceClassifier.get(TENANT_ID));
        }
        Map<String, String> prefixMap = new HashMap<>();
        DatabaseRegistry databaseByClassifierAndType = dBaaService.findDatabaseByClassifierAndType(cloneDatabaseProcessObject.getSourceClassifier(), configuration.getType(), false);
        prefixMap.put(databaseByClassifierAndType.getName(), configuration.getNamePrefix());


        updateState(context, "Restoring DB with target classifier " + configuration.getClassifier());
        UUID namespaceBackupId = cloneDatabaseProcessObject.getBackupId();
        backupsRepository.findByIdOptional(cloneDatabaseProcessObject.getBackupId()).orElseThrow();
        Optional<Database> database = blueGreenService.restoreDatabase(configuration, version,
                cloneDatabaseProcessObject.getBackupId(), cloneDatabaseProcessObject.getRestoreId(), namespaceBackupId, prefixMap);

        log.debug("Done '{}' task with database = {}", super.getName(), database);
    }

}
