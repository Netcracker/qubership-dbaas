package com.netcracker.cloud.dbaas.service;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.netcracker.cloud.dbaas.dto.backup.Status;
import com.netcracker.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import com.netcracker.cloud.dbaas.entity.pg.backup.RestoreResult;
import com.netcracker.cloud.dbaas.entity.pg.backup.TrackedAction;
import com.netcracker.cloud.dbaas.exceptions.InteruptedPollingException;
import com.netcracker.cloud.dbaas.repositories.dbaas.ActionTrackDbaasRepository;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.ext.web.codec.BodyCodec;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Date;

import static jakarta.transaction.Transactional.TxType.NOT_SUPPORTED;

@Slf4j
@ApplicationScoped
public class AdapterActionTrackerClient {

    private ActionTrackDbaasRepository actionTrackDbaasRepository;
    private WebClient webClient;
    private Integer actionTrackPollingLimitationTimes;
    private Boolean saveTrackingActions;
    private Integer actionTrackPollingPeriodBaseMs;
    private Long actionTrackHistoryLimitMs;

    public AdapterActionTrackerClient(ActionTrackDbaasRepository actionTrackDbaasRepository,
                                      Vertx vertx,
                                      @ConfigProperty(name = "adapter.action.track.polling.limitation.times", defaultValue = "25") Integer actionTrackPollingLimitationTimes,
                                      @ConfigProperty(name = "adapter.action.track.save.enabled", defaultValue = "true") Boolean saveTrackingActions,
                                      @ConfigProperty(name = "adapter.action.track.polling.start.period-milliseconds", defaultValue = "1000") Integer actionTrackPollingPeriodBaseMs,
                                      @ConfigProperty(name = "adapter.action.track.history.limit-milliseconds", defaultValue = "604800000") /* by default would clean everything older than 1 week */ Long actionTrackHistoryLimitMs) {
        this.actionTrackDbaasRepository = actionTrackDbaasRepository;
        this.actionTrackPollingLimitationTimes = actionTrackPollingLimitationTimes;
        this.saveTrackingActions = saveTrackingActions;
        this.actionTrackPollingPeriodBaseMs = actionTrackPollingPeriodBaseMs;
        this.actionTrackHistoryLimitMs = actionTrackHistoryLimitMs;
        this.webClient = WebClient.create(vertx);
    }

