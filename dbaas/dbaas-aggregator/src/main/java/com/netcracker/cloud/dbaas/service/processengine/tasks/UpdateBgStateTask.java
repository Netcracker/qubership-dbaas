package com.netcracker.cloud.dbaas.service.processengine.tasks;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.netcracker.cloud.dbaas.service.BlueGreenService;
import com.netcracker.core.scheduler.po.DataContext;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

import static com.netcracker.cloud.dbaas.Constants.APPLY_CONFIG_OPERATION;
import static com.netcracker.cloud.dbaas.Constants.WARMUP_OPERATION;

@Slf4j
@ApplicationScoped
public class UpdateBgStateTask extends AbstractDbaaSTask implements Serializable {

    public UpdateBgStateTask() {
        super(UpdateBgStateTask.class.getName());
    }

    @Override
    protected void executeTask(DataContext context) {

        String operation = (String) context.get("operation");
        if (APPLY_CONFIG_OPERATION.equals(operation)) {
StructuredLog.info(log, "Nothing to change in '' task because operation is", "arg0", super.getName(), "operation", operation);
            return;
        }

        BlueGreenService blueGreenService = Arc.container().instance(BlueGreenService.class).get();

        String namespace = (String) context.get("namespace");

        String version = null;
        if (context.get("version") != null) {
            version = (String) context.get("version");
        }
        if (WARMUP_OPERATION.equals(operation)) {
            blueGreenService.updateWarmupBgNamespace(namespace, version);
        }

StructuredLog.debug(log, "Done '' task update state =", "state", super.getName(), "namespace", namespace);
    }

}
