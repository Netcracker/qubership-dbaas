package org.qubership.cloud.dbaas.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.cdi.SchedulerLock;
import net.javacrumbs.shedlock.core.LockAssert;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.qubership.cloud.context.propagation.core.ContextManager;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.dto.backupV2.*;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.backupV2.*;
import org.qubership.cloud.dbaas.entity.pg.backupV2.LogicalRestore;
import org.qubership.cloud.dbaas.entity.pg.backupV2.RestoreStatus;
import org.qubership.cloud.dbaas.entity.shared.AbstractDatabase;
import org.qubership.cloud.dbaas.entity.shared.AbstractDatabaseRegistry;
import org.qubership.cloud.dbaas.enums.Status;
import org.qubership.cloud.dbaas.exceptions.BackupExecutionException;
import org.qubership.cloud.dbaas.exceptions.BackupNotFoundException;
import org.qubership.cloud.dbaas.exceptions.DBBackupValidationException;
import org.qubership.cloud.dbaas.exceptions.NotFoundException;
import org.qubership.cloud.dbaas.mapper.BackupV2Mapper;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BackupRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.RestoreRepository;
import org.qubership.cloud.dbaas.utils.DbaasBackupUtils;
import org.qubership.cloud.framework.contexts.xrequestid.XRequestIdContextObject;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;
import static org.qubership.cloud.framework.contexts.xrequestid.XRequestIdContextObject.X_REQUEST_ID;

@Slf4j
@ApplicationScoped
@AllArgsConstructor
public class DbBackupV2Service {

    private final static RetryPolicy<Object> OPERATION_STATUS_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(2).withDelay(Duration.ofSeconds(1));
    protected static int TRACK_DELAY_MS = 3000;

    private final BackupRepository backupRepository;
    private final RestoreRepository restoreRepository;
    private final PhysicalDatabasesService physicalDatabasesService;
    private final DatabaseDbaasRepository databaseDbaasRepository;
    private final BackupV2Mapper mapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(20);
    private final BalancingRulesService balancingRulesService;

    public void backup(BackupRequest backupRequest) {
        String backupName = backupRequest.getBackupName();

        log.info("Start backup process with name {}", backupName);

        List<Database> databasesForBackup = getAllDbByFilter(backupRequest.getFilterCriteria());

        backupExistenceCheck(backupName);

        Backup backup = initializeFullBackupStructure(databasesForBackup, backupRequest);
        startBackup(backup);
        //TODO return backupName or throw exception
    }

    protected Backup initializeFullBackupStructure(List<Database> databasesForBackup, BackupRequest backupRequest) {
        Backup backup = new Backup(backupRequest.getBackupName(), backupRequest.getStorageName(), backupRequest.getBlobPath(), backupRequest.getExternalDatabaseStrategy(), null); //TODO fill backup class properly

        List<LogicalBackup> logicalBackups = databasesForBackup.stream()
                .collect(Collectors.groupingBy(AbstractDatabase::getAdapterId))
                .entrySet()
                .stream()
                .map(entry -> {
                    String adapterId = entry.getKey();
                    List<Database> databases = entry.getValue();
                    DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);

                    LogicalBackup lb = LogicalBackup.builder()
                            .backup(backup)
                            .adapterId(adapterId)
                            .type(adapter.type())
                            .status(new LogicalBackupStatus())
                            .backupDatabases(new ArrayList<>())
                            .build();

                    lb.getBackupDatabases().addAll(databases.stream()
                            .map(db -> BackupDatabase.builder()
                                    .logicalBackup(lb)
                                    .name(DbaasBackupUtils.getDatabaseName(db))
                                    .classifiers(db.getDatabaseRegistry().stream()
                                            .map(AbstractDatabaseRegistry::getClassifier)
                                            .toList())
                                    .users(getBackupDatabaseUsers(db.getConnectionProperties()))
                                    .settings(db.getSettings())
                                    .externallyManageable(db.isExternallyManageable())
                                    .build())
                            .toList());
                    return lb;
                })
                .toList();