    @Transactional(NOT_SUPPORTED)
    public TrackedAction waitForSuccess(@Nonnull final TrackedAction startTrack,
                                        final AbstractDbaasAdapterRESTClient adapter) throws InteruptedPollingException {
        try {
            startTrack.setAdapterId(adapter.identifier());
            StructuredLog.info(log, "Start waiting for :", "arg0", startTrack.getAction(), "startTrack", startTrack);
            TrackedAction activeTrack = startTrack;
            startTrack.setWhenStarted(new Date());
            updateRepository(startTrack);
            String action = startTrack.getAction().toString().toLowerCase();
            int pollWith = actionTrackPollingPeriodBaseMs;
            int pollNumber = 0;
            while (activeTrack.getStatus() != Status.SUCCESS && pollNumber < actionTrackPollingLimitationTimes) {
                StructuredLog.info(log, "Poll tracked action : of with period", "action", action, "arg1", startTrack.getTrackLog(), "pollNumber", pollNumber, "actionTrackPollingLimitationTimes", actionTrackPollingLimitationTimes, "pollWith", pollWith);
                pollNumber++;
                Thread.sleep(pollWith);
                pollWith += 1000; // wait longer with every retry
                activeTrack = callTrack(startTrack, adapter, action);
                activeTrack.setWhenStarted(startTrack.getWhenStarted());
                activeTrack.setWhenChecked(new Date());
                activeTrack.setAdapterId(adapter.identifier());
                updateRepository(activeTrack);
                if (Status.FAIL == activeTrack.getStatus()) {
                    StructuredLog.error(log, "Action just failed:", "arg0", activeTrack.getAction(), "activeTrack", activeTrack);
                    return null;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
            }
            if (Status.PROCEEDING == activeTrack.getStatus()) {
                StructuredLog.error(log, "Action has been to long to wait for", "actionTrackPollingLimitationTimes", actionTrackPollingLimitationTimes);
                if (pollNumber >= actionTrackPollingLimitationTimes) {
                    StructuredLog.error(log, "Action has been to long to wait for, limitation has been reached", "action", action, "actionTrackPollingLimitationTimes", actionTrackPollingLimitationTimes);
                }
                return null;
            }
            cleanActions();
            return activeTrack;
        } catch (InterruptedException e) {
            StructuredLog.error(log, "Thread for action tracking interrupted", "arg0", startTrack.getAction(), "startTrack", startTrack);
            throw new InteruptedPollingException(e.getMessage());
        }
    }

    private String path(String address, String path) {
        boolean aends = address.endsWith("/");
        boolean pstarts = path.startsWith("/");
        if (aends && pstarts) return address + path.substring(1);
        if (!aends && !pstarts) return address + "/" + path;
        return address + path;
    }

    private TrackedAction callTrack(@Nonnull TrackedAction startTrack, AbstractDbaasAdapterRESTClient adapter, String action) {
        if (startTrack.useTrackPath()) {
            return webClient.get(path(adapter.adapterAddress(), startTrack.getTrackPath()))
                    .as(BodyCodec.json(TrackedAction.class))
                    .send()
                    .onItem().transform(trackedActionHttpResponse -> trackedActionHttpResponse.body()).await().indefinitely();
        }
        return adapter.trackBackup(action, startTrack.getTrackId());
    }

    private void updateRepository(TrackedAction actionTrack) {
        if (saveTrackingActions) {
            try {
                actionTrackDbaasRepository.save(actionTrack);
            } catch (Exception ex) {
                StructuredLog.warn(log, "Failed to update tracking action , skip save", "actionTrack", actionTrack);
            }
        }
    }

    private void cleanActions() {
        if (saveTrackingActions) {
            try {
                actionTrackDbaasRepository.deleteAll(actionTrackDbaasRepository.findByCreatedTimeMsLessThan(new Date().getTime() - actionTrackHistoryLimitMs));
            } catch (OptimisticLockException ex) {
                log.warn("OptimisticLockException lock happened. Try to perform clean actions again");
                cleanActions();
            } catch (Exception ex) {
                log.warn("Failed to clean tracking actions, skip save", ex);
            }
        }
    }


    public DatabasesBackup waitForBackup(TrackedAction adapterBackupAction, AbstractDbaasAdapterRESTClient adapter) throws InteruptedPollingException {
        try {
            TrackedAction successful = waitForSuccess(adapterBackupAction, adapter);
            try {
                if (successful != null) {
                    DatabasesBackup backup = new DatabasesBackup(successful);
                    backup.setStatus(successful.getStatus());
                    return backup;
                } else {
                    StructuredLog.error(log, "Failed to get successful backup track from adapter , assume backup failed", "backup", adapterBackupAction.getTrackLog(), "adapter", adapter.identifier());
                    DatabasesBackup backup = new DatabasesBackup();
                    backup.setStatus(Status.FAIL);
                    return backup;
                }
            } catch (Exception e) {
                StructuredLog.error(log, "Failed to construct databases backup from", "successful", successful);
                DatabasesBackup backup = new DatabasesBackup();
                backup.setStatus(Status.FAIL);
                return backup;
            }
        } catch (InteruptedPollingException e) {
            throw e;
        } catch (Exception e) {
            StructuredLog.error(log, "Failed to get successful backup track from adapter on action", "backup", adapterBackupAction.getTrackLog(), "adapter", adapter.identifier(), "adapterBackupAction", adapterBackupAction);
            DatabasesBackup backup = new DatabasesBackup();
            backup.setStatus(Status.FAIL);
            return backup;
        }
    }

    public DatabasesBackup validateBackup(TrackedAction adapterBackupAction, AbstractDbaasAdapterRESTClient adapter) {
        String action = adapterBackupAction.getAction().toString().toLowerCase();
        TrackedAction tracked = callTrack(adapterBackupAction, adapter, action);
        try {
            if (tracked != null) {
                DatabasesBackup backup = new DatabasesBackup(tracked);
                backup.setStatus(tracked.getStatus());
                return backup;
            } else {
                StructuredLog.error(log, "Failed to get successful backup track from adapter , assume backup failed", "backup", adapterBackupAction.getTrackLog(), "adapter", adapter.identifier());
                DatabasesBackup backup = new DatabasesBackup();
                backup.setStatus(Status.FAIL);
                return backup;
            }
        } catch (Exception e) {
            StructuredLog.error(log, "Failed to construct databases backup from", "tracked", tracked);
            DatabasesBackup backup = new DatabasesBackup();
            backup.setStatus(Status.FAIL);
            return backup;
        }
    }

    public RestoreResult waitForRestore(DatabasesBackup backup, TrackedAction adapterBackupAction, AbstractDbaasAdapterRESTClient adapter) {
        try {
            TrackedAction successful = waitForSuccess(adapterBackupAction, adapter);
            try {
                RestoreResult result = new RestoreResult(adapter.identifier());
                if (!MapUtils.isEmpty(adapterBackupAction.getChangedNameDb())) {
                    result.setChangedNameDb(adapterBackupAction.getChangedNameDb());
                }
                result.setDatabasesBackup(backup);
                if (successful != null) {

                    result.setStatus(Status.SUCCESS);
                    return result;
                } else {
                    StructuredLog.error(log, "Failed to get successful restore track from adapter", "adapter", adapter.identifier());
                    result.setStatus(Status.FAIL);
                    return result;
                }
            } catch (Exception e) {
                StructuredLog.error(log, "Failed to construct restore result from", "successful", successful);
                RestoreResult result = new RestoreResult(adapter.identifier());
                result.setStatus(Status.FAIL);
                return result;
            }
        } catch (InteruptedPollingException e) {
            throw e;
        } catch (Exception e) {
            StructuredLog.error(log, "Failed to get successful restore track from adapter on action", "adapter", adapter.identifier(), "adapterBackupAction", adapterBackupAction);
            RestoreResult result = new RestoreResult(adapter.identifier());
            result.setStatus(Status.FAIL);
            return result;
        }
    }

}
