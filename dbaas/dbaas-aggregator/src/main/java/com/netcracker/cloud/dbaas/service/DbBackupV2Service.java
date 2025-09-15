package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.dbaas.dto.Source;
import com.netcracker.cloud.dbaas.dto.backupV2.*;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.LogicalBackupAdapterResponse;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.LogicalRestoreAdapterResponse;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.*;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.LogicalRestore;
import com.netcracker.cloud.dbaas.entity.shared.AbstractDatabase;
import com.netcracker.cloud.dbaas.entity.shared.AbstractDatabaseRegistry;
import com.netcracker.cloud.dbaas.enums.ExternalDatabaseStrategy;
import com.netcracker.cloud.dbaas.enums.Status;
import com.netcracker.cloud.dbaas.exceptions.*;
import com.netcracker.cloud.dbaas.mapper.BackupV2Mapper;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.BackupRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.RestoreRepository;
import com.netcracker.cloud.dbaas.utils.DbaasBackupUtils;
import com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextObject;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.cdi.SchedulerLock;
import net.javacrumbs.shedlock.core.LockAssert;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextObject.X_REQUEST_ID;
import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

@Slf4j
@ApplicationScoped
@AllArgsConstructor
public class DbBackupV2Service {

    private final static RetryPolicy<Object> OPERATION_STATUS_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(2).withDelay(Duration.ofSeconds(1));
    private static final Duration RETRY_DELAY = Duration.ofSeconds(3);
    private static final int MAX_RETRIES = 2;
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
        backupExistenceCheck(backupName);

        log.info("Start backup process with name {}", backupName);
        List<Database> databasesForBackup = getAllDbByFilter(backupRequest.getFilterCriteria());

        List<Database> filteredDb = validateAndFilterDatabasesForBackup(
                databasesForBackup,
                backupRequest.getIgnoreNotBackupableDatabases(),
                backupRequest.getExternalDatabaseStrategy()
        );

