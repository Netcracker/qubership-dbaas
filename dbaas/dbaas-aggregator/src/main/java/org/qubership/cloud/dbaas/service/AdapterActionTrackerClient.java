package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.backup.Status;
import org.qubership.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.RestoreResult;
import org.qubership.cloud.dbaas.entity.pg.backup.TrackedAction;
import org.qubership.cloud.dbaas.exceptions.InteruptedPollingException;
import org.qubership.cloud.dbaas.repositories.dbaas.ActionTrackDbaasRepository;
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
            log.info("Start waiting for {}: {}", startTrack.getAction(), startTrack);
            TrackedAction activeTrack = startTrack;
            startTrack.setWhenStarted(new Date());
            updateRepository(startTrack);
            String action = startTrack.getAction().toString().toLowerCase();
            int pollWith = actionTrackPollingPeriodBaseMs;
            int pollNumber = 0;
            while (activeTrack.getStatus() != Status.SUCCESS && pollNumber < actionTrackPollingLimitationTimes) {
                log.info("Poll tracked action {} {}: {} of {} with period {}", action, startTrack.getTrackLog(),
                        pollNumber, actionTrackPollingLimitationTimes, pollWith);
                pollNumber++;
                Thread.sleep(pollWith);
                pollWith += 1000; // wait longer with every retry
                activeTrack = callTrack(startTrack, adapter, action);
                activeTrack.setWhenStarted(startTrack.getWhenStarted());
                activeTrack.setWhenChecked(new Date());
                activeTrack.setAdapterId(adapter.identifier());
                updateRepository(activeTrack);
                if (Status.FAIL == activeTrack.getStatus()) {
                    log.error("Action {} just failed: {}", activeTrack.getAction(), activeTrack);
                    return null;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
            }
            if (Status.PROCEEDING == activeTrack.getStatus()) {
                log.error("Action {} has been to long to wait for", actionTrackPollingLimitationTimes);
                if (pollNumber >= actionTrackPollingLimitationTimes) {
                    log.error("Action {} has been to long to wait for, limitation {} has been reached", action, actionTrackPollingLimitationTimes);
                }
                return null;
            }
            cleanActions();
            return activeTrack;
        } catch (InterruptedException e) {
            log.error("Thread for action {} tracking interrupted {}", startTrack.getAction(), startTrack, e);
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
                log.warn("Failed to update tracking action {}, skip save", actionTrack, ex);
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
                log.warn("Failed to clean tracking actions {}, skip save", ex);
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
                    log.error("Failed to get successful backup track {} from adapter {}, assume backup failed",
                            adapterBackupAction.getTrackLog(), adapter.identifier());
                    DatabasesBackup backup = new DatabasesBackup();
                    backup.setStatus(Status.FAIL);
                    return backup;
                }
            } catch (Exception e) {
                log.error("Failed to construct databases backup from {}", successful);
                DatabasesBackup backup = new DatabasesBackup();
                backup.setStatus(Status.FAIL);
                return backup;
            }
        } catch (InteruptedPollingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get successful backup track {} from adapter {} on action {}",
                    adapterBackupAction.getTrackLog(), adapter.identifier(), adapterBackupAction, e);
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
                log.error("Failed to get successful backup track {} from adapter {}, assume backup failed",
                        adapterBackupAction.getTrackLog(), adapter.identifier());
                DatabasesBackup backup = new DatabasesBackup();
                backup.setStatus(Status.FAIL);
                return backup;
            }
        } catch (Exception e) {
            log.error("Failed to construct databases backup from {}", tracked);
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
                    log.error("Failed to get successful restore track from adapter {}", adapter.identifier());
                    result.setStatus(Status.FAIL);
                    return result;
                }
            } catch (Exception e) {
                log.error("Failed to construct restore result from {}", successful);
                RestoreResult result = new RestoreResult(adapter.identifier());
                result.setStatus(Status.FAIL);
                return result;
            }
        } catch (InteruptedPollingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get successful restore track from adapter {} on action {}",
                    adapter.identifier(),
                    adapterBackupAction, e);
            RestoreResult result = new RestoreResult(adapter.identifier());
            result.setStatus(Status.FAIL);
            return result;
        }
    }

}