        backup.setLogicalBackups(logicalBackups);
        backupRepository.save(backup);
        return backup;
    }

    private List<BackupDatabase.User> getBackupDatabaseUsers(List<Map<String, Object>> connectionProperties) {
        return connectionProperties.stream()
                .map(entry -> {
                    String username = (String) entry.get("username");
                    String role = (String) entry.get("role");
                    return new BackupDatabase.User(username, role);
                }).toList();
    }

    protected void startBackup(Backup backup) {
        Map<LogicalBackup, Future<String>> names = new HashMap<>();
        List<LogicalBackup> logicalBackups = backup.getLogicalBackups();

        var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();

        logicalBackups.forEach(logicalBackup -> {
            ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));
            Future<String> future = executor.submit(() -> {
                ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));
                return startLogicalBackup(logicalBackup);
            });
            names.put(logicalBackup, future);
        });

        names.forEach((logicalbackup, future) -> {
            try {
                String logicalBackupName = future.get();
                logicalbackup.setLogicalBackupName(logicalBackupName);
            } catch (InterruptedException | ExecutionException e) {
                LogicalBackupStatus logicalBackupStatus = LogicalBackupStatus.builder()
                        .status(Status.FAILED)
                        .errorMessage(e.getCause().getMessage())
                        .build();
                logicalbackup.setStatus(logicalBackupStatus);
            }
        });
        backupRepository.save(backup);
    }

    protected String startLogicalBackup(LogicalBackup logicalBackup) {
        List<String> dbNames = logicalBackup.getStatus().getDatabases().stream()
                .map(LogicalBackupStatus.Database::getDatabaseName)
                .toList();
        String adapterId = logicalBackup.getAdapterId();

        RetryPolicy<Object> retryPolicy = new RetryPolicy<>()
                .handle(WebApplicationException.class)
                .withMaxRetries(2)
                .withDelay(Duration.ofSeconds(3))
                .onFailedAttempt(e -> log.warn("Attempt failed: {}", e.getLastFailure().getMessage()))
                .onRetry(e -> log.info("Retrying backupV2..."))
                .onFailure(e -> log.error("Request limit exceeded for {}", logicalBackup));

        try {
            return Failsafe.with(retryPolicy).get(() -> {
                DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);
                String result = adapter.backupV2(dbNames);

                if (result == null) {
                    throw new BackupExecutionException(URI.create("e"), "Empty result from backup", new Throwable()); //TODO correct path
                }
                return result;
            });
        } catch (Exception e) {
            throw new RuntimeException("Failsafe execution failed", e);
        }
    }

    @Scheduled(every = "${backup.aggregation.interval}", concurrentExecution = SKIP)
    @SchedulerLock(name = "findAndStartAggregateBackup")
    protected void findAndStartAggregateBackup() {
        LockAssert.assertLocked();
        List<Backup> backupsToAggregate = backupRepository.findBackupsToAggregate();
        backupsToAggregate.forEach(this::trackAndAggregate);
    }

    protected void trackAndAggregate(Backup backup) {
        if (backup.getAttemptCount() > 20 && Status.IN_PROGRESS == backup.getStatus().getStatus()) {
            BackupStatus backupStatus = backup.getStatus();
            backupStatus.setStatus(Status.FAILED);
            backupStatus.setErrorMessage("The number of attempts exceeded 20");
        } else {
            List<LogicalBackup> notFinishedLogicalBackups = backup.getLogicalBackups();
            fetchAndUpdateStatuses(notFinishedLogicalBackups);
            updateAggregatedStatus(backup);
            backup.setAttemptCount(backup.getAttemptCount() + 1); // update track attempt
        }

        backupRepository.save(backup);
    }

    private void fetchAndUpdateStatuses(List<LogicalBackup> logicalBackupList) {
        Map<LogicalBackup, Future<LogicalBackupStatus>> statuses = new HashMap<>();
        var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();

        logicalBackupList
                .forEach(logicalBackup -> {
                    Future<LogicalBackupStatus> future = executor.submit(() -> {
                        ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));
                        return Failsafe.with(OPERATION_STATUS_RETRY_POLICY).get(() -> {
                                    DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalBackup.getAdapterId());
                                    return adapter.trackBackupV2(logicalBackup.getLogicalBackupName());
                                }
                        );
                    });
                    statuses.put(logicalBackup, future);
                });

        statuses.forEach((logicalBackup, future) -> {
            try {
                LogicalBackupStatus logicalBackupStatus = future.get();
                logicalBackup.setStatus(logicalBackupStatus);
            } catch (InterruptedException | ExecutionException e) {
                logicalBackup.getStatus().setStatus(Status.FAILED);
                logicalBackup.getStatus().setErrorMessage(e.getCause().getMessage());
            }
        });
    }

    protected void updateAggregatedStatus(Backup backup) {
        log.info("Start aggregating for backupName: {}", backup.getName());

        List<LogicalBackup> logicalBackuplist = backup.getLogicalBackups();
        List<LogicalBackupStatus> logicalBackupStatusList = logicalBackuplist.stream()
                .map(LogicalBackup::getStatus)
                .toList();

        log.info("List of logicalBackupStatusList: {}", logicalBackupStatusList);

        int totalDbCount = logicalBackuplist.stream().mapToInt(lb -> lb.getBackupDatabases().size()).sum();

        String aggregatedErrorMsg = logicalBackuplist.stream()
                .filter(lb -> lb.getStatus().getErrorMessage() != null && !lb.getStatus().getErrorMessage().isBlank())
                .map(lb -> {
                    String warn = String.format("LogicalBackup %s failed: %s", lb.getLogicalBackupName(), lb.getStatus().getErrorMessage());
                    log.warn(warn);
                    return warn;
                })
                .collect(Collectors.joining("; "));

        Set<Status> statusSet = new HashSet<>();
        long totalBytes = 0;
        int countCompletedDb = 0;

        for (LogicalBackupStatus logicalBackupStatus : logicalBackupStatusList) {
            Status currStatus = logicalBackupStatus.getStatus();
            statusSet.add(currStatus);

            totalBytes += logicalBackupStatus.getDatabases().stream()
                    .filter(database -> database.getSize() != null)
                    .mapToLong(LogicalBackupStatus.Database::getSize)
                    .sum();

            countCompletedDb += (int) logicalBackupStatus.getDatabases().stream()
                    .filter(db -> Status.COMPLETED.equals(db.getStatus()))
                    .count();
        }

        backup.setStatus(BackupStatus.builder()
                .status(aggregateStatus(statusSet))
                .size(totalBytes)
                .total(totalDbCount)
                .completed(countCompletedDb)
                .errorMessage(aggregatedErrorMsg)
                .build());
    }

    protected Status aggregateStatus(Set<Status> statusSet) {
        if (statusSet.contains(Status.NOT_STARTED) && statusSet.size() == 1)
            return Status.NOT_STARTED;

        if (statusSet.contains(Status.PENDING) && statusSet.size() == 1)
            return Status.PENDING;

        if (statusSet.contains(Status.NOT_STARTED) && statusSet.size() > 1)
            return Status.IN_PROGRESS;

        if (statusSet.contains(Status.IN_PROGRESS))
            return Status.IN_PROGRESS;

        if (statusSet.contains(Status.PENDING) && statusSet.size() > 1)
            return Status.IN_PROGRESS;

        if (statusSet.contains(Status.FAILED))
            return Status.FAILED;

        return Status.COMPLETED;
    }

    //TODO write test
    public BackupStatusResponse getCurrentStatus(String backupName) {
        BackupStatus backupStatus = backupRepository.findByIdOptional(backupName)
                .orElseThrow(() -> new BackupNotFoundException(backupName, Source.builder().build()))
                .getStatus();
        return mapper.toBackupStatusResponse(backupStatus);
    }

    protected List<Database> getAllDbByFilter(FilterCriteria filterCriteria) {
        String namespace = filterCriteria.getFilter().getFirst().getNamespace().getFirst();
        List<Database> databasesForBackup = databaseDbaasRepository.findAnyLogDbTypeByNamespace(namespace);

        if (databasesForBackup.isEmpty()) {
            log.warn("Databases that match filterCriteria not found");
            throw new BackupExecutionException(URI.create("path"),
                    "Databases that match filterCriteria not found",
                    null); //TODO fill correct path
        }

        return databasesForBackup;
    }


    public BackupResponse getBackupMetadata(String backupName) {
        Backup backup = backupRepository.findByIdOptional(backupName)
                .orElseThrow(() -> new BackupNotFoundException(backupName, Source.builder().build()));

        return mapper.toBackupResponse(backup);
    }

    public void uploadBackupMetadata(BackupResponse backupResponse) {
        Backup backup = mapper.toBackup(backupResponse);

        backupExistenceCheck(backup.getName());

        backup.getLogicalBackups().forEach(lb -> {
            if (lb.getAdapterId() != null)
                lb.setAdapterId(null);
        });
        backupRepository.save(backup);
    }

    public void restore(String backupName, RestoreRequest restoreRequest, boolean dryRun) {
        log.info("Start restore to backup {}", backupName);
        Backup backup = backupRepository.findByIdOptional(backupName)
                .orElseThrow(() -> new NotFoundException("Backup not found"));

        Restore restore = initializeFullRestoreStructure(backup, restoreRequest);
        startRestore(restore, dryRun);
    }


    protected Restore initializeFullRestoreStructure(Backup backup, RestoreRequest restoreRequest) {
        List<LogicalBackup> logicalBackups = backup.getLogicalBackups();
        Restore restore = new Restore();

        Map<String, String> namespacesMap = Optional.ofNullable(restoreRequest.getMapping())
                .map(Mapping::getNamespaces)
                .orElseGet(HashMap::new);


        Map<Map.Entry<String, String>, LogicalRestore> logicalRestoreMap = logicalBackups.stream()
                .map(lb -> {
                            var classifier = lb.getBackupDatabases().getFirst().getClassifiers().getFirst();
                            String oldNamespace = (String) classifier.get("namespace");
                            String type = lb.getType();
                            String targetNamespace = namespacesMap.getOrDefault(oldNamespace, oldNamespace);

                            String adapterId = balancingRulesService
                                    .applyNamespaceBalancingRule(targetNamespace, type)
                                    .getAdapter().getAdapterId();

                            LogicalRestore lr = new LogicalRestore();
                            lr.setAdapterId(adapterId);
                            lr.setType(type);
                            lr.setStatus(new LogicalRestoreStatus());
                            lr.setRestore(restore);
                            lr.setRestoreDatabases(new ArrayList<>());
                            return Map.entry(Map.entry(type, targetNamespace), lr);
                        }
                )
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        logicalBackups.forEach(lb -> lb.getBackupDatabases().forEach(bd -> {
                    String oldNamespace = (String) bd.getClassifiers().getFirst().get("namespace");
                    String targetNamespace = namespacesMap.getOrDefault(oldNamespace, oldNamespace);

                    LogicalRestore logicalRestore = logicalRestoreMap.get(Map.entry(lb.getType(), targetNamespace));
                    List<RestoreDatabase.User> users = bd.getUsers().stream().map(user -> new RestoreDatabase.User(user.getName(), user.getRole())).toList();
                    List<SortedMap<String, Object>> updatedClassifier = bd.getClassifiers().stream()
                            .map(classifier -> {
                                SortedMap<String, Object> copy = new TreeMap<>(classifier);
                                if (copy.containsKey("namespace")) {
                                    copy.put("namespace", targetNamespace);
                                }
                                return copy;
                            })
                            .toList();

                    RestoreDatabase rd = new RestoreDatabase(null, logicalRestore, bd, bd.getName(), updatedClassifier, users, bd.getResources());
                    logicalRestore.getRestoreDatabases().add(rd);
                })
        );
        restore.setName(backup.getName());
        restore.setLogicalRestores(new ArrayList<>(logicalRestoreMap.values()));
        restore.setBackup(backup);
        restore.setStorageName(restoreRequest.getStorageName());
        restore.setBlobPath(restoreRequest.getBlobPath());
        restore.setMapping("\"n\""); //TODO
        restore.setFilters("\"n\""); //TODO

        restoreRepository.save(restore);
        return restore;
    }

    protected void startRestore(Restore restore, boolean dryRun) {
        Map<LogicalRestore, Future<String>> names = new HashMap<>();
        List<LogicalRestore> logicalRestores = restore.getLogicalRestores();

        var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();

        logicalRestores.forEach(logicalRestore -> {
            Future<String> future = executor.submit(() -> {
                ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));
                return logicalRestore(logicalRestore, dryRun);
            });
            names.put(logicalRestore, future);
        });

        names.forEach((logicalRestore, future) -> {
            try {
                String logicalRestoreName = future.get();
                logicalRestore.setLogicalRestoreName(logicalRestoreName);
            } catch (InterruptedException | ExecutionException e) {
                LogicalRestoreStatus status = LogicalRestoreStatus.builder()
                        .status(Status.FAILED)
                        .errorMessage(e.getCause().getMessage())
                        .build();
                logicalRestore.setStatus(status);
            }
        });
        restoreRepository.save(restore);//TODO dryRun
    }


    private String logicalRestore(LogicalRestore logicalRestore, boolean dryRun) {
        String logicalBackupName = logicalRestore.getRestoreDatabases().getFirst().getBackupDatabase().getLogicalBackup().getLogicalBackupName();

        List<Map<String, String>> databases = logicalRestore.getRestoreDatabases().stream()
                .map(restoreDatabase -> {
                    String namespace = restoreDatabase.getClassifiers()
                            .stream()
                            .map(i -> (String) i.get("namespace"))
                            .findFirst()
                            .orElseThrow(() -> new NotFoundException("Namespace not found in " + restoreDatabase));
                    return Map.of(
                            "namespace", namespace,
                            "databaseName", restoreDatabase.getName()
                    );
                }).toList();

        Restore restore = logicalRestore.getRestore();
        RetryPolicy<Object> retryPolicy = new RetryPolicy<>()
                .handle(WebApplicationException.class)
                .withMaxRetries(2)
                .withDelay(Duration.ofSeconds(3))
                .onFailedAttempt(e -> log.warn("Attempt failed: {}", e.getLastFailure().getMessage()))
                .onRetry(e -> log.info("Retrying restoreV2..."))
                .onFailure(e -> log.error("Request limit exceeded for {}", logicalRestore));

        try {
            return Failsafe.with(retryPolicy).get(() -> {
                DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalRestore.getAdapterId());
                String result = adapter.restoreV2(logicalBackupName, dryRun, restore.getStorageName(), restore.getBlobPath(), databases);

                if (result == null) {
                    throw new BackupExecutionException(URI.create("e"), "Empty result from restore", new Throwable()); //TODO correct path
                }
                return result;
            });
        } catch (Exception e) {
            throw new RuntimeException("Failsafe execution failed", e);
        }
    }

    @Scheduled(every = "${restore.aggregation.interval}", concurrentExecution = SKIP)
    @SchedulerLock(name = "findAndStartAggregateRestore")
    protected void findAndStartAggregateRestore() {
        LockAssert.assertLocked();
        List<Restore> restoresToAggregate = restoreRepository.findRestoresToAggregate();
        restoresToAggregate.forEach(this::trackAndAggregateRestore);
    }

    protected void trackAndAggregateRestore(Restore restore) {
        if (restore.getAttemptCount() > 20 && Status.IN_PROGRESS == restore.getStatus().getStatus()) {
            RestoreStatus restoreStatus = restore.getStatus();
            restoreStatus.setStatus(Status.FAILED);
            restoreStatus.setErrorMessage("The number of attempts exceeded 20");
        } else {
            List<LogicalRestore> notFinishedLogicalRestores = restore.getLogicalRestores();
            fetchStatuses(notFinishedLogicalRestores);
            aggregateRestoreStatus(restore);
            restore.setAttemptCount(restore.getAttemptCount() + 1); // update track attempt
        }

        restoreRepository.save(restore);
    }


    private void fetchStatuses(List<LogicalRestore> logicalRestoreList) {
        Map<LogicalRestore, Future<LogicalRestoreStatus>> statuses = new HashMap<>();
        var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();

        logicalRestoreList
                .forEach(logicalRestore -> {
                    Future<LogicalRestoreStatus> future = executor.submit(() -> {
                        ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));
                        return Failsafe.with(OPERATION_STATUS_RETRY_POLICY).get(() -> {
                                    DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalRestore.getAdapterId());
                                    return adapter.trackRestoreV2(logicalRestore.getLogicalRestoreName());
                                }
                        );
                    });
                    statuses.put(logicalRestore, future);
                });

        statuses.forEach((logicalRestore, future) -> {
            try {
                LogicalRestoreStatus logicalRestoreStatus = future.get();
                logicalRestore.setStatus(logicalRestoreStatus);
            } catch (InterruptedException | ExecutionException e) {
                logicalRestore.getStatus().setStatus(Status.FAILED);
                logicalRestore.getStatus().setErrorMessage(e.getCause().getMessage());
            }
        });
    }

    private void aggregateRestoreStatus(Restore restore) {
        log.info("Start aggregating for restore: {}", restore.getName());

        List<LogicalRestore> logicalRestoreList = restore.getLogicalRestores();
        List<LogicalRestoreStatus> logicalRestoreStatuses = logicalRestoreList.stream()
                .map(LogicalRestore::getStatus)
                .toList();

        log.info("List of logicalRestoreStatusList: {}", logicalRestoreStatuses);

        int totalDbCount = logicalRestoreList.stream().mapToInt(lb -> lb.getRestoreDatabases().size()).sum();

        String aggregatedErrorMsg = logicalRestoreList.stream()
                .filter(logicalRestore ->
                        logicalRestore.getStatus().getErrorMessage() != null
                                && !logicalRestore.getStatus().getErrorMessage().isBlank())
                .map(logicalRestore -> {
                    String warn = String.format("LogicalRestore %s failed: %s",
                            logicalRestore.getLogicalRestoreName(),
                            logicalRestore.getStatus().getErrorMessage());
                    log.warn(warn);
                    return warn;
                })
                .collect(Collectors.joining("; "));

        Set<Status> statusSet = new HashSet<>();
        long totalBytes = 0;
        int countCompletedDb = 0;

        for (LogicalRestoreStatus logicalRestoreStatus : logicalRestoreStatuses) {
            Status currStatus = logicalRestoreStatus.getStatus();
            statusSet.add(currStatus);

            totalBytes += logicalRestoreStatus.getDatabases().stream()
                    .filter(database -> database.getSize() != null)
                    .mapToLong(LogicalRestoreStatus.Database::getSize)
                    .sum();

            countCompletedDb += (int) logicalRestoreStatus.getDatabases().stream()
                    .filter(db -> Status.COMPLETED.equals(db.getStatus()))
                    .count();
        }

        restore.setStatus(RestoreStatus.builder()
                .status(aggregateStatus(statusSet))
                .size(totalBytes)
                .total(totalDbCount)
                .completed(countCompletedDb)
                .errorMessage(aggregatedErrorMsg)
                .build());
    }

    private void backupExistenceCheck(String backupName) {
        if (backupRepository.findByIdOptional(backupName).isPresent()) {
            log.error("Backup with name {} already exists", backupName);
            throw new DBBackupValidationException(Source.builder().build(),
                    String.format("Backup with name %s already exists", backupName));
        }
    }
}
