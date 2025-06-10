package org.qubership.cloud.dbaas.service.processengine.tasks;

import org.qubership.cloud.dbaas.service.BlueGreenService;
import org.qubership.core.scheduler.po.DataContext;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

import static org.qubership.cloud.dbaas.Constants.APPLY_CONFIG_OPERATION;
import static org.qubership.cloud.dbaas.Constants.WARMUP_OPERATION;

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
            log.info("Nothing to change in '{}' task because operation is {}", super.getName(), operation);
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

        log.debug("Done '{}' task update state = {}", super.getName(), namespace);
    }

}
