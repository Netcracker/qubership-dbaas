package org.qubership.cloud.dbaas.service.processengine.tasks;

import org.qubership.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import org.qubership.cloud.dbaas.service.BlueGreenService;
import org.qubership.core.scheduler.po.DataContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;

import java.io.Serializable;
import java.time.Duration;
import java.util.Optional;
import java.util.SortedMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@ApplicationScoped
public class BackupDatabaseTask extends AbstractDbaaSTask implements Serializable {
    private static final RetryPolicy<Object> RETRY_POLICY = new RetryPolicy<>().withMaxRetries(-1)
            .withDelay(Duration.ofSeconds(1)).withMaxDuration(Duration.ofMinutes(20));

    @Inject
    LogicalDbDbaasRepository logicalDbDbaasRepository;
    @Inject
    BlueGreenService blueGreenService;

    public BackupDatabaseTask() {
        super(BackupDatabaseTask.class.getName());
    }

    @Override
    protected void executeTask(DataContext context) {
        CloneDatabaseProcessObject cloneDatabaseProcessObject = (CloneDatabaseProcessObject) context.get("processObject");

        SortedMap<String, Object> sourceClassifier = cloneDatabaseProcessObject.getSourceClassifier();
        AtomicReference<DatabaseRegistry> sourceRegistry = new AtomicReference<>();
        updateState(context, "Waiting for source DB with classifier " + sourceClassifier, true);
        Failsafe.with(RETRY_POLICY).run(() -> {
            Optional<DatabaseRegistry> databaseRegistry = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()
                    .getDatabaseByClassifierAndType(sourceClassifier, cloneDatabaseProcessObject.getConfig().getType());
            if (databaseRegistry.isEmpty()) {
                throw new RuntimeException("Can't find source DB with classifier " + sourceClassifier);
            }
            sourceRegistry.set(databaseRegistry.get());
        });

        updateState(context, "Backup source DB with classifier " + sourceClassifier, false);
        UUID sourceDatabaseId = sourceRegistry.get().getId();
        String sourceNamespace = cloneDatabaseProcessObject.getSourceNamespace();
        UUID backupId = cloneDatabaseProcessObject.getBackupId();
        blueGreenService.createDatabaseBackup(backupId, sourceNamespace, sourceDatabaseId);
        log.debug("Done '{}' task with databaseId = {}", super.getName(), sourceDatabaseId);
    }
}
