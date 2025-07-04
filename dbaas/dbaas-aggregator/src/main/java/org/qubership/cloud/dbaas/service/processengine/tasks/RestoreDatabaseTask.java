package org.qubership.cloud.dbaas.service.processengine.tasks;

import org.qubership.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BackupsRepository;
import org.qubership.cloud.dbaas.service.BlueGreenService;
import org.qubership.cloud.dbaas.service.DBaaService;
import org.qubership.core.scheduler.po.DataContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.*;

import static org.qubership.cloud.dbaas.Constants.SCOPE;
import static org.qubership.cloud.dbaas.Constants.SCOPE_VALUE_TENANT;
import static org.qubership.cloud.dbaas.Constants.TENANT_ID;
import static org.qubership.cloud.dbaas.Constants.VERSION_STATE;

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
