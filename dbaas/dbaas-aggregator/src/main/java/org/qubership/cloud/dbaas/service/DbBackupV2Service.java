package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.dto.EnsuredUser;
import com.netcracker.cloud.dbaas.dto.Source;
import com.netcracker.cloud.dbaas.dto.backupV2.*;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.LogicalBackupAdapterResponse;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.LogicalRestoreAdapterResponse;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.DbState;
import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabase;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.*;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.LogicalRestore;
import com.netcracker.cloud.dbaas.entity.shared.AbstractDatabase;
import com.netcracker.cloud.dbaas.entity.shared.AbstractDatabaseRegistry;
import com.netcracker.cloud.dbaas.enums.ExternalDatabaseStrategy;
import com.netcracker.cloud.dbaas.enums.Status;
import com.netcracker.cloud.dbaas.exceptions.BackupAlreadyExistsException;
import com.netcracker.cloud.dbaas.exceptions.BackupExecutionException;
import com.netcracker.cloud.dbaas.exceptions.BackupNotFoundException;
import com.netcracker.cloud.dbaas.exceptions.DatabaseBackupNotSupportedException;
import com.netcracker.cloud.dbaas.mapper.BackupV2Mapper;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.BackupRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.RestoreRepository;
import com.netcracker.cloud.dbaas.utils.DbaasBackupUtils;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.cdi.SchedulerLock;
import net.javacrumbs.shedlock.core.LockAssert;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

@Slf4j
@ApplicationScoped
public class DbBackupV2Service {

    @ConfigProperty(name = "retry.delay.seconds")
    Duration retryDelay;

    @ConfigProperty(name = "retry.max")
    int maxRetries;

    @ConfigProperty(name = "retry.delay.track.ms")
    int trackDelayMs;

    @ConfigProperty(name = "retry.count")
    int retryCount;

    private final BackupRepository backupRepository;
    private final RestoreRepository restoreRepository;
    private final PhysicalDatabasesService physicalDatabasesService;
    private final DatabaseDbaasRepository databaseDbaasRepository;
    private final DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    private final BackupV2Mapper mapper;
    private final BalancingRulesService balancingRulesService;
    private final AsyncOperations asyncOperations;
    private final DBaaService dBaaService;
    private final PasswordEncryption encryption;

    @Inject
    public DbBackupV2Service(BackupRepository backupRepository,
                             RestoreRepository restoreRepository,
                             PhysicalDatabasesService physicalDatabasesService,
                             DatabaseDbaasRepository databaseDbaasRepository,
                             DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository,
                             BackupV2Mapper mapper,
                             BalancingRulesService balancingRulesService,
                             AsyncOperations asyncOperations,
                             DBaaService dBaaService,
                             PasswordEncryption encryption) {
        this.backupRepository = backupRepository;
        this.restoreRepository = restoreRepository;
        this.physicalDatabasesService = physicalDatabasesService;
        this.databaseDbaasRepository = databaseDbaasRepository;
        this.databaseRegistryDbaasRepository = databaseRegistryDbaasRepository;
        this.mapper = mapper;
        this.balancingRulesService = balancingRulesService;
        this.asyncOperations = asyncOperations;
        this.dBaaService = dBaaService;
        this.encryption = encryption;
    }

    public BackupOperationResponse backup(BackupRequest backupRequest, boolean dryRun) {
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

        return mapper.toBackupOperationResponse(backup);
    }

