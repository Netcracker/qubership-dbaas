package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.dto.EnsuredUser;
import com.netcracker.cloud.dbaas.dto.Source;
import com.netcracker.cloud.dbaas.dto.backupV2.*;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.*;
import com.netcracker.cloud.dbaas.entity.pg.*;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.*;
import com.netcracker.cloud.dbaas.entity.shared.AbstractDatabase;
import com.netcracker.cloud.dbaas.entity.shared.AbstractDatabaseRegistry;
import com.netcracker.cloud.dbaas.enums.*;
import com.netcracker.cloud.dbaas.exceptions.*;
import com.netcracker.cloud.dbaas.mapper.BackupV2Mapper;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.BackupRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.BgNamespaceRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.RestoreRepository;
import com.netcracker.cloud.dbaas.utils.DbaasBackupUtils;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.cdi.SchedulerLock;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.netcracker.cloud.dbaas.entity.shared.AbstractDbState.DatabaseStateStatus.CREATED;
import static com.netcracker.cloud.dbaas.service.DBaaService.MARKED_FOR_DROP;
import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

@Slf4j
@ApplicationScoped
public class DbBackupV2Service {
    private static final String BACKUP_OPERATION = "backupV2";
    private static final String TRACK_BACKUP_OPERATION = "trackBackupV2";
    private static final String DELETE_BACKUP_OPERATION = "deleteBackupV2";
    private static final String RESTORE_OPERATION = "restoreV2";
    private static final String ENSURE_USER_OPERATION = "ensureUser";
    private static final String TRACK_RESTORE_OPERATION = "trackRestoreV2";

    private static final String USERNAME = "username";
    private static final String ROLE = "role";
    private static final String NAMESPACE = "namespace";
    private static final String MICROSERVICE_NAME = "microserviceName";
    private static final String DATABASE_NAME = "databaseName";

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
    private final BgNamespaceRepository bgNamespaceRepository;
    private final LockProvider lockProvider;

