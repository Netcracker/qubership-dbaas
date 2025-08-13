package com.netcracker.cloud.dbaas.service.processengine.tasks;

import com.netcracker.cloud.dbaas.dto.bluegreen.NewDatabaseProcessObject;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import com.netcracker.cloud.dbaas.service.BlueGreenService;
import com.netcracker.core.scheduler.po.DataContext;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

import static com.netcracker.cloud.dbaas.Constants.VERSION_STATE;


@Slf4j
@ApplicationScoped
public class NewDatabaseTask extends AbstractDbaaSTask implements Serializable {
    public NewDatabaseTask() {
        super(NewDatabaseTask.class.getName());
    }

    @Override
    protected void executeTask(DataContext context) {
        BlueGreenService blueGreenService = Arc.container().instance(BlueGreenService.class).get();
        NewDatabaseProcessObject processObject = (NewDatabaseProcessObject) context.get("processObject");

        DatabaseDeclarativeConfig configuration = processObject.getConfig();
        String version = null;
        if (VERSION_STATE.equals(configuration.getVersioningType())) {
            version = processObject.getVersion();
        }

        updateState(context, "Creating new DB with classifier " + configuration.getClassifier());
        blueGreenService.createOrUpdateDatabaseWarmup(configuration, version);
        log.debug("Done '{}' task with classifier = {}", super.getName(), configuration.getClassifier());
    }
}