    protected Backup initializeFullBackupStructure(List<Database> databasesForBackup, BackupRequest backupRequest) {
        // Create base backup
        Backup backup = new Backup(
                backupRequest.getBackupName(),
                backupRequest.getStorageName(),
                backupRequest.getBlobPath(),
                backupRequest.getExternalDatabaseStrategy(),
                null); //TODO fill backup class properly

        // Partition databases into externally manageable and non-externally manageable
        Map<Boolean, List<Database>> partitioned = databasesForBackup.stream()
                .collect(Collectors.partitioningBy(AbstractDatabase::isExternallyManageable));

        // Handle non-externally managed databases
        List<LogicalBackup> logicalBackups = partitioned
                .getOrDefault(false, List.of())
                .stream()
                .collect(Collectors.groupingBy(AbstractDatabase::getAdapterId))
                .entrySet()
                .stream()
                .map(entry -> createLogicalBackup(entry.getKey(), entry.getValue(), backup))
                .toList();

        // Handle externally managed databases
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

        // Persist and return
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
                .map(entry ->
                        new BackupDatabase.User(
                                (String) entry.get("username"),
                                (String) entry.get("role")
                        )
                ).toList();
    }

    protected void startBackup(Backup backup) {
        List<CompletableFuture<Void>> futures = backup.getLogicalBackups().stream()
                .map(logicalBackup ->
                        CompletableFuture.supplyAsync(asyncOperations.wrapWithContext(() ->
                                        startLogicalBackup(logicalBackup)), asyncOperations.getBackupPool())
                                .thenAccept(response ->
                                        refreshLogicalBackupState(logicalBackup, response)
                                )
                                .exceptionally(throwable -> {
                                    logicalBackup.setStatus(Status.FAILED);
                                    logicalBackup.setErrorMessage(throwable.getCause() != null ?
                                            throwable.getCause().getMessage() : throwable.getMessage());
                                    return null;
                                }))
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

        RetryPolicy<Object> retryPolicy = buildRetryPolicy(logicalBackup.getLogicalBackupName(), "backupV2");

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
            log.error("Failsafe execution failed", e);
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
        if (backup.getAttemptCount() > retryCount) {
            log.warn("The number of attempts to track backup {} exceeded {}", backup.getName(), retryCount);
            backup.setStatus(Status.FAILED);
            backup.setErrorMessage("The number of attempts exceeded " + retryCount);
        } else {
            List<LogicalBackup> notFinishedLogicalBackups = backup.getLogicalBackups().stream()
                    .filter(db -> Status.IN_PROGRESS.equals(db.getStatus()))
                    .toList();
            fetchAndUpdateStatuses(notFinishedLogicalBackups);
            updateAggregatedStatus(backup);
            backup.setAttemptCount(backup.getAttemptCount() + 1); // update track attempt
        }

        backupRepository.save(backup);
    }