    private final Duration retryDelay;
    private final int retryAttempts;
    private final int retryCount;

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
                             PasswordEncryption encryption,
                             BgNamespaceRepository bgNamespaceRepository,
                             LockProvider lockProvider,
                             @ConfigProperty(name = "dbaas.backup-restore.retry.delay.seconds") Duration retryDelay,
                             @ConfigProperty(name = "dbaas.backup-restore.retry.attempts") int retryAttempts,
                             @ConfigProperty(name = "dbaas.backup-restore.check.attempts") int retryCount
    ) {
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
        this.bgNamespaceRepository = bgNamespaceRepository;
        this.lockProvider = lockProvider;
        this.retryDelay = retryDelay;
        this.retryAttempts = retryAttempts;
        this.retryCount = retryCount;
    }

    public BackupResponse backup(BackupRequest backupRequest, boolean dryRun) {
        if (dryRun)
            throw new FunctionalityNotImplemented("Dry-run mode");

        String backupName = backupRequest.getBackupName();
        backupExistenceCheck(backupName);

        log.info("Start backup process with name {}", backupName);
        List<Database> filteredDb = validateAndFilterDatabasesForBackup(
                getAllDbByFilter(backupRequest.getFilterCriteria()),
                backupRequest.getIgnoreNotBackupableDatabases(),
                backupRequest.getExternalDatabaseStrategy()
        );

        Backup backup = initializeFullBackupStructure(filteredDb, backupRequest);
        startBackup(backup);
        updateAggregatedStatus(backup);
        backupRepository.save(backup);
        return mapper.toBackupResponse(backup);
    }

    protected Backup initializeFullBackupStructure(List<Database> databasesForBackup, BackupRequest backupRequest) {
        // Create base backup
        Backup backup = new Backup(
                backupRequest.getBackupName(),
                backupRequest.getStorageName(),
                backupRequest.getBlobPath(),
                backupRequest.getExternalDatabaseStrategy(),
                mapper.toFilterCriteriaEntity(backupRequest.getFilterCriteria())
        );

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

        if (!isBackupRestoreSupported(adapter)) {
            log.error("Adapter {} not support backup operation", adapterId);
            throw new DatabaseBackupNotSupportedException(
                    String.format("Adapter %s not support backup operation", adapterId),
                    Source.builder().build());
        }

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
                        .configurational(db.getBgVersion() != null && !db.getBgVersion().isBlank())
                        .build())
                .toList());
        return logicalBackup;
    }

    private List<BackupDatabase.User> getBackupDatabaseUsers(List<Map<String, Object>> connectionProperties) {
        return connectionProperties.stream()
                .map(entry ->
                        new BackupDatabase.User(
                                (String) entry.get(USERNAME),
                                (String) entry.get(ROLE)
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
                                    logicalBackup.setStatus(BackupTaskStatus.FAILED);
                                    logicalBackup.setErrorMessage(extractErrorMessage(throwable));
                                    return null;
                                }))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        backupRepository.save(backup);
    }

    protected LogicalBackupAdapterResponse startLogicalBackup(LogicalBackup logicalBackup) {
        Backup backup = logicalBackup.getBackup();
        String storageName = backup.getStorageName();
        String blobPath = backup.getBlobPath();
        String adapterId = logicalBackup.getAdapterId();

        List<Map<String, String>> dbNames = logicalBackup.getBackupDatabases().stream()
                .map(db -> Map.of(DATABASE_NAME, db.getName()))
                .toList();

        RetryPolicy<Object> retryPolicy = buildRetryPolicy(logicalBackup.getLogicalBackupName(), BACKUP_OPERATION);

        try {
            return Failsafe.with(retryPolicy).get(() -> executeBackup(adapterId, storageName, blobPath, dbNames, backup.getName()));
        } catch (Exception e) {
            log.error("Logical backup startup for adapterId {} and backup {}", adapterId, backup.getName(), e);
            throw new BackupExecutionException("Failsafe execution failed", e);
        }
    }

    private LogicalBackupAdapterResponse executeBackup(
            String adapterId, String storageName, String blobPath, List<Map<String, String>> dbNames, String backupName) {
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);
        LogicalBackupAdapterResponse result = adapter.backupV2(new BackupAdapterRequest(storageName, blobPath, dbNames));

        if (result == null) {
            log.error("Empty result from backup operation for adapter: {}, backup: {}", adapterId, backupName);
            throw new BackupExecutionException(String.format("Empty result from backup operation for adapter %s", adapterId), new Throwable());
        }

        return result;
    }

    protected void refreshLogicalBackupState(LogicalBackup logicalBackup, LogicalBackupAdapterResponse logicalBackupAdapterResponse) {
        logicalBackup.setLogicalBackupName(logicalBackupAdapterResponse.getBackupId());
        logicalBackup.setStatus(mapper.toBackupTaskStatus(logicalBackupAdapterResponse.getStatus()));
        logicalBackup.setErrorMessage(logicalBackupAdapterResponse.getErrorMessage());
        logicalBackup.setCreationTime(logicalBackupAdapterResponse.getCreationTime());
        logicalBackup.setCompletionTime(logicalBackupAdapterResponse.getCompletionTime());

        Map<String, BackupDatabase> backupDbMap = logicalBackup.getBackupDatabases().stream()
                .collect(Collectors.toMap(BackupDatabase::getName, Function.identity()));

        logicalBackupAdapterResponse.getDatabases().forEach(db -> {
            BackupDatabase backupDb = backupDbMap.get(db.getDatabaseName());
            if (backupDb != null) {
                backupDb.setStatus(mapper.toBackupTaskStatus(db.getStatus()));
                backupDb.setSize(db.getSize());
                backupDb.setDuration(db.getDuration());
                backupDb.setCreationTime(db.getCreationTime());
                backupDb.setPath(db.getPath());
                backupDb.setErrorMessage(db.getErrorMessage());
            }
        });
    }

    @Scheduled(every = "${dbaas.backup-restore.check.interval}", concurrentExecution = SKIP)
    @SchedulerLock(name = "checkBackupsAsync")
    protected void checkBackupsAsync() {
        //TODO propagate correct business id
        log.info("Starting backup scheduler");
        LockAssert.assertLocked();
        List<Backup> backupsToAggregate = backupRepository.findBackupsToAggregate();
        backupsToAggregate.forEach(this::trackAndAggregate);
    }

    protected void trackAndAggregate(Backup backup) {
        if (backup.getAttemptCount() > retryCount) {
            log.warn("The number of attempts to track backup {} exceeded {}", backup.getName(), retryCount);
            backup.setStatus(BackupStatus.FAILED);
            backup.setErrorMessage(String.format("The number of attempts exceeded %s", retryCount));
        } else {
            fetchAndUpdateStatuses(backup);
            updateAggregatedStatus(backup);
            backup.incrementAttempt(); // update track attempt
        }
        backupRepository.save(backup);
    }

    private void fetchAndUpdateStatuses(Backup backup) {
        List<LogicalBackup> notFinishedBackups = backup.getLogicalBackups().stream()
                .filter(db -> db.getStatus() == BackupTaskStatus.IN_PROGRESS
                        || db.getStatus() == BackupTaskStatus.NOT_STARTED)
                .toList();

        List<CompletableFuture<Void>> futures = notFinishedBackups.stream()
                .map(this::trackLogicalBackupAsync)
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private CompletableFuture<Void> trackLogicalBackupAsync(LogicalBackup logicalBackup) {
        RetryPolicy<Object> retryPolicy =
                buildRetryPolicy(logicalBackup.getLogicalBackupName(), TRACK_BACKUP_OPERATION);

        return CompletableFuture.supplyAsync(
                        asyncOperations.wrapWithContext(() -> Failsafe.with(retryPolicy)
                                .get(() -> executeTrackBackup(logicalBackup))),
                        asyncOperations.getBackupPool()
                )
                .thenAccept(response -> refreshLogicalBackupState(logicalBackup, response))
                .exceptionally(throwable -> {
                    logicalBackup.setErrorMessage(extractErrorMessage(throwable));
                    return null;
                });
    }

    private LogicalBackupAdapterResponse executeTrackBackup(LogicalBackup logicalBackup) {
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalBackup.getAdapterId());
        LogicalBackupAdapterResponse response = adapter.trackBackupV2(
                logicalBackup.getLogicalBackupName(),
                logicalBackup.getBackup().getStorageName(),
                logicalBackup.getBackup().getBlobPath()
        );

        if (response == null) {
            log.error("Empty response from {} for {}", TRACK_BACKUP_OPERATION, logicalBackup.getLogicalBackupName());
            throw new BackupExecutionException(
                    String.format("Empty response from %s for %s", TRACK_BACKUP_OPERATION, logicalBackup.getLogicalBackupName()),
                    new Throwable()
            );
        }
        return response;
    }

    protected void updateAggregatedStatus(Backup backup) {
        log.info("Start aggregating for backupName: {}", backup.getName());

        List<LogicalBackup> logicalBackupList = backup.getLogicalBackups();
        Set<BackupTaskStatus> statusSet = new HashSet<>();

        int totalDbCount = 0;
        long totalBytes = 0;
        int completedDbCount = 0;
        List<String> errorMessages = new ArrayList<>();

        for (LogicalBackup logicalBackup : logicalBackupList) {
            statusSet.add(logicalBackup.getStatus());

            String error = logicalBackup.getErrorMessage();
            if (error != null && !error.isBlank()) {
                String message = String.format("LogicalBackup %s failed: %s",
                        logicalBackup.getLogicalBackupName(), error);
                log.warn(message);
                errorMessages.add(message);
            }

            for (BackupDatabase db : logicalBackup.getBackupDatabases()) {
                totalDbCount++;
                totalBytes += db.getSize();
                if (BackupTaskStatus.COMPLETED.equals(db.getStatus())) {
                    completedDbCount++;
                }
            }
        }

        backup.setStatus(aggregateBackupStatus(statusSet));
        backup.setSize(totalBytes);
        backup.setTotal(totalDbCount);
        backup.setCompleted(completedDbCount);
        backup.setErrorMessage(String.join("; ", errorMessages));
    }

    protected BackupStatus aggregateBackupStatus(Set<BackupTaskStatus> statusSet) {
        return aggregateStatus(statusSet,
                BackupTaskStatus::valueOf,
                BackupStatus::valueOf);
    }

    protected RestoreStatus aggregateRestoreStatus(Set<RestoreTaskStatus> statusSet) {
        return aggregateStatus(statusSet,
                RestoreTaskStatus::valueOf,
                RestoreStatus::valueOf);
    }

    public BackupStatusResponse getCurrentStatus(String backupName) {
        return mapper.toBackupStatusResponse(getBackupOrThrowException(backupName));
    }

    protected List<Database> getAllDbByFilter(FilterCriteria filterCriteria) {
        Filter filter = filterCriteria.getFilter().getFirst();

        if (filter.getNamespace().isEmpty()) {
            if (!filter.getMicroserviceName().isEmpty()) {
                throw new FunctionalityNotImplemented("backup by microservice");
            }
            if (!filter.getDatabaseKind().isEmpty()) {
                throw new FunctionalityNotImplemented("backup by databaseKind");
            }
            if (!filter.getDatabaseType().isEmpty()) {
                throw new FunctionalityNotImplemented("backup by databaseType");
            }
            throw new RequestValidationException(ErrorCodes.CORE_DBAAS_4043, "namespace", Source.builder().build());
        }
        if (filter.getNamespace().size() > 1) {
            throw new FunctionalityNotImplemented("backup by several namespace");
        }

        String namespace = filter.getNamespace().getFirst();

        List<Database> databasesForBackup = databaseDbaasRepository.findAnyLogDbTypeByNamespace(namespace);

        List<Database> filteredDatabase = databasesForBackup.stream().map(database -> {
                    List<DatabaseRegistry> databaseRegistries = database.getDatabaseRegistry().stream()
                            .filter(registry -> isValidRegistry(registry, namespace))
                            .map(registry -> new DatabaseRegistry(registry, registry.getNamespace()))
                            .toList();

                    if (databaseRegistries.isEmpty())
                        return null;

                    Database databaseToBackup = new Database(database);
                    databaseToBackup.setDatabaseRegistry(databaseRegistries);
                    databaseRegistries.forEach(registry -> registry.setDatabase(databaseToBackup));
                    return databaseToBackup;
                })
                .filter(Objects::nonNull)
                .toList();

        if (filteredDatabase.isEmpty()) {
            log.warn("Databases that match filterCriteria not found");
            throw new DbNotFoundException("Databases that match filterCriteria not found", Source.builder().build()); //TODO make appropriate exception
        }

        return filteredDatabase;
    }

    private boolean isValidRegistry(DatabaseRegistry registry, String namespace) {
        return !registry.isMarkedForDrop()
                && !registry.getClassifier().containsKey(MARKED_FOR_DROP)
                && namespace.equals(registry.getClassifier().get(NAMESPACE))
                && namespace.equals(registry.getNamespace())
                && CREATED.equals(registry.getDbState().getDatabaseState());
    }

    public BackupResponse getBackup(String backupName) {
        return mapper.toBackupResponse(getBackupOrThrowException(backupName));
    }

    public BackupResponse getBackupMetadata(String backupName) {
        Backup backup = getBackupOrThrowException(backupName);

        if (BackupStatus.COMPLETED != backup.getStatus()) {
            throw new UnprocessableEntityException(backupName,
                    String.format("can`t produce metadata for backup in status %s", backup.getStatus()),
                    Source.builder().build());
        }
        return mapper.toBackupResponse(backup);
    }

    public void uploadBackupMetadata(BackupResponse backupResponse) {
        Backup incomingBackup = mapper.toBackup(backupResponse);

        Optional<Backup> optionalExisting = backupRepository.findByIdOptional(incomingBackup.getName());

        if (optionalExisting.isEmpty()) {
            // new backup save as imported
            incomingBackup.setImported(true);
            backupRepository.save(incomingBackup);
            return;
        }

        Backup existingBackup = optionalExisting.get();

        String existingBackupDigest = existingBackup.getDigest();
        String incomingBackupDigest = incomingBackup.getDigest();

        if (existingBackup.getStatus() == BackupStatus.DELETED) {
            if (existingBackup.isImported()) {
                if (Objects.equals(existingBackupDigest, incomingBackupDigest)) {
                    existingBackup.setStatus(BackupStatus.COMPLETED);
                    backupRepository.save(existingBackup);
                    return;
                } else {
                    throw new IntegrityViolationException(
                            String.format("expected digest %s but got %s", existingBackupDigest, incomingBackupDigest),
                            Source.builder().build()
                    );
                }
            } else {
                throw new IllegalResourceStateException(
                        String.format("can`t restore %s backup that not imported", BackupStatus.DELETED),
                        Source.builder().build()
                );
            }
        }

        // if backup status not DELETED
        throw new IllegalResourceStateException(
                String.format("backup already exists and is not %s status", BackupStatus.DELETED),
                Source.builder().build()
        );
    }

    public void deleteBackup(String backupName, boolean force) {
        Backup backup = backupRepository.findByIdOptional(backupName).orElse(null);
        if (backup == null)
            return;

        BackupStatus status = backup.getStatus();
        if (status != BackupStatus.COMPLETED
                && status != BackupStatus.FAILED
                && status != BackupStatus.DELETED) {
            throw new UnprocessableEntityException(
                    backup.getName(),
                    String.format(
                            "has invalid status '%s'. Only %s, %s or %s backups can be processed.",
                            status, BackupStatus.COMPLETED, BackupStatus.FAILED, BackupStatus.DELETED),
                    Source.builder().build()
            );
        }

        if (!force) {
            backup.setStatus(BackupStatus.DELETED);
            backupRepository.save(backup);
            return;
        }

        backup.setStatus(BackupStatus.DELETE_IN_PROGRESS);
        backupRepository.save(backup);

        Map<String, String> failedAdapters = new ConcurrentHashMap<>();

        List<CompletableFuture<Void>> futures = backup.getLogicalBackups().stream()
                .map(logicalBackup -> deleteLogicalBackupAsync(logicalBackup, failedAdapters))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete(asyncOperations.wrapWithContext((res, ex) ->
                        finalizeBackupDeletion(backup, failedAdapters)));
    }

    private CompletableFuture<Void> deleteLogicalBackupAsync(
            LogicalBackup logicalBackup,
            Map<String, String> failedAdapters
    ) {
        String adapterId = logicalBackup.getAdapterId();
        RetryPolicy<Object> retryPolicy =
                buildRetryPolicy(logicalBackup.getLogicalBackupName(), DELETE_BACKUP_OPERATION);

        log.info("Backup with adapter id {} has {} databases to delete",
                adapterId, logicalBackup.getBackupDatabases().size());

        return CompletableFuture.runAsync(
                        asyncOperations.wrapWithContext(() ->
                                Failsafe.with(retryPolicy).run(() -> {
                                            DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);
                                            adapter.deleteBackupV2(logicalBackup.getLogicalBackupName());
                                        }
                                )),
                        asyncOperations.getBackupPool()
                )
                .exceptionally(throwable -> {
                    String errorMessage = extractErrorMessage(throwable);
                    log.error("Delete backup with adapter id {} (logicalBackup={}) failed: {}",
                            adapterId, logicalBackup.getLogicalBackupName(), errorMessage);
                    failedAdapters.put(adapterId, errorMessage);
                    return null;
                });
    }

    private void finalizeBackupDeletion(Backup backup, Map<String, String> failedAdapters) {
        if (failedAdapters.isEmpty()) {
            backup.setStatus(BackupStatus.DELETED);
            log.info("Backup {} deleted successfully", backup.getName());
        } else {
            String aggregatedError = String.format(
                    "Not all backups were deleted successfully in backup %s, failed adapters: %s",
                    backup.getName(), failedAdapters
            );
            log.error(aggregatedError);
            backup.setStatus(BackupStatus.FAILED);
            backup.setErrorMessage(aggregatedError);
        }
        backupRepository.save(backup);
    }

    public RestoreResponse restore(String backupName, RestoreRequest restoreRequest, boolean dryRun) {
        if (dryRun)
            throw new FunctionalityNotImplemented("dryRun");

        String restoreName = restoreRequest.getRestoreName();
        if (restoreRepository.findByIdOptional(restoreName).isPresent()) {
            log.error("Restore with name {} already exists", restoreName);
            throw new ResourceAlreadyExistsException(restoreName, Source.builder().build());
        }

        log.info("Start restore for backup {}", backupName);
        Backup backup = getBackupOrThrowException(backupName);

        BackupStatus backupStatus = backup.getStatus();
        if (BackupStatus.COMPLETED != backupStatus) {
            log.error("restore can`t process due to backup status {}", backupStatus);
            throw new UnprocessableEntityException(
                    backupName, String.format("restore can`t process due to backup status %s", backupStatus),
                    Source.builder().build());
        }

        LockConfiguration config = new LockConfiguration(
                Instant.now(),
                "restore",
                Duration.ofMinutes(2),
                Duration.ofMinutes(0)
        );

        Optional<SimpleLock> optLock = lockProvider.lock(config);

        if (optLock.isEmpty())
            throw new IllegalResourceStateException("restore already running", Source.builder().build());

        SimpleLock lock = optLock.get();
        boolean unlocked = false;

        try {
            if (restoreRepository.countNotCompletedRestores() > 0)
                throw new IllegalResourceStateException("another restore is being processed", Source.builder().build());

            Restore restore = initializeFullRestoreStructure(backup, restoreRequest);
            restoreRepository.save(restore);
            // unlock method after save restore
            lock.unlock();
            unlocked = true;

            startRestore(restore);
            aggregateRestoreStatus(restore);
            restoreRepository.save(restore);
            return mapper.toRestoreResponse(restore);
        } finally {
            if (!unlocked) {
                lock.unlock();
            }
        }
    }

    protected List<BackupDatabaseDelegate> getAllDbByFilter(List<BackupDatabase> backupDatabasesToFilter, FilterCriteria filterCriteria) {
        if (filterCriteria == null || filterCriteria.getFilter() == null || filterCriteria.getFilter().isEmpty())
            return backupDatabasesToFilter.stream().map(db -> new BackupDatabaseDelegate(db, db.getClassifiers()))
                    .toList();

        Filter filter = filterCriteria.getFilter().getFirst();

        if (filter.getNamespace().isEmpty()) {
            if (!filter.getMicroserviceName().isEmpty()) {
                throw new FunctionalityNotImplemented("restoration by microservice");
            }
            if (!filter.getDatabaseKind().isEmpty()) {
                throw new FunctionalityNotImplemented("restoration by databaseKind");
            }
            if (!filter.getDatabaseType().isEmpty()) {
                throw new FunctionalityNotImplemented("restoration by databaseType");
            }
            throw new RequestValidationException(ErrorCodes.CORE_DBAAS_4043, "namespace", Source.builder().build());
        }
        if (filter.getNamespace().size() > 1) {
            throw new FunctionalityNotImplemented("restoration by several namespace");
        }
        String namespace = filter.getNamespace().getFirst();

        List<BackupDatabaseDelegate> databaseDelegateList = backupDatabasesToFilter.stream()
                .map(backupDatabase -> {
                            List<SortedMap<String, Object>> filteredClassifiers = backupDatabase.getClassifiers().stream()
                                    .filter(classifier -> namespace.equals(classifier.get(NAMESPACE)))
                                    .map(classifier -> (SortedMap<String, Object>) new TreeMap<>(classifier))
                                    .toList();

                            if (filteredClassifiers.isEmpty())
                                return null;

                            return new BackupDatabaseDelegate(
                                    backupDatabase,
                                    filteredClassifiers
                            );
                        }
                )
                .filter(Objects::nonNull)
                .toList();

        if (databaseDelegateList.isEmpty()) {
            log.warn("Databases that match filterCriteria not found");
            throw new DbNotFoundException("Databases that match filterCriteria not found", Source.builder().build()); //TODO make appropriate exception
        }
        return databaseDelegateList;
    }

    protected Restore initializeFullRestoreStructure(
            Backup backup,
            RestoreRequest restoreRequest
    ) {
        log.info("Initializing restore structure: restoreName={}, backupName={}",
                restoreRequest.getRestoreName(),
                backup.getName());

        // Filtering classifiers
        List<BackupDatabaseDelegate> backupDatabases = getAllDbByFilter(
                backup.getLogicalBackups().stream()
                        .flatMap(logicalBackup -> logicalBackup.getBackupDatabases().stream())
                        .toList(),
                restoreRequest.getFilterCriteria());

        Map<String, String> namespacesMap = Optional.ofNullable(restoreRequest.getMapping())
                .map(Mapping::getNamespaces)
                .orElseGet(HashMap::new);

        // Group BackupDatabase by updated adapters
        Map<PhysicalDatabase, List<BackupDatabaseDelegate>> groupedByTypeAndAdapter =
                groupBackupDatabasesByTypeAndAdapter(backupDatabases, namespacesMap);

        //Check adapters are backupable
        groupedByTypeAndAdapter.keySet().forEach(physicalDatabase -> {
            String adapterId = physicalDatabase.getAdapter().getAdapterId();
            DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);
            if (!isBackupRestoreSupported(adapter)) {
                log.error("Adapter {} not support restore operation", adapterId);
                throw new DatabaseBackupNotSupportedException(
                        String.format("Adapter %s not support restore operation", adapterId),
                        Source.builder().build());
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
                    log.debug("Initialized restoreDatabase names {}",
                            restoreDatabases.stream().map(RestoreDatabase::getName).toList());
                    logicalRestore.setRestoreDatabases(restoreDatabases);
                    restoreDatabases.forEach(rd -> rd.setLogicalRestore(logicalRestore));
                    return logicalRestore;
                })
                .toList();

        // Build restore
        Restore restore = new Restore();
        restore.setName(restoreRequest.getRestoreName());
        restore.setBackup(backup);
        restore.setStorageName(restoreRequest.getStorageName());
        restore.setBlobPath(restoreRequest.getBlobPath());
        restore.setLogicalRestores(new ArrayList<>(logicalRestores));

        if (restoreRequest.getMapping() != null) {
            restore.setMapping(Restore.Mapping.builder()
                    .namespaces(restoreRequest.getMapping().getNamespaces())
                    .tenants(restoreRequest.getMapping().getTenants())
                    .build());
        }
        restore.setFilterCriteria(mapper.toFilterCriteriaEntity(restoreRequest.getFilterCriteria()));

        // set up relation
        logicalRestores.forEach(lr -> lr.setRestore(restore));

        int totalDatabases = logicalRestores.stream()
                .mapToInt(lr -> lr.getRestoreDatabases().size())
                .sum();

        log.info("Restore structure initialized: restoreName={}, logicalRestores={}, totalDatabases={}",
                restore.getName(), logicalRestores.size(), totalDatabases);

        return restore;
    }


    private List<RestoreDatabase> createRestoreDatabases(
            List<BackupDatabaseDelegate> backupDatabases,
            Map<String, String> namespacesMap
    ) {
        Set<SortedMap<String, Object>> uniqueClassifiers = new HashSet<>();

        return backupDatabases.stream()
                .map(delegatedBackupDatabase -> {
                    // updated classifiers
                    List<SortedMap<String, Object>> classifiers = delegatedBackupDatabase.filteredClassifiers().stream()
                            .map(classifier -> {
                                SortedMap<String, Object> updatedClassifier = updateClassifierNamespace(classifier, namespacesMap);
                                // to prevent collision
                                if (uniqueClassifiers.contains(updatedClassifier)) {
                                    String oldNamespace = (String) classifier.get(NAMESPACE);
                                    String updateNamespace = (String) updatedClassifier.get(NAMESPACE);
                                    log.error("classifier with namespace '{}' conflicts with existing classifier '{}'. " +
                                                    "Please ensure all classifier namespaces are unique.",
                                            updateNamespace, oldNamespace);
                                    throw new IllegalResourceStateException(
                                            String.format(
                                                    "classifier with namespace '%s' conflicts with existing classifier '%s'. " +
                                                            "Please ensure all classifier namespaces are unique.",
                                                    updateNamespace, oldNamespace
                                            ), Source.builder().build());
                                }
                                uniqueClassifiers.add(updatedClassifier);
                                return updatedClassifier;
                            })
                            .toList();

                    BackupDatabase backupDatabase = delegatedBackupDatabase.backupDatabase();
                    String namespace = (String) classifiers.getFirst().get(NAMESPACE);
                    String bgVersion = null;
                    if (backupDatabase.isConfigurational()) {
                        Optional<BgNamespace> bgNamespace = bgNamespaceRepository.findBgNamespaceByNamespace(namespace);
                        if (bgNamespace.isPresent())
                            bgVersion = bgNamespace.get().getVersion();
                    }

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
                            .bgVersion(bgVersion)
                            .build();
                })
                .toList();
    }

    private SortedMap<String, Object> updateClassifierNamespace(
            SortedMap<String, Object> classifier,
            Map<String, String> namespacesMap
    ) {
        String oldNamespace = (String) classifier.get(NAMESPACE);
        String targetNamespace = namespacesMap.getOrDefault(oldNamespace, oldNamespace);

        SortedMap<String, Object> updatedClassifier = new TreeMap<>(classifier);
        updatedClassifier.put(NAMESPACE, targetNamespace);
        return updatedClassifier;
    }

    private Map<PhysicalDatabase, List<BackupDatabaseDelegate>> groupBackupDatabasesByTypeAndAdapter(
            List<BackupDatabaseDelegate> backupDatabases,
            Map<String, String> namespacesMap
    ) {
        return backupDatabases.stream()
                .map(db -> {
                    List<SortedMap<String, Object>> classifiers = db.filteredClassifiers();
                    String targetNamespace = null;
                    String microserviceName = null;

                    // 1) find classifier, that namespace exists in mapping
                    for (var c : classifiers) {
                        String oldNamespace = (String) c.get(NAMESPACE);
                        microserviceName = (String) c.get(MICROSERVICE_NAME);
                        if (namespacesMap.containsKey(oldNamespace)) {
                            targetNamespace = namespacesMap.get(oldNamespace);
                            break;
                        }
                    }

                    // 2) if in case namespace not exists in mapping
                    if (targetNamespace == null) {
                        targetNamespace = (String) classifiers.getFirst().get(NAMESPACE);
                    }

                    String type = db.backupDatabase().getLogicalBackup().getType();
                    PhysicalDatabase physicalDatabase = balancingRulesService
                            .applyBalancingRules(type, targetNamespace, microserviceName);

                    return Map.entry(physicalDatabase, db);
                })
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                ));
    }


    protected void startRestore(Restore restore) {
        List<LogicalRestore> logicalRestores = restore.getLogicalRestores();
        log.info("Starting requesting adapters to restore process: restoreId={}, logicalRestoreCount={}",
                restore.getName(), restore.getLogicalRestores().size());
        List<CompletableFuture<Void>> futures = logicalRestores.stream()
                .map(logicalRestore ->
                        CompletableFuture.supplyAsync(asyncOperations.wrapWithContext(() ->
                                        logicalRestore(logicalRestore)))
                                .thenAccept(response ->
                                        refreshLogicalRestoreState(logicalRestore, response))
                                .exceptionally(throwable -> {
                                    logicalRestore.setStatus(RestoreTaskStatus.FAILED);
                                    logicalRestore.setErrorMessage(extractErrorMessage(throwable));
                                    log.error("Logical restore failed: id={}, error={}",
                                            logicalRestore.getLogicalRestoreName(), logicalRestore.getErrorMessage());
                                    return null;
                                })
                )
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        restoreRepository.save(restore);
    }

    private void refreshLogicalRestoreState(LogicalRestore logicalRestore, LogicalRestoreAdapterResponse response) {
        log.info("Start updating LogicalRestore state: restoreId={}", response.getRestoreId());
        logicalRestore.setLogicalRestoreName(response.getRestoreId());
        logicalRestore.setStatus(mapper.toRestoreTaskStatus(response.getStatus()));
        logicalRestore.setErrorMessage(response.getErrorMessage());
        logicalRestore.setCreationTime(response.getCreationTime());
        logicalRestore.setCompletionTime(response.getCompletionTime());

        log.debug("Updated LogicalRestore: status={}, error message={}",
                logicalRestore.getStatus(), logicalRestore.getErrorMessage());

        Map<String, RestoreDatabase> restoreDbMap = logicalRestore.getRestoreDatabases().stream()
                .collect(Collectors.toMap(RestoreDatabase::getName, Function.identity()));

        response.getDatabases().forEach(db -> {
            RestoreDatabase restoreDatabase = restoreDbMap.get(db.getPreviousDatabaseName());
            if (restoreDatabase != null) {
                restoreDatabase.setStatus(mapper.toRestoreTaskStatus(db.getStatus()));
                restoreDatabase.setDuration(db.getDuration());
                restoreDatabase.setPath(db.getPath());
                restoreDatabase.setErrorMessage(db.getErrorMessage());
                restoreDatabase.setCreationTime(db.getCreationTime());

                if (db.getDatabaseName() != null)
                    restoreDatabase.setName(db.getDatabaseName());
                log.debug("Database updated: old name={}, new name={}, error message={}, path={}",
                        db.getPreviousDatabaseName(), restoreDatabase.getName(),
                        restoreDatabase.getErrorMessage(), restoreDatabase.getPath());
            } else {
                List<String> existingDbNames = logicalRestore.getRestoreDatabases().stream()
                        .map(RestoreDatabase::getName)
                        .toList();
                log.warn(
                        "Database from adapter response not found in current LogicalRestore databases. " +
                                "logicalRestoreName: {} adapterReportedDb: {} knownDatabases: {}",
                        logicalRestore.getLogicalRestoreName(),
                        db.getPreviousDatabaseName(),
                        existingDbNames
                );
            }
        });
    }

    private LogicalRestoreAdapterResponse logicalRestore(LogicalRestore logicalRestore) {
        String logicalBackupName = extractLogicalBackupName(logicalRestore);
        List<Map<String, String>> databases = buildRestoreDatabases(logicalRestore);

        Restore restore = logicalRestore.getRestore();
        RetryPolicy<Object> retryPolicy = buildRetryPolicy(logicalRestore.getLogicalRestoreName(), RESTORE_OPERATION);

        try {
            return Failsafe.with(retryPolicy)
                    .get(() -> executeRestore(logicalRestore, logicalBackupName, restore, databases));
        } catch (Exception e) {
            log.error("Failsafe execution failed for logical restore {}", logicalRestore.getLogicalRestoreName(), e);
            throw new BackupExecutionException("Failsafe execution failed", e);
        }
    }

    private String extractLogicalBackupName(LogicalRestore logicalRestore) {
        return logicalRestore.getRestoreDatabases().getFirst()
                .getBackupDatabase()
                .getLogicalBackup()
                .getLogicalBackupName();
    }

    private List<Map<String, String>> buildRestoreDatabases(LogicalRestore logicalRestore) {
        return logicalRestore.getRestoreDatabases().stream()
                .map(restoreDatabase -> {
                    String namespace = restoreDatabase.getClassifiers().stream()
                            .map(i -> (String) i.get(NAMESPACE))
                            .findFirst()
                            .orElse("");

                    String microserviceName = restoreDatabase.getClassifiers().stream()
                            .map(i -> (String) i.get(MICROSERVICE_NAME))
                            .findFirst()
                            .orElse("");

                    return Map.of(
                            MICROSERVICE_NAME, microserviceName,
                            DATABASE_NAME, restoreDatabase.getName(),
                            NAMESPACE, namespace
                    );
                })
                .toList();
    }

    private LogicalRestoreAdapterResponse executeRestore(
            LogicalRestore logicalRestore,
            String logicalBackupName,
            Restore restore,
            List<Map<String, String>> databases
    ) {
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalRestore.getAdapterId());
        boolean dryRun = false; // TODO: make configurable

        LogicalRestoreAdapterResponse result = adapter.restoreV2(
                logicalBackupName,
                dryRun,
                new RestoreAdapterRequest(restore.getStorageName(), restore.getBlobPath(), databases)
        );

        if (result == null) {
            log.error("Empty result from restore operation for adapter {}", logicalRestore.getAdapterId());
            throw new BackupExecutionException(
                    String.format("Empty result from restore operation for adapter %s", logicalRestore.getAdapterId()),
                    new Throwable()
            );
        }

        return result;
    }


    @Scheduled(every = "${dbaas.backup-restore.check.interval}", concurrentExecution = SKIP)
    @SchedulerLock(name = "checkRestoresAsync")
    protected void checkRestoresAsync() {
        log.info("Starting restore scheduler");
        LockAssert.assertLocked();
        List<Restore> restoresToAggregate = restoreRepository.findRestoresToAggregate();

        log.info("Founded restores to aggregate {}",
                restoresToAggregate.stream().map(Restore::getName).toList());

        restoresToAggregate.forEach(this::trackAndAggregateRestore);
        restoresToAggregate.forEach(restore -> {
            if (!Objects.equals(restore.getTotal(), restore.getCompleted())) {
                return;
            }
            Map<String, List<EnsuredUser>> dbNameToEnsuredUsers = restore.getLogicalRestores().stream()
                    .flatMap(lr -> lr.getRestoreDatabases().stream()
                            .map(rd -> ensureUsers(lr.getAdapterId(), rd.getName(), rd.getUsers()))
                    )
                    .flatMap(m -> m.entrySet().stream())
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue
                    ));

            initializeLogicalDatabasesFromRestore(restore, dbNameToEnsuredUsers);
        });
    }


    protected Map<String, List<EnsuredUser>> ensureUsers(String adapterId,
                                                         String dbName,
                                                         List<RestoreDatabase.User> users) {
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);
        RetryPolicy<Object> retryPolicy = buildRetryPolicy(dbName, ENSURE_USER_OPERATION);

        log.info("Ensuring {} users for database name=[{}] via adapter [{}]",
                users.size(), dbName, adapterId);

        return Map.of(
                dbName,
                users.stream()
                        .map(user -> {
                            try {
                                return Failsafe.with(retryPolicy)
                                        .get(() -> adapter.ensureUser(user.getName(), null, dbName, user.getRole()));
                            } catch (Exception e) {
                                log.error("Failed to ensure user {} in database {}", user.getName(), dbName, e);
                                throw new BackupExecutionException(
                                        String.format("Failed to ensure user %s", user.getName()), e);
                            }
                        })
                        .toList()
        );
    }

    protected void trackAndAggregateRestore(Restore restore) {
        if (restore.getAttemptCount() > retryCount) {
            log.warn("The number of attempts of track restore {} exceeded {}", restore.getName(), retryCount);
            restore.setStatus(RestoreStatus.FAILED);
            restore.setErrorMessage(String.format("The number of attempts exceeded %s", retryCount));
        } else {
            fetchStatuses(restore);
            aggregateRestoreStatus(restore);
            restore.incrementAttempt(); // update track attempt
        }

        restoreRepository.save(restore);
    }


    private void fetchStatuses(Restore restore) {
        List<LogicalRestore> notFinishedLogicalRestores = restore.getLogicalRestores().stream()
                .filter(db -> RestoreTaskStatus.IN_PROGRESS == db.getStatus()
                        || RestoreTaskStatus.NOT_STARTED == db.getStatus())
                .toList();
        log.debug("Checking status for logical restores still in progress: restoreId={}, logicalRestores={}",
                restore.getName(),
                notFinishedLogicalRestores.stream()
                        .map(LogicalRestore::getLogicalRestoreName)
                        .toList());

        List<CompletableFuture<Void>> futures = notFinishedLogicalRestores.stream()
                .map(logicalRestore -> {
                    RetryPolicy<Object> retryPolicy = buildRetryPolicy(logicalRestore.getLogicalRestoreName(), TRACK_RESTORE_OPERATION);
                    return CompletableFuture.supplyAsync(
                                    asyncOperations.wrapWithContext(() -> Failsafe.with(retryPolicy).get(() -> {
                                        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalRestore.getAdapterId());
                                        return adapter.trackRestoreV2(logicalRestore.getLogicalRestoreName(), restore.getStorageName(), restore.getBlobPath());
                                    })))
                            .thenAccept(response ->
                                    refreshLogicalRestoreState(logicalRestore, response))
                            .exceptionally(throwable -> {
                                logicalRestore.setErrorMessage(throwable.getCause() != null
                                        ? throwable.getCause().getMessage() : throwable.getMessage());
                                return null;
                            });
                }).toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void aggregateRestoreStatus(Restore restore) {
        log.info("Start aggregating restore status: restoreName={}", restore.getName());

        List<LogicalRestore> logicalRestoreList = restore.getLogicalRestores();
        Set<RestoreTaskStatus> statusSet = new HashSet<>();

        int totalDbCount = 0;
        int countCompletedDb = 0;
        List<String> errorMessages = new ArrayList<>();

        for (LogicalRestore lr : logicalRestoreList) {
            statusSet.add(lr.getStatus());

            String errorMessage = lr.getErrorMessage();
            if (errorMessage != null && !errorMessage.isBlank()) {
                String warn = String.format("LogicalRestore %s failed: %s",
                        lr.getLogicalRestoreName(), errorMessage);
                log.warn(warn);
                errorMessages.add(errorMessage);
            }

            List<RestoreDatabase> dbs = lr.getRestoreDatabases();
            totalDbCount += dbs.size();
            countCompletedDb += (int) dbs.stream()
                    .filter(db -> RestoreTaskStatus.COMPLETED.equals(db.getStatus()))
                    .count();
        }

        restore.setStatus(aggregateRestoreStatus(statusSet));
        restore.setTotal(totalDbCount);
        restore.setCompleted(countCompletedDb);
        restore.setErrorMessage(String.join("; ", errorMessages));
        log.info("Aggregated restore status: restoreName={}, status={}, totalDb={}, completed={}, errorMessage={}",
                restore.getName(), restore.getStatus(), restore.getTotal(), restore.getCompleted(), restore.getErrorMessage());
    }

    @Transactional
    protected void initializeLogicalDatabasesFromRestore(Restore restore, Map<String, List<EnsuredUser>> dbNameToEnsuredUsers) {
        log.info("Start initializing logical databases from restore {}", restore.getName());
        try {
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
                    List<EnsuredUser> ensuredUsers = dbNameToEnsuredUsers.get(restoreDatabase.getName());
                    Database newDatabase = createLogicalDatabase(
                            restoreDatabase.getName(),
                            restoreDatabase.getSettings(),
                            classifiers,
                            type,
                            adapterId,
                            physicalDatabaseId,
                            restoreDatabase.getBgVersion());

                    newDatabase.setConnectionProperties(ensuredUsers.stream().map(EnsuredUser::getConnectionProperties).collect(Collectors.toList()));
                    newDatabase.setResources(ensuredUsers.stream().map(EnsuredUser::getResources).filter(Objects::nonNull).flatMap(Collection::stream).collect(Collectors.toList()));
                    newDatabase.setResources(newDatabase.getResources().stream().distinct().collect(Collectors.toList()));
                    encryption.encryptPassword(newDatabase);
                    databaseRegistryDbaasRepository.saveInternalDatabase(newDatabase.getDatabaseRegistry().getFirst());
                    log.info("Database [{}] created", newDatabase.getName());
                });
            });
            restore.setStatus(RestoreStatus.COMPLETED);
            log.info("Finished initializing logical databases from restore {}", restore.getName());
        } catch (Exception e) {
            log.error("Exception occurred during restore process", e);
            restore.setStatus(RestoreStatus.FAILED);
            restore.setErrorMessage(e.getMessage());
        }

    }

    private Database createLogicalDatabase(String dbName,
                                           Map<String, Object> settings,
                                           Set<SortedMap<String, Object>> classifiers,
                                           String type,
                                           String adapterId,
                                           String physicalDatabaseId,
                                           String bgVersion
    ) {
        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setName(dbName);
        database.setType(type);
        database.setBackupDisabled(false);
        database.setSettings(settings);
        database.setDbState(new DbState(CREATED));
        database.setDatabaseRegistry(new ArrayList<>());
        database.setAdapterId(adapterId);
        database.setBgVersion(bgVersion);
        database.setConnectionProperties(new ArrayList<>());
        database.setPhysicalDatabaseId(physicalDatabaseId);

        classifiers.forEach(classifier -> {
            String namespace = (String) classifier.get(NAMESPACE);
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

    public RestoreResponse getRestore(String restoreName) {
        return mapper.toRestoreResponse(getRestoreOrThrowException(restoreName));
    }

    public RestoreStatusResponse getRestoreStatus(String restoreName) {
        return mapper.toRestoreStatusResponse(getRestoreOrThrowException(restoreName));
    }

    public void deleteRestore(String restoreName) {
        log.info("Delete restoration with name = {}", restoreName);
        Restore restore = restoreRepository.findByIdOptional(restoreName)
                .orElse(null);

        if (restore == null)
            return;

        RestoreStatus status = restore.getStatus();
        if (status == RestoreStatus.DELETED)
            return;

        if (status != RestoreStatus.COMPLETED && status != RestoreStatus.FAILED) {
            throw new UnprocessableEntityException(
                    restoreName,
                    String.format(
                            "has invalid status '%s'. Only %s or %s restores can be processed.",
                            status, RestoreStatus.COMPLETED, RestoreStatus.FAILED
                    ),
                    Source.builder().build());
        }

        restore.setStatus(RestoreStatus.DELETED);
        restoreRepository.save(restore);
    }

    public RestoreResponse retryRestore(String restoreName) {
        throw new FunctionalityNotImplemented("retry restore functionality not implemented yet");
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
                    log.error("External databases present but strategy={}: {}", ExternalDatabaseStrategy.FAIL, externalNames);
                    throw new DatabaseBackupNotSupportedException(
                            String.format("External databases not allowed by strategy=%s: %s", ExternalDatabaseStrategy.FAIL, externalNames),
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
                        return !isBackupRestoreSupported(physicalDatabasesService.getAdapterById(db.getAdapterId()));
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
                log.error("Backup operation unsupported for databases: {}", dbNames);
                throw new DatabaseBackupNotSupportedException(
                        String.format("Backup operation unsupported for databases: %s", dbNames),
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

    private boolean isBackupRestoreSupported(DbaasAdapter adapter) {
        return adapter.isBackupRestoreSupported();
    }

    private void backupExistenceCheck(String backupName) {
        if (backupRepository.findByIdOptional(backupName).isPresent()) {
            log.error("Backup with name {} already exists", backupName);
            throw new ResourceAlreadyExistsException(backupName, Source.builder().build());
        }
    }

    private Backup getBackupOrThrowException(String backupName) {
        return backupRepository.findByIdOptional(backupName)
                .orElseThrow(() -> new BackupNotFoundException(backupName, Source.builder().build()));
    }

    private Restore getRestoreOrThrowException(String restoreName) {
        return restoreRepository.findByIdOptional(restoreName)
                .orElseThrow(() -> new BackupRestorationNotFoundException(restoreName, Source.builder().build()));
    }

    private RetryPolicy<Object> buildRetryPolicy(String name, String operation) {
        return new RetryPolicy<>()
                .handle(WebApplicationException.class)
                .withMaxRetries(retryAttempts)
                .withDelay(retryDelay)
                .onFailedAttempt(e -> log.warn("Attempt failed for {}: {}",
                        name, extractErrorMessage(e.getLastFailure())))
                .onRetry(e -> log.info("Retrying {}...", operation))
                .onFailure(e -> log.error("Request limit exceeded for {}", name));
    }

    private String extractErrorMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof WebApplicationException webEx) {
                Response response = webEx.getResponse();
                try {
                    return response.readEntity(String.class);
                } catch (Exception readEx) {
                    return "Unable to read response body: " + readEx.getMessage();
                }
            }
            cause = cause.getCause();
        }
        return throwable.getMessage();
    }

    private static <T extends Enum<T>, R extends Enum<R>> R aggregateStatus(
            Set<T> statusSet,
            Function<String, T> taskStatusGetter,
            Function<String, R> resultStatusGetter) {

        if (statusSet.contains(taskStatusGetter.apply("NOT_STARTED")) && statusSet.size() == 1)
            return resultStatusGetter.apply("NOT_STARTED");

        if (statusSet.contains(taskStatusGetter.apply("NOT_STARTED")) && statusSet.size() > 1)
            return resultStatusGetter.apply("IN_PROGRESS");

        if (statusSet.contains(taskStatusGetter.apply("IN_PROGRESS")))
            return resultStatusGetter.apply("IN_PROGRESS");

        if (statusSet.contains(taskStatusGetter.apply("FAILED")))
            return resultStatusGetter.apply("FAILED");

        if (statusSet.contains(taskStatusGetter.apply("COMPLETED")))
            return resultStatusGetter.apply("COMPLETED");
        return null;
    }
}