        Backup backup = initializeFullBackupStructure(filteredDb, backupRequest);
        startBackup(backup);
        updateAggregatedStatus(backup);
        //TODO return backupName or throw exception
    }

    protected Backup initializeFullBackupStructure(List<Database> databasesForBackup, BackupRequest backupRequest) {
        //Create base backup
        Backup backup = new Backup(
                backupRequest.getBackupName(),
                backupRequest.getStorageName(),
                backupRequest.getBlobPath(),
                backupRequest.getExternalDatabaseStrategy(),
                null); //TODO fill backup class properly

        //Partition databases into externally manageable and non-externally manageable
        Map<Boolean, List<Database>> partitioned = databasesForBackup.stream()
                .collect(Collectors.partitioningBy(AbstractDatabase::isExternallyManageable));

        //Handle non-externally managed databases
        List<LogicalBackup> logicalBackups = partitioned
                .getOrDefault(false, List.of())
                .stream()
                .collect(Collectors.groupingBy(AbstractDatabase::getAdapterId))
                .entrySet()
                .stream()
                .map(entry -> createLogicalBackup(entry.getKey(), entry.getValue(), backup))
                .toList();

        //Handle externally managed databases
        List<BackupExternalDatabase> externalDatabases = partitioned
                .getOrDefault(true, List.of())
                .stream()
                .map(database -> BackupExternalDatabase.builder()
                        .backup(backup)
                        .name(database.getName())
                        .type(database.getDatabaseRegistry().getFirst().getType())
                        .classifiers(database.getDatabaseRegistry().stream()
                                .map(AbstractDatabaseRegistry::getClassifier)
                                .toList())
                        .build()
                ).toList();

        //Persist and return
        backup.setExternalDatabases(externalDatabases);
        backup.setLogicalBackups(logicalBackups);
        backupRepository.save(backup);
        return backup;
    }

    private LogicalBackup createLogicalBackup(String adapterId, List<Database> databases, Backup backup) {
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);

        LogicalBackup logicalBackup = LogicalBackup.builder()
                .backup(backup)
                .adapterId(adapterId)
                .type(adapter.type())
                .backupDatabases(new ArrayList<>())
                .build();

        logicalBackup.getBackupDatabases().addAll(databases.stream()
                .map(db -> BackupDatabase.builder()
                        .logicalBackup(logicalBackup)
                        .name(DbaasBackupUtils.getDatabaseName(db))
                        .classifiers(db.getDatabaseRegistry().stream()
                                .map(AbstractDatabaseRegistry::getClassifier).toList())
                        .users(getBackupDatabaseUsers(db.getConnectionProperties()))
                        .settings(db.getSettings())
                        .build())
                .toList());
        return logicalBackup;
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
        var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();

        List<CompletableFuture<Void>> futures = backup.getLogicalBackups().stream()
                .map(logicalBackup ->
                        CompletableFuture.supplyAsync(() -> {
                                    ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));
                                    return startLogicalBackup(logicalBackup);
                                }, executor)
                                .thenAccept(response -> {
                                    refreshLogicalBackupState(logicalBackup, response);
                                })
                                .exceptionally(throwable -> {
                                    logicalBackup.setStatus(Status.FAILED);
                                    logicalBackup.setErrorMessage(throwable.getCause() != null ?
                                            throwable.getCause().getMessage() : throwable.getMessage());
                                    return null;
                                })
                )
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        backupRepository.save(backup);
    }

    protected void refreshLogicalBackupState(LogicalBackup logicalBackup, LogicalBackupAdapterResponse logicalBackupAdapterResponse) {
        logicalBackup.setLogicalBackupName(logicalBackupAdapterResponse.getLogicalBackupName());
        logicalBackup.setStatus(logicalBackupAdapterResponse.getStatus());
        logicalBackup.setErrorMessage(logicalBackupAdapterResponse.getErrorMessage());
        logicalBackup.setCreationTime(logicalBackupAdapterResponse.getCreationTime());
        logicalBackup.setCompletionTime(logicalBackupAdapterResponse.getCompletionTime());

        Map<String, BackupDatabase> backupDbMap = logicalBackup.getBackupDatabases().stream()
                .collect(Collectors.toMap(BackupDatabase::getName, Function.identity()));

        logicalBackupAdapterResponse.getDatabases().forEach(db -> {
            BackupDatabase backupDb = backupDbMap.get(db.getDatabaseName());
            if (backupDb != null) {
                backupDb.setStatus(db.getStatus());
                backupDb.setSize(db.getSize());
                backupDb.setDuration(db.getDuration());
                backupDb.setCreationTime(db.getCreationTime());
                backupDb.setPath(db.getPath());
                backupDb.setErrorMessage(db.getErrorMessage());
            }
        });

    }

    protected LogicalBackupAdapterResponse startLogicalBackup(LogicalBackup logicalBackup) {
        Backup backup = logicalBackup.getBackup();
        String storageName = backup.getStorageName();
        String blobPath = backup.getBlobPath();

        List<Map<String, String>> dbNames = logicalBackup.getBackupDatabases().stream()
                .map(backupDatabase -> Map.of("databaseName", backupDatabase.getName()))
                .toList();
        String adapterId = logicalBackup.getAdapterId();

        RetryPolicy<Object> retryPolicy = new RetryPolicy<>()
                .handle(WebApplicationException.class)
                .withMaxRetries(MAX_RETRIES)
                .withDelay(RETRY_DELAY)
                .onFailedAttempt(e -> log.warn("Attempt failed: {}", e.getLastFailure().getMessage()))
                .onRetry(e -> log.info("Retrying backupV2..."))
                .onFailure(e -> log.error("Request limit exceeded for {}", logicalBackup));

        try {
            return Failsafe.with(retryPolicy).get(() -> {
                DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);
                LogicalBackupAdapterResponse result = adapter.backupV2(storageName, blobPath, dbNames);

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
        //TODO propagate correct business id
        LockAssert.assertLocked();
        List<Backup> backupsToAggregate = backupRepository.findBackupsToAggregate();
        backupsToAggregate.forEach(this::trackAndAggregate);
    }

    protected void trackAndAggregate(Backup backup) {
        if (backup.getAttemptCount() > 20 && Status.IN_PROGRESS == backup.getStatus()) {
            backup.setStatus(Status.FAILED);
            backup.setErrorMessage("The number of attempts exceeded 20");
        } else {
            List<LogicalBackup> notFinishedLogicalBackups = backup.getLogicalBackups();
            fetchAndUpdateStatuses(notFinishedLogicalBackups);
            updateAggregatedStatus(backup);
            backup.setAttemptCount(backup.getAttemptCount() + 1); // update track attempt
        }

        backupRepository.save(backup);
    }

    private void fetchAndUpdateStatuses(List<LogicalBackup> logicalBackupList) {
        var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();

        List<CompletableFuture<Void>> futures = logicalBackupList.stream()
                .map(logicalBackup ->
                        CompletableFuture.supplyAsync(() -> {
                                    ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));
                                    return Failsafe.with(OPERATION_STATUS_RETRY_POLICY).get(() -> {
                                        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalBackup.getAdapterId());
                                        return adapter.trackBackupV2(logicalBackup.getLogicalBackupName());
                                    });
                                }, executor)
                                .thenAccept(response -> {
                                    refreshLogicalBackupState(logicalBackup, response);
                                })
                                .exceptionally(throwable -> {
                                    logicalBackup.setStatus(Status.FAILED);
                                    logicalBackup.setErrorMessage(throwable.getCause() != null ?
                                            throwable.getCause().getMessage() : throwable.getMessage());
                                    return null;
                                })
                )
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    protected void updateAggregatedStatus(Backup backup) {
        log.info("Start aggregating for backupName: {}", backup.getName());

        List<LogicalBackup> logicalBackuplist = backup.getLogicalBackups();
        List<Status> logicalBackupStatusList = logicalBackuplist.stream()
                .map(LogicalBackup::getStatus)
                .toList();

        log.info("List of logicalBackupStatusList: {}", logicalBackupStatusList);

        int totalDbCount = logicalBackuplist.stream().mapToInt(lb -> lb.getBackupDatabases().size()).sum();

        String aggregatedErrorMsg = logicalBackuplist.stream()
                .filter(lb -> lb.getErrorMessage() != null && !lb.getErrorMessage().isBlank())
                .map(lb -> {
                    String warn = String.format("LogicalBackup %s failed: %s", lb.getLogicalBackupName(), lb.getErrorMessage());
                    log.warn(warn);
                    return warn;
                })
                .collect(Collectors.joining("; "));

        long totalBytes = logicalBackuplist.stream()
                .flatMap(lb -> lb.getBackupDatabases().stream())
                .mapToLong(BackupDatabase::getSize)
                .sum();

        int countCompletedDb = (int) logicalBackuplist.stream()
                .flatMap(lb -> lb.getBackupDatabases().stream())
                .filter(db -> Status.COMPLETED.equals(db.getStatus()))
                .count();


        Set<Status> statusSet = new HashSet<>(logicalBackupStatusList);

        backup.setStatus(aggregateStatus(statusSet));
        backup.setSize(totalBytes);
        backup.setTotal(totalDbCount);
        backup.setCompleted(countCompletedDb);
        backup.setErrorMessage(aggregatedErrorMsg);
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
        Backup backup = backupRepository.findByIdOptional(backupName)
                .orElseThrow(() -> new BackupNotFoundException(backupName, Source.builder().build()));

        return mapper.toBackupStatusResponse(backup);
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
        aggregateRestoreStatus(restore);
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

                    RestoreDatabase restoreDatabase = RestoreDatabase.builder()
                            .logicalRestore(logicalRestore)
                            .backupDatabase(bd)
                            .name(bd.getName())
                            .classifiers(updatedClassifier)
                            .users(users)
                            .resources(bd.getResources())
                            .build();

                    logicalRestore.getRestoreDatabases().add(restoreDatabase);
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
        Map<LogicalRestore, Future<LogicalRestoreAdapterResponse>> names = new HashMap<>();
        List<LogicalRestore> logicalRestores = restore.getLogicalRestores();

        var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();

        logicalRestores.forEach(logicalRestore -> {
            Future<LogicalRestoreAdapterResponse> future = executor.submit(() -> {
                ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));
                return logicalRestore(logicalRestore, dryRun);
            });
            names.put(logicalRestore, future);
        });

        names.forEach((logicalRestore, future) -> {
            try {
                LogicalRestoreAdapterResponse response = future.get();//TODO future.get() -> CompletableFuture
                refreshLogicalRestoreState(logicalRestore, response);
            } catch (InterruptedException | ExecutionException e) {
                logicalRestore.setStatus(Status.FAILED);
                logicalRestore.setErrorMessage(e.getCause().getMessage());
            }
        });
        restoreRepository.save(restore);//TODO dryRun
    }

    private void refreshLogicalRestoreState(LogicalRestore logicalRestore, LogicalRestoreAdapterResponse response) {
        logicalRestore.setLogicalRestoreName(response.getLogicalRestoreName());
        logicalRestore.setStatus(response.getStatus());
        logicalRestore.setErrorMessage(response.getErrorMessage());
        logicalRestore.setCreationTime(response.getCreationTime());
        logicalRestore.setCompletionTime(response.getCompletionTime());

        Map<String, RestoreDatabase> restoreDbMap = logicalRestore.getRestoreDatabases().stream()
                .collect(Collectors.toMap(RestoreDatabase::getName, Function.identity()));

        response.getDatabases().forEach(db -> {
            RestoreDatabase restoreDatabase = restoreDbMap.get(db.getPreviousDatabaseName());
            if (restoreDatabase != null) {
                restoreDatabase.setStatus(db.getStatus());
                restoreDatabase.setDuration(db.getDuration());
                restoreDatabase.setPath(db.getPath());
                restoreDatabase.setErrorMessage(db.getErrorMessage());
                restoreDatabase.setCreationTime(db.getCreationTime());

                if (db.getDatabaseName() != null)
                    restoreDatabase.setName(db.getDatabaseName());
            }
        });
    }


    private LogicalRestoreAdapterResponse logicalRestore(LogicalRestore logicalRestore, boolean dryRun) {
        String logicalBackupName = logicalRestore.getRestoreDatabases().getFirst().getBackupDatabase().getLogicalBackup().getLogicalBackupName();

        List<Map<String, String>> databases = logicalRestore.getRestoreDatabases().stream()
                .map(restoreDatabase -> {
                    String namespace = restoreDatabase.getClassifiers()
                            .stream()
                            .map(i -> (String) i.get("namespace"))
                            .findFirst()//todo logical error
                            .orElse("");

                    String microserviceName = restoreDatabase.getClassifiers()
                            .stream()
                            .map(i -> (String) i.get("microserviceName"))
                            .findFirst()//todo logical error
                            .orElse("");

                    return Map.of(
                            "microserviceName", microserviceName,
                            "databaseName", restoreDatabase.getName(),
                            "namespace", namespace
                    );
                }).toList();

        Restore restore = logicalRestore.getRestore();
        RetryPolicy<Object> retryPolicy = new RetryPolicy<>()
                .handle(WebApplicationException.class)
                .withMaxRetries(MAX_RETRIES)
                .withDelay(RETRY_DELAY)
                .onFailedAttempt(e -> log.warn("Attempt failed: {}", e.getLastFailure().getMessage()))
                .onRetry(e -> log.info("Retrying restoreV2..."))
                .onFailure(e -> log.error("Request limit exceeded for {}", logicalRestore));

        try {
            return Failsafe.with(retryPolicy).get(() -> {
                DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalRestore.getAdapterId());
                LogicalRestoreAdapterResponse result = adapter.restoreV2(logicalBackupName, dryRun, restore.getStorageName(), restore.getBlobPath(), databases);

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

    //TODO after aggregate status completed need to start registry database (Database.class)
    protected void trackAndAggregateRestore(Restore restore) {
        if (restore.getAttemptCount() > 20 && Status.IN_PROGRESS == restore.getStatus()) {
            restore.setStatus(Status.FAILED);
            restore.setErrorMessage("The number of attempts exceeded 20");
        } else {
            List<LogicalRestore> notFinishedLogicalRestores = restore.getLogicalRestores();
            fetchStatuses(notFinishedLogicalRestores);
            aggregateRestoreStatus(restore);
            restore.setAttemptCount(restore.getAttemptCount() + 1); // update track attempt
        }

        restoreRepository.save(restore);
    }


    private void fetchStatuses(List<LogicalRestore> logicalRestoreList) {
        Map<LogicalRestore, Future<LogicalRestoreAdapterResponse>> statuses = new HashMap<>();
        var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();

        logicalRestoreList
                .forEach(logicalRestore -> {
                    Future<LogicalRestoreAdapterResponse> future = executor.submit(() -> {
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
                LogicalRestoreAdapterResponse logicalRestoreStatus = future.get(); //TODO future.get() -> completableFuture
                refreshLogicalRestoreState(logicalRestore, logicalRestoreStatus);
            } catch (InterruptedException | ExecutionException e) {
                logicalRestore.setStatus(Status.FAILED);
                logicalRestore.setErrorMessage(e.getCause().getMessage());
            }
        });
    }

    private void aggregateRestoreStatus(Restore restore) {
        log.info("Start aggregating for restore: {}", restore.getName());

        List<LogicalRestore> logicalRestoreList = restore.getLogicalRestores();
        List<Status> logicalRestoreStatuses = logicalRestoreList.stream()
                .map(LogicalRestore::getStatus)
                .toList();
        Set<Status> statusSet = new HashSet<>(logicalRestoreStatuses);
        log.info("List of logicalRestoreStatusList: {}", logicalRestoreStatuses);

        int totalDbCount = logicalRestoreList.stream().mapToInt(lb -> lb.getRestoreDatabases().size()).sum();

        String aggregatedErrorMsg = logicalRestoreList.stream()
                .filter(logicalRestore ->
                        logicalRestore.getErrorMessage() != null
                                && !logicalRestore.getErrorMessage().isBlank())
                .map(logicalRestore -> {
                    String warn = String.format("LogicalRestore %s failed: %s",
                            logicalRestore.getLogicalRestoreName(),
                            logicalRestore.getErrorMessage());
                    log.warn(warn);
                    return warn;
                })
                .collect(Collectors.joining("; "));


        int countCompletedDb = (int) logicalRestoreList.stream()
                .flatMap(logicalRestore -> logicalRestore.getRestoreDatabases().stream())
                .filter(db -> Status.COMPLETED.equals(db.getStatus()))
                .count();

        restore.setStatus(aggregateStatus(statusSet));
        restore.setTotal(totalDbCount);
        restore.setCompleted(countCompletedDb);
        restore.setErrorMessage(aggregatedErrorMsg);
    }

    protected List<Database> validateAndFilterDatabasesForBackup(List<Database> databasesForBackup,
                                                                 boolean ignoreNotBackupableDatabases,
                                                                 ExternalDatabaseStrategy strategy) {

        Map<Boolean, List<Database>> partitioned = databasesForBackup.stream().collect(Collectors.partitioningBy(Database::isExternallyManageable));
        List<Database> externalDatabases = partitioned.get(true);
        List<Database> internalDatabases = partitioned.get(false);

        if (!externalDatabases.isEmpty()) {
            String externalNames = externalDatabases.stream()
                    .map(Database::getName)
                    .collect(Collectors.joining(", "));

            switch (strategy) {
                case FAIL -> {
                    log.error("External databases present but strategy=FAIL: {}", externalNames);
                    throw new DBNotSupportedValidationException(
                            Source.builder().parameter("externalDatabaseStrategy").build(),
                            "Backup failed: external databases not allowed by external database strategy: " + externalNames
                    );
                }
                case SKIP -> log.info("Excluding external databases from backup by strategy: {}", externalNames);
                case INCLUDE -> {
                }
            }
        }

        List<Database> notBackupableDatabases = internalDatabases.stream()
                .filter(db -> {
                    if (Boolean.TRUE.equals(db.getBackupDisabled())) {
                        return true;
                    }
                    if (db.getAdapterId() != null) {
                        return !physicalDatabasesService
                                .getAdapterById(db.getAdapterId())
                                .isBackupRestoreSupported();
                    }
                    return false;
                })
                .toList();


        if (!notBackupableDatabases.isEmpty()) {
            String dbNames = notBackupableDatabases.stream()
                    .map(AbstractDatabase::getName)
                    .collect(Collectors.joining(", "));

            if (ignoreNotBackupableDatabases) {
                log.info("Excluding not backupable databases: {}", dbNames);
            } else {
                log.error("Backup validation failed: Backup operation unsupported for databases: {}", dbNames);
                throw new DBNotSupportedValidationException(
                        Source.builder().parameter("ignoreNotBackupableDatabases").build(),
                        "Backup validation failed: Backup operation unsupported for databases: " + dbNames
                );
            }
        }

        List<Database> filteredDatabases = new ArrayList<>(internalDatabases);

        if (ignoreNotBackupableDatabases)
            filteredDatabases.removeAll(notBackupableDatabases);

        if (ExternalDatabaseStrategy.INCLUDE.equals(strategy))
            filteredDatabases.addAll(externalDatabases);

        return filteredDatabases;
    }

    private void backupExistenceCheck(String backupName) {
        if (backupRepository.findByIdOptional(backupName).isPresent()) {
            log.error("Backup with name {} already exists", backupName);
            throw new DBBackupValidationException(Source.builder().build(),
                    String.format("Backup with name %s already exists", backupName));
        }
    }
}