    private void fetchAndUpdateStatuses(List<LogicalBackup> logicalBackupList) {
        List<CompletableFuture<Void>> futures = logicalBackupList.stream()
                .map(logicalBackup -> {
                    RetryPolicy<Object> retryPolicy = buildRetryPolicy(logicalBackup.getLogicalBackupName(), "trackBackupV2");
                    return CompletableFuture.supplyAsync(
                                    asyncOperations.wrapWithContext(() -> Failsafe.with(retryPolicy).get(() -> {
                                        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalBackup.getAdapterId());
                                        return adapter.trackBackupV2(logicalBackup.getLogicalBackupName());
                                    })), asyncOperations.getBackupPool())
                            .thenAccept(response ->
                                    refreshLogicalBackupState(logicalBackup, response))
                            .exceptionally(throwable -> {
                                logicalBackup.setStatus(Status.FAILED);
                                logicalBackup.setErrorMessage(throwable.getCause() != null ?
                                        throwable.getCause().getMessage() : throwable.getMessage());
                                return null;
                            });
                })
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    protected void updateAggregatedStatus(Backup backup) {
        log.info("Start aggregating for backupName: {}", backup.getName());

        List<LogicalBackup> logicalBackupList = backup.getLogicalBackups();
        Set<Status> statusSet = new HashSet<>();

        StringBuilder errorMsg = new StringBuilder();
        int totalDbCount = 0;
        long totalBytes = 0;
        int countCompletedDb = 0;

        for (LogicalBackup logicalBackup : logicalBackupList) {
            statusSet.add(logicalBackup.getStatus());
            totalDbCount += logicalBackup.getBackupDatabases().size();

            if (logicalBackup.getErrorMessage() != null && !logicalBackup.getErrorMessage().isBlank()) {
                String warn = String.format("LogicalBackup %s failed: %s", logicalBackup.getLogicalBackupName(), logicalBackup.getErrorMessage());
                log.warn(warn);
                if (!errorMsg.isEmpty())
                    errorMsg.append("; ");
                errorMsg.append(warn);
            }
            totalBytes += logicalBackup.getBackupDatabases().stream().mapToLong(BackupDatabase::getSize).sum();
            countCompletedDb += (int) logicalBackup.getBackupDatabases().stream().filter(db -> Status.COMPLETED.equals(db.getStatus())).count();
        }

        backup.setStatus(aggregateStatus(statusSet));
        backup.setSize(totalBytes);
        backup.setTotal(totalDbCount);
        backup.setCompleted(countCompletedDb);
        backup.setErrorMessage(errorMsg.toString());
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
        Backup backup = getBackupOrThrowException(backupName);

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


    public BackupResponse getBackup(String backupName) {
        Backup backup = getBackupOrThrowException(backupName);

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
        Backup backup = getBackupOrThrowException(backupName);
        List<BackupDatabase> filteredBackupDbs = getAllDbByFilter(backup, restoreRequest.getFilterCriteria());

        Restore restore = initializeFullRestoreStructure(backup, filteredBackupDbs, restoreRequest);
        startRestore(restore, dryRun);
        aggregateRestoreStatus(restore);
    }

    private List<BackupDatabase> getAllDbByFilter(Backup backup, FilterCriteria filterCriteria) {
        //TODO filter by classifiers
        return backup.getLogicalBackups().stream().flatMap(lb -> lb.getBackupDatabases().stream()).toList();
    }

    protected Restore initializeFullRestoreStructure(
            Backup backup,
            List<BackupDatabase> backupDatabases,
            RestoreRequest restoreRequest
    ) {
        Map<String, String> namespacesMap = Optional.ofNullable(restoreRequest.getMapping())
                .map(Mapping::getNamespaces)
                .orElseGet(HashMap::new);

        // Group BackupDatabase by updated adapters
        Map<PhysicalDatabase, List<BackupDatabase>> groupedByTypeAndAdapter =
                groupBackupDatabasesByTypeAndAdapter(backupDatabases, namespacesMap);

        //Check adapters are backupable
        groupedByTypeAndAdapter.keySet().forEach(physicalDatabase -> {
            String adapterId = physicalDatabase.getAdapter().getAdapterId();
            if (!physicalDatabasesService.getAdapterById(adapterId).isBackupRestoreSupported()) {
                //TODO throw exception
            }
        });

        // Build logicalRestores for each new adapter
        List<LogicalRestore> logicalRestores = groupedByTypeAndAdapter.entrySet().stream()
                .map(entry -> {
                    LogicalRestore logicalRestore = new LogicalRestore();
                    logicalRestore.setType(entry.getKey().getType());
                    logicalRestore.setAdapterId(entry.getKey().getAdapter().getAdapterId());

                    List<RestoreDatabase> restoreDatabases =
                            createRestoreDatabases(entry.getValue(), namespacesMap);
                    logicalRestore.setRestoreDatabases(restoreDatabases);
                    restoreDatabases.forEach(rd -> rd.setLogicalRestore(logicalRestore));
                    return logicalRestore;
                })
                .toList();

        // Build restore
        Restore restore = new Restore();
        restore.setName(backup.getName());
        restore.setBackup(backup);
        restore.setStorageName(restoreRequest.getStorageName());
        restore.setBlobPath(restoreRequest.getBlobPath());
        restore.setLogicalRestores(new ArrayList<>(logicalRestores));

        // TODO change to mapping/filters
        restore.setMapping("\"n\"");
        restore.setFilters("\"n\"");

        // set up relation
        logicalRestores.forEach(lr -> lr.setRestore(restore));
        return restore;
    }


    private List<RestoreDatabase> createRestoreDatabases(
            List<BackupDatabase> backupDatabases,
            Map<String, String> namespacesMap
    ) {
        Set<SortedMap<String, Object>> uniqueClassifiers = new HashSet<>();

        return backupDatabases.stream()
                .map(backupDatabase -> {
                    // updated classifiers
                    List<SortedMap<String, Object>> classifiers = backupDatabase.getClassifiers().stream()
                            .map(classifier -> {
                                SortedMap<String, Object> updatedClassifier = updateClassifierNamespace(classifier, namespacesMap);

                                if (uniqueClassifiers.contains(updatedClassifier)) {
                                    //TODO throw exception
                                }
                                uniqueClassifiers.add(updatedClassifier);
                                return updatedClassifier;
                            })
                            .toList();

                    List<RestoreDatabase.User> users = backupDatabase.getUsers().stream()
                            .map(u -> new RestoreDatabase.User(u.getName(), u.getRole()))
                            .toList();

                    return RestoreDatabase.builder()
                            .backupDatabase(backupDatabase)
                            .name(backupDatabase.getName())
                            .classifiers(classifiers)
                            .settings(backupDatabase.getSettings())
                            .users(users)
                            .resources(backupDatabase.getResources())
                            .build();
                })
                .toList();
    }

    private SortedMap<String, Object> updateClassifierNamespace(
            SortedMap<String, Object> classifier,
            Map<String, String> namespacesMap
    ) {
        String oldNamespace = (String) classifier.get("namespace");
        String targetNamespace = namespacesMap.getOrDefault(oldNamespace, oldNamespace);

        SortedMap<String, Object> updatedClassifier = new TreeMap<>(classifier);
        updatedClassifier.put("namespace", targetNamespace);
        return updatedClassifier;
    }

    private Map<PhysicalDatabase, List<BackupDatabase>> groupBackupDatabasesByTypeAndAdapter(
            List<BackupDatabase> backupDatabases,
            Map<String, String> namespacesMap
    ) {
        return backupDatabases.stream()
                .map(db -> {
                    List<SortedMap<String, Object>> classifiers = db.getClassifiers();
                    String targetNamespace = null;
                    String microserviceName = null;

                    // 1) find classifier, that namespace exists in mapping
                    for (var c : classifiers) {
                        String oldNamespace = (String) c.get("namespace");
                        microserviceName = (String) c.get("microserviceName");
                        if (namespacesMap.containsKey(oldNamespace)) {
                            targetNamespace = namespacesMap.get(oldNamespace);
                            break;
                        }
                    }

                    // 2) if in case namespace not exists in mapping
                    if (targetNamespace == null) {
                        targetNamespace = (String) classifiers.getFirst().get("namespace");
                    }

                    String type = db.getLogicalBackup().getType();
                    PhysicalDatabase physicalDatabase = balancingRulesService
                            .applyBalancingRules(type, targetNamespace, microserviceName);

                    return Map.entry(physicalDatabase, db);
                })
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                ));
    }


    protected void startRestore(Restore restore, boolean dryRun) {
        List<LogicalRestore> logicalRestores = restore.getLogicalRestores();

        List<CompletableFuture<Void>> futures = logicalRestores.stream()
                .map(logicalRestore ->
                        CompletableFuture.supplyAsync(asyncOperations.wrapWithContext(() ->
                                        logicalRestore(logicalRestore, dryRun)))
                                .thenAccept(response ->
                                        refreshLogicalRestoreState(logicalRestore, response))
                                .exceptionally(throwable -> {
                                    logicalRestore.setStatus(Status.FAILED);
                                    logicalRestore.setErrorMessage(throwable.getCause() != null
                                            ? throwable.getCause().getMessage() : throwable.getMessage()); //TODO will be return general exception, see deleteBackup exception handling
                                    return null;
                                })
                )
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
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
                            .findFirst()
                            .orElse("");

                    String microserviceName = restoreDatabase.getClassifiers()
                            .stream()
                            .map(i -> (String) i.get("microserviceName"))
                            .findFirst()
                            .orElse("");

                    return Map.of(
                            "microserviceName", microserviceName,
                            "databaseName", restoreDatabase.getName(),
                            "namespace", namespace
                    );
                }).toList();

        Restore restore = logicalRestore.getRestore();
        RetryPolicy<Object> retryPolicy = buildRetryPolicy(logicalRestore.getLogicalRestoreName(), "restoreV2");

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
        if (restore.getAttemptCount() > retryCount) {
            log.warn("The number of attempts of track restore {} exceeded {}", restore.getName(), retryCount);
            restore.setStatus(Status.FAILED);
            restore.setErrorMessage("The number of attempts exceeded " + retryCount);
        } else {
            List<LogicalRestore> notFinishedLogicalRestores = restore.getLogicalRestores().stream()
                    .filter(db -> Status.IN_PROGRESS.equals(db.getStatus()))
                    .toList();
            fetchStatuses(notFinishedLogicalRestores);
            aggregateRestoreStatus(restore);
            restore.setAttemptCount(restore.getAttemptCount() + 1); // update track attempt
        }

        restoreRepository.save(restore);
    }


    private void fetchStatuses(List<LogicalRestore> logicalRestoreList) {
        List<CompletableFuture<Void>> futures = logicalRestoreList.stream()
                .map(logicalRestore -> {
                    RetryPolicy<Object> retryPolicy = buildRetryPolicy(logicalRestore.getLogicalRestoreName(), "trackRestoreV2");
                    return CompletableFuture.supplyAsync(
                                    asyncOperations.wrapWithContext(() -> Failsafe.with(retryPolicy).get(() -> {
                                        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalRestore.getAdapterId());
                                        return adapter.trackRestoreV2(logicalRestore.getLogicalRestoreName());
                                    })))
                            .thenAccept(response ->
                                    refreshLogicalRestoreState(logicalRestore, response))
                            .exceptionally(throwable -> {
                                logicalRestore.setStatus(Status.FAILED);
                                logicalRestore.setErrorMessage(throwable.getCause() != null
                                        ? throwable.getCause().getMessage() : throwable.getMessage());
                                return null;
                            });
                }).toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void aggregateRestoreStatus(Restore restore) {
        log.info("Start aggregating for restore: {}", restore.getName());

        List<LogicalRestore> logicalRestoreList = restore.getLogicalRestores();
        Set<Status> statusSet = new HashSet<>();
        int totalDbCount = 0;
        int countCompletedDb = 0;
        StringBuilder sb = new StringBuilder();

        for (LogicalRestore lr : logicalRestoreList) {
            statusSet.add(lr.getStatus());

            List<RestoreDatabase> dbs = lr.getRestoreDatabases();
            totalDbCount += dbs.size();
            countCompletedDb += (int) dbs.stream()
                    .filter(db -> Status.COMPLETED.equals(db.getStatus()))
                    .count();

            String errorMessage = lr.getErrorMessage();
            if (errorMessage != null && !errorMessage.isBlank()) {
                String warn = String.format("LogicalRestore %s failed: %s",
                        lr.getLogicalRestoreName(), errorMessage);
                log.warn(warn);

                if (!sb.isEmpty()) {
                    sb.append("; ");
                }
                sb.append(warn);
            }
        }

        restore.setStatus(aggregateStatus(statusSet));
        restore.setTotal(totalDbCount);
        restore.setCompleted(countCompletedDb);
        restore.setErrorMessage(sb.toString());
    }

    protected void initializeLogicalDatabasesFromRestore(Restore restore) {
        log.info("Start initializing logical databases from restore {}", restore.getName());
        restore.getLogicalRestores().forEach(logicalRestore -> {
            log.info("Processing logical restore type={}, adapterId={}", logicalRestore.getType(), logicalRestore.getAdapterId());
            logicalRestore.getRestoreDatabases().forEach(restoreDatabase -> {
                String type = logicalRestore.getType();
                Set<SortedMap<String, Object>> classifiers = new HashSet<>();

                log.info("Processing restoreDatabase [settings={}]", restoreDatabase.getSettings());
                restoreDatabase.getClassifiers().forEach(classifier -> {
                    classifiers.add(new TreeMap<>(classifier));
                    log.debug("Classifier candidate: {}", classifier);
                    databaseRegistryDbaasRepository
                            .getDatabaseByClassifierAndType(classifier, type)
                            .ifPresent(dbRegistry -> {
                                Database db = dbRegistry.getDatabase();
                                log.info("Found existing database {} for classifier {}", db.getId(), classifier);
                                List<TreeMap<String, Object>> existClassifiers = db.getDatabaseRegistry().stream()
                                        .map(AbstractDatabaseRegistry::getClassifier)
                                        .map(TreeMap::new)
                                        .toList();

                                classifiers.addAll(existClassifiers);
                                dBaaService.markDatabasesAsOrphan(dbRegistry);
                                log.info("Database {} marked as orphan", db.getId());
                                databaseRegistryDbaasRepository.saveAnyTypeLogDb(dbRegistry);
                            });
                });
                String adapterId = logicalRestore.getAdapterId();
                String physicalDatabaseId = physicalDatabasesService.getByAdapterId(adapterId).getId();
                Database newDatabase = createLogicalDatabase(restoreDatabase.getName(), restoreDatabase.getSettings(), classifiers, type, adapterId, physicalDatabaseId);
                ensureUsers(newDatabase, restoreDatabase.getUsers());
            });
        });
        log.info("Finished initializing logical databases from restore {}", restore.getName());
    }

    private Database createLogicalDatabase(String dbName,
                                           Map<String, Object> settings,
                                           Set<SortedMap<String, Object>> classifiers,
                                           String type,
                                           String adapterId,
                                           String physicalDatabaseId) {
        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setName(dbName);
        database.setType(type);
        database.setBackupDisabled(false);
        database.setSettings(settings);
        database.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        database.setDatabaseRegistry(new ArrayList<>());
        database.setBgVersion(""); //TODO
        database.setAdapterId(adapterId);
        database.setConnectionProperties(new ArrayList<>());
        database.setPhysicalDatabaseId(physicalDatabaseId);

        classifiers.forEach(classifier -> {
            String namespace = (String) classifier.get("namespace");
            DatabaseRegistry databaseRegistry = new DatabaseRegistry();
            databaseRegistry.setClassifier(classifier);
            databaseRegistry.setNamespace(namespace);
            databaseRegistry.setType(type);
            databaseRegistry.setDatabase(database);

            database.getDatabaseRegistry().add(databaseRegistry);
        });

        database.setClassifier(database.getDatabaseRegistry().getFirst().getClassifier());
        database.setNamespace(database.getDatabaseRegistry().getFirst().getNamespace());

        log.info("Saving new logical database with id=[{}], adapterId=[{}]", database.getId(), database.getAdapterId());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());
        return database;
    }

    private void ensureUsers(Database newDatabase, List<RestoreDatabase.User> users) {
        String dbName = newDatabase.getName();

        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(newDatabase.getAdapterId());
        RetryPolicy<Object> retryPolicy = buildRetryPolicy(dbName, "ensureUser");

        log.info("Ensuring {} users for database id=[{}] via adapter [{}]",
                users.size(), newDatabase.getId(), adapter.identifier());

        List<EnsuredUser> ensuredUsers = users.stream()
                .map(user ->
                        Failsafe.with(retryPolicy).get(() ->
                                adapter.ensureUser(user.getName(), null, dbName, user.getRole()))
                )
                .toList();

        newDatabase.setConnectionProperties(ensuredUsers.stream().map(EnsuredUser::getConnectionProperties).collect(Collectors.toList()));
        newDatabase.setResources(ensuredUsers.stream().map(EnsuredUser::getResources).filter(Objects::nonNull).flatMap(Collection::stream).collect(Collectors.toList()));
        newDatabase.setResources(newDatabase.getResources().stream().distinct().collect(Collectors.toList()));
        encryption.encryptPassword(newDatabase);
        databaseRegistryDbaasRepository.saveInternalDatabase(newDatabase.getDatabaseRegistry().getFirst());
        log.info("Users ensured and database [{}] updated", newDatabase.getId());
    }

    public void deleteBackup(String backupName) {
        Backup backup = getBackupOrThrowException(backupName);
        Map<String, String> errorHappenedAdapters = new ConcurrentHashMap<>();

        List<CompletableFuture<Void>> futures = backup.getLogicalBackups().stream()
                .map(logicalBackup -> {
                    RetryPolicy<Object> retryPolicy = buildRetryPolicy(
                            logicalBackup.getLogicalBackupName(),
                            "deleteBackupV2");

                    String adapterId = logicalBackup.getAdapterId();
                    log.info("backup with adapter id {} has {} databases to delete", adapterId, logicalBackup.getBackupDatabases().size());
                    return CompletableFuture.runAsync(
                                    asyncOperations.wrapWithContext(() ->
                                            Failsafe.with(retryPolicy).run(() ->
                                                    physicalDatabasesService.getAdapterById(adapterId).deleteBackupV2(logicalBackup.getLogicalBackupName()))
                                    ), asyncOperations.getBackupPool())
                            .exceptionally(throwable -> {
                                String exMessage = extractErrorMessage(throwable);
                                log.error("Delete backup with adapter id {} (logicalBackup={}) failed: {}",
                                        adapterId, logicalBackup.getLogicalBackupName(), exMessage);
                                errorHappenedAdapters.put(adapterId, exMessage);
                                return null;
                            });
                }).toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        if (!errorHappenedAdapters.isEmpty()) {
            String aggregatedError = String.format(
                    "Not all backups were deleted successfully in backup %s, failed adapters: %s",
                    backupName, errorHappenedAdapters
            );
            log.error(aggregatedError);
            throw new BackupExecutionException(URI.create("deleteBackup"), aggregatedError, new Throwable());
        }

        backupRepository.delete(backup);
        log.info("Deletion of backup {} succeed", backupName);
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
                case FAIL:
                    log.error("External databases present but strategy=FAIL: {}", externalNames);
                    throw new DatabaseBackupNotSupportedException(
                            "External databases not allowed by strategy=FAIL: " + externalNames,
                            Source.builder().parameter("ExternalDatabaseStrategy").build()
                    );
                case SKIP:
                    log.info("Excluding external databases from backup by strategy: {}", externalNames);
                    break;
                case INCLUDE:
                    break;
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
                throw new DatabaseBackupNotSupportedException(
                        "Backup operation unsupported for databases: " + dbNames,
                        Source.builder().parameter("ignoreNotBackupableDatabases").build()
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
            throw new BackupAlreadyExistsException(backupName, Source.builder().build());
        }
    }

    private Backup getBackupOrThrowException(String backupName) {
        return backupRepository.findByIdOptional(backupName)
                .orElseThrow(() -> new BackupNotFoundException("Backup not found", Source.builder().build()));
    }

    private RetryPolicy<Object> buildRetryPolicy(String name, String operation) {
        return new RetryPolicy<>()
                .handle(WebApplicationException.class)
                .withMaxRetries(maxRetries)
                .withDelay(retryDelay)
                .onFailedAttempt(e -> log.warn("Attempt failed for {}: {}",
                        name, extractErrorMessage(e.getLastFailure())))
                .onRetry(e -> log.info("Retrying {}...", operation))
                .onFailure(e -> log.error("Request limit exceeded for {}", name));
    }

    private String extractErrorMessage(Throwable throwable) {
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;

        if (cause instanceof WebApplicationException webEx) {
            Response response = webEx.getResponse();
            try {
                return response.readEntity(String.class);
            } catch (Exception readEx) {
                return "Unable to read response body: " + readEx.getMessage();
            }
        }

        return cause.getMessage();
    }


}
