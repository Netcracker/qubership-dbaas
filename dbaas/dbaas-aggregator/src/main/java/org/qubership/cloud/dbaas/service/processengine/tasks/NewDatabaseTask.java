package org.qubership.cloud.dbaas.service.processengine.tasks;

import org.qubership.cloud.dbaas.dto.bluegreen.NewDatabaseProcessObject;
import org.qubership.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import org.qubership.cloud.dbaas.service.BlueGreenService;
import org.qubership.core.scheduler.po.DataContext;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

import static org.qubership.cloud.dbaas.Constants.VERSION_STATE;


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
