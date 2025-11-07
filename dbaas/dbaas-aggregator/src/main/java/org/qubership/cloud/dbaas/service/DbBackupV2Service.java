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

import static com.netcracker.cloud.dbaas.Constants.*;
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
    private static final String RESTORE = "restore";
    private static final String DATABASE_NAME = "databaseName";

    private final BackupRepository backupRepository;
    private final RestoreRepository restoreRepository;
    private final PhysicalDatabasesService physicalDatabasesService;
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
        String backupName = backupRequest.getBackupName();
        backupExistenceCheck(backupName);

        log.info("Start backup process with name {}", backupName);
        Map<Database, List<DatabaseRegistry>> filteredDb = validateAndFilterDatabasesForBackup(
                getAllDbByFilter(backupRequest.getFilterCriteria()),
                backupRequest.getIgnoreNotBackupableDatabases(),
                backupRequest.getExternalDatabaseStrategy()
        );

        Backup backup = initializeFullBackupStructure(filteredDb, backupRequest);
        if (!dryRun) {
            // Saving initialized backup structure
            backupRepository.save(backup);
            startBackup(backup);
        }
        updateAggregatedStatus(backup);
        if (!dryRun) {
            // Saving aggregated backup structure
            backupRepository.save(backup);
        }
        return mapper.toBackupResponse(backup);
    }

    protected Backup initializeFullBackupStructure(Map<Database, List<DatabaseRegistry>> databasesForBackup, BackupRequest backupRequest) {
        // Create base backup
        Backup backup = new Backup(
                backupRequest.getBackupName(),
                backupRequest.getStorageName(),
                backupRequest.getBlobPath(),
                backupRequest.getExternalDatabaseStrategy(),
                mapper.toFilterCriteriaEntity(backupRequest.getFilterCriteria()),
                backupRequest.getIgnoreNotBackupableDatabases()
        );

        // Partition databases into externally manageable and non-externally manageable
        Map<Boolean, Map<Database, List<DatabaseRegistry>>> partitioned = databasesForBackup.entrySet().stream()
                .collect(Collectors.partitioningBy(entry ->
                                entry.getKey().isExternallyManageable(),
                        Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)
                ));

        // Handle non-externally managed databases
        List<LogicalBackup> logicalBackups = partitioned
                .getOrDefault(false, Map.of())
                .entrySet()
                .stream()
                .collect(Collectors.groupingBy(entry ->
                                entry.getKey().getAdapterId(),
                        Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .entrySet()
                .stream()
                .map(entry -> createLogicalBackup(entry.getKey(), entry.getValue(), backup))
                .toList();

        // Handle externally managed databases
        List<BackupExternalDatabase> externalDatabases = partitioned
                .getOrDefault(true, Map.of())
                .entrySet()
                .stream()
                .map(entry -> {
                    Database database = entry.getKey();
                    List<DatabaseRegistry> databaseRegistries = entry.getValue();
                    return BackupExternalDatabase.builder()
                            .backup(backup)
                            .name(database.getName())
                            .type(database.getDatabaseRegistry().getFirst().getType())
                            .classifiers(databaseRegistries.stream()
                                    .map(DatabaseRegistry::getClassifier)
                                    .toList())
                            .build();
                }).toList();

        // Persist and return
        backup.setExternalDatabases(externalDatabases);
        backup.setLogicalBackups(logicalBackups);
        return backup;
    }

    private LogicalBackup createLogicalBackup(String adapterId, Map<Database, List<DatabaseRegistry>> databaseToRegistry, Backup backup) {
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);

        if (!isBackupRestoreSupported(adapter)) {
            log.error("Adapter {} not support backup operation", adapterId);
            throw new DatabaseBackupRestoreNotSupportedException(
                    String.format("Adapter %s not support backup operation", adapterId),
                    Source.builder().build());
        }

        LogicalBackup logicalBackup = LogicalBackup.builder()
                .backup(backup)
                .adapterId(adapterId)
                .type(adapter.type())
                .backupDatabases(new ArrayList<>())
                .build();
        // Initializing backup database entity
        logicalBackup.getBackupDatabases().addAll(databaseToRegistry.entrySet().stream()
                .map(entry -> {
                    Database db = entry.getKey();
                    List<DatabaseRegistry> databaseRegistries = entry.getValue();
                    return BackupDatabase.builder()
                            .logicalBackup(logicalBackup)
                            .name(DbaasBackupUtils.getDatabaseName(db))
                            .classifiers(databaseRegistries.stream()
                                    .map(DatabaseRegistry::getClassifier).toList())
                            .users(getBackupDatabaseUsers(db.getConnectionProperties()))
                            .settings(db.getSettings())
                            .configurational(db.getBgVersion() != null && !db.getBgVersion().isBlank())
                            .build();
                    //TODO fill backup database resource field
                }).toList());
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
            return Failsafe.with(retryPolicy).get(() -> {
                DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);
                LogicalBackupAdapterResponse result = adapter.backupV2(new BackupAdapterRequest(storageName, blobPath, dbNames));

                if (result == null) {
                    log.error("Empty result from backup operation for adapter: {}, backup: {}", adapterId, backup.getName());
                    throw new BackupExecutionException(String.format("Empty result from backup operation for adapter %s", adapterId), new Throwable());
                }

                return result;
            });
        } catch (Exception e) {
            log.error("Logical backup startup for adapterId '{}' and backup with name '{}' failed", adapterId, backup.getName());
            throw new BackupExecutionException("Failsafe execution failed", e);
        }
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
    public void checkBackupsAsync() {
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
                String message = logicalBackup.getLogicalBackupName() != null
                        ? String.format("LogicalBackup %s failed: %s", logicalBackup.getLogicalBackupName(), error)
                        : String.format("LogicalBackup failed in adapter '%s' with error: %s", logicalBackup.getAdapterId(), error);
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

    protected Map<Database, List<DatabaseRegistry>> getAllDbByFilter(FilterCriteria filterCriteria) {
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

        List<DatabaseRegistry> databasesRegistriesForBackup = databaseRegistryDbaasRepository
                .findAnyLogDbRegistryTypeByNamespace(namespace)
                .stream()
                .filter(this::isValidRegistry)
                .toList();

        if (databasesRegistriesForBackup.isEmpty()) {
            log.warn("Databases that match filterCriteria not found");
            throw new DbNotFoundException("Databases that match filterCriteria not found", Source.builder().build()); //TODO make appropriate exception
        }

        return databasesRegistriesForBackup.stream()
                .collect(Collectors.groupingBy(DatabaseRegistry::getDatabase));
    }

    private boolean isValidRegistry(DatabaseRegistry registry) {
        return !registry.isMarkedForDrop()
                && !registry.getClassifier().containsKey(MARKED_FOR_DROP)
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
        log.info("Starting delete backup={}, force={}", backupName, force);
        Backup backup = backupRepository.findByIdOptional(backupName).orElse(null);
        if (backup == null)
            return;

        BackupStatus status = backup.getStatus();
        if (status != BackupStatus.COMPLETED
                && status != BackupStatus.FAILED
                && status != BackupStatus.DELETED) {
            log.error("Backup has invalid status={}. Only {}, {} or {} backups can be processed.",
                    status, BackupStatus.COMPLETED, BackupStatus.FAILED, BackupStatus.DELETED);
            throw new UnprocessableEntityException(
                    backup.getName(),
                    String.format(
                            "has invalid status='%s'. Only %s, %s or %s backups can be processed.",
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
                .map(logicalBackup -> deleteLogicalBackupAsync(logicalBackup, backup.getBlobPath(), failedAdapters))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete(asyncOperations.wrapWithContext((res, ex) ->
                        finalizeBackupDeletion(backup, failedAdapters)));
    }

    private CompletableFuture<Void> deleteLogicalBackupAsync(
            LogicalBackup logicalBackup,
            String blobPath,
            Map<String, String> failedAdapters
    ) {
        String adapterId = logicalBackup.getAdapterId();
        RetryPolicy<Object> retryPolicy =
                buildRetryPolicy(logicalBackup.getLogicalBackupName(), DELETE_BACKUP_OPERATION);

        log.info("Backup with adapterId {} has {} databases to delete",
                adapterId, logicalBackup.getBackupDatabases().size());

        return CompletableFuture.runAsync(
                        asyncOperations.wrapWithContext(() ->
                                Failsafe.with(retryPolicy).run(() -> {
                                            DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);
                                            adapter.deleteBackupV2(logicalBackup.getLogicalBackupName(), blobPath);
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
                RESTORE,
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

            // DryRun on adapters
            startRestore(restore, true);
            aggregateRestoreStatus(restore);
            if (RestoreStatus.FAILED != restore.getStatus()) {
                // Real run on adapters
                restore = getRestoreOrThrowException(restoreName);
                startRestore(restore, false);
                aggregateRestoreStatus(restore);
            }
            restoreRepository.save(restore);
            return mapper.toRestoreResponse(restore);
        } finally {
            if (!unlocked) {
                lock.unlock();
            }
        }
    }

    protected List<BackupDatabaseDelegate> getAllDbByFilter(List<BackupDatabase> backupDatabasesToFilter, FilterCriteria filterCriteria) {
        if (isFilterEmpty(filterCriteria))
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
        // Filter BackupDatabase by namespace
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
        // TODO optimize filtering, mapping for external, inner DBs
        // Apply ExternalDatabaseStrategy to external databases, filter by FilterCriteria
        List<RestoreExternalDatabase> externalDatabases = validateAndFilterExternalDb(
                backup.getExternalDatabases(),
                restoreRequest.getExternalDatabaseStrategy(),
                restoreRequest.getFilterCriteria());

        // MappingEntity classifiers of externalDb
        if (restoreRequest.getMapping() != null)
            externalDatabases = executeMappingForExternalDb(externalDatabases, restoreRequest.getMapping());

        // Filtering classifiers
        List<BackupDatabaseDelegate> backupDatabases = getAllDbByFilter(
                backup.getLogicalBackups().stream()
                        .flatMap(logicalBackup -> logicalBackup.getBackupDatabases().stream())
                        .toList(),
                restoreRequest.getFilterCriteria());


        // Group BackupDatabase by updated adapters
        Map<PhysicalDatabase, List<BackupDatabaseDelegate>> groupedByTypeAndAdapter =
                groupBackupDatabasesByTypeAndAdapter(backupDatabases, restoreRequest.getMapping());

        log.info("Initializing restore structure: restoreName={}, backupName={}",
                restoreRequest.getRestoreName(),
                backup.getName());
        // Build logicalRestores for each new adapter
        List<LogicalRestore> logicalRestores = groupedByTypeAndAdapter.entrySet().stream()
                .map(entry -> {
                    LogicalRestore logicalRestore = new LogicalRestore();
                    logicalRestore.setType(entry.getKey().getType());
                    logicalRestore.setAdapterId(entry.getKey().getAdapter().getAdapterId());

                    List<RestoreDatabase> restoreDatabases =
                            createRestoreDatabases(entry.getValue());
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
        restore.setExternalDatabaseStrategy(restoreRequest.getExternalDatabaseStrategy());
        restore.setExternalDatabases(externalDatabases);
        restore.setMapping(mapper.toMappingEntity(restoreRequest.getMapping()));
        restore.setFilterCriteria(mapper.toFilterCriteriaEntity(restoreRequest.getFilterCriteria()));

        // set up relation
        logicalRestores.forEach(lr -> lr.setRestore(restore));
        externalDatabases.forEach(db -> db.setRestore(restore));
        int totalDatabases = logicalRestores.stream()
                .mapToInt(lr -> lr.getRestoreDatabases().size())
                .sum();

        log.info("Restore structure initialized: restoreName={}, logicalRestores={}, restoreDatabases={}",
                restore.getName(), logicalRestores.size(), totalDatabases);

        return restore;
    }

    private List<RestoreExternalDatabase> validateAndFilterExternalDb(List<BackupExternalDatabase> externalDatabases,
                                                                      ExternalDatabaseStrategy strategy,
                                                                      FilterCriteria filterCriteria) {
        if (externalDatabases == null || externalDatabases.isEmpty())
            return List.of();

        String externalNames = externalDatabases.stream()
                .map(BackupExternalDatabase::getName)
                .collect(Collectors.joining(", "));

        return switch (strategy) {
            case FAIL -> {
                log.error("External databases not allowed by strategy={}: {}", ExternalDatabaseStrategy.FAIL, externalNames);
                throw new DatabaseBackupRestoreNotSupportedException(
                        String.format("External databases not allowed by strategy=%s: %s", ExternalDatabaseStrategy.FAIL, externalNames),
                        Source.builder().parameter("ExternalDatabaseStrategy").build()
                );
            }
            case SKIP -> {
                log.info("Excluding external databases from restore by strategy={}: external db names {}",
                        ExternalDatabaseStrategy.SKIP, externalNames);
                yield List.of();
            }
            case INCLUDE -> {
                log.info("Including external databases to restore by strategy: {}", ExternalDatabaseStrategy.INCLUDE);
                if (isFilterEmpty(filterCriteria))
                    yield mapper.toRestoreExternalDatabases(externalDatabases);

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
                yield mapper.toRestoreExternalDatabases(externalDatabases).stream()
                        .filter(db -> db.getClassifiers().stream()
                                .anyMatch(classifier ->
                                        namespace.equals(classifier.get(NAMESPACE)))
                        ).toList();
            }
        };
    }

    private List<RestoreExternalDatabase> executeMappingForExternalDb(List<RestoreExternalDatabase> externalDatabases,
                                                                      Mapping mapping) {
        return externalDatabases.stream()
                .peek(db -> {
                    Set<SortedMap<String, Object>> uniqueClassifiers = new HashSet<>();
                    List<SortedMap<String, Object>> updatedClassifiers = db.getClassifiers().stream()
                            .map(classifier -> updateAndValidateClassifier(classifier, mapping, uniqueClassifiers))
                            .toList();
                    db.setClassifiers(updatedClassifiers);
                })
                .toList();
    }

    private List<RestoreDatabase> createRestoreDatabases(
            List<BackupDatabaseDelegate> backupDatabases
    ) {
        return backupDatabases.stream()
                .map(delegatedBackupDatabase -> {
                    BackupDatabase backupDatabase = delegatedBackupDatabase.backupDatabase();
                    List<SortedMap<String, Object>> classifiers = delegatedBackupDatabase.classifiers();
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

    private SortedMap<String, Object> updateClassifier(SortedMap<String, Object> classifier, Mapping mapping) {
        String targetNamespace = getValue(mapping.getNamespaces(), (String) classifier.get(NAMESPACE));
        String targetTenant = getValue(mapping.getTenants(), (String) classifier.get(TENANT_ID));

        SortedMap<String, Object> updatedClassifier = new TreeMap<>(classifier);
        updatedClassifier.put(NAMESPACE, targetNamespace);
        if (targetTenant != null)
            updatedClassifier.put(TENANT_ID, targetTenant);
        return updatedClassifier;
    }

    private String getValue(Map<String, String> map, String oldValue) {
        if (map == null || map.isEmpty()) {
            return oldValue;
        }
        return map.getOrDefault(oldValue, oldValue);
    }

    private Map<PhysicalDatabase, List<BackupDatabaseDelegate>> groupBackupDatabasesByTypeAndAdapter(
            List<BackupDatabaseDelegate> backupDatabases,
            Mapping mapping) {

        return backupDatabases.stream()
                .map(db -> mapToPhysicalDatabaseEntry(db, mapping))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                ));
    }

    private Map.Entry<PhysicalDatabase, BackupDatabaseDelegate> mapToPhysicalDatabaseEntry(
            BackupDatabaseDelegate db, Mapping mapping) {
        List<SortedMap<String, Object>> classifiers = db.classifiers();
        if (classifiers.isEmpty()) {
            return null;
        }

        SortedMap<String, Object> firstClassifier = classifiers.getFirst();
        String targetNamespace = (String) firstClassifier.get(NAMESPACE);
        String microserviceName = (String) firstClassifier.get(MICROSERVICE_NAME);

        // Mapping classifiers
        if (mapping != null && mapping.getNamespaces() != null) {
            Set<SortedMap<String, Object>> uniqueClassifiers = new HashSet<>();
            classifiers = db.classifiers().stream()
                    .map(classifier -> updateAndValidateClassifier(classifier, mapping, uniqueClassifiers))
                    .toList();

            // Find the first classifier whose namespace exists in the mapping
            SortedMap<String, Object> matchedClassifier = classifiers.stream()
                    .filter(c -> mapping.getNamespaces().containsKey((String) c.get(NAMESPACE)))
                    .findFirst()
                    .orElse(classifiers.getFirst());

            String oldNamespace = (String) matchedClassifier.get(NAMESPACE);
            targetNamespace = mapping.getNamespaces().getOrDefault(oldNamespace, oldNamespace);
            microserviceName = (String) matchedClassifier.get(MICROSERVICE_NAME);
        }

        String type = db.backupDatabase().getLogicalBackup().getType();
        PhysicalDatabase physicalDatabase = balancingRulesService
                .applyBalancingRules(type, targetNamespace, microserviceName);

        // Checking adapter support backup restore
        String adapterId = physicalDatabase.getAdapter().getAdapterId();
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);
        if (!isBackupRestoreSupported(adapter)) {
            throw new DatabaseBackupRestoreNotSupportedException(
                    String.format("Adapter %s does not support restore operation", adapterId),
                    Source.builder().build());
        }

        return Map.entry(physicalDatabase, new BackupDatabaseDelegate(db.backupDatabase(), classifiers));
    }

    private SortedMap<String, Object> updateAndValidateClassifier(
            SortedMap<String, Object> classifier,
            Mapping mapping,
            Set<SortedMap<String, Object>> uniqueClassifiers) {
        SortedMap<String, Object> updatedClassifier = updateClassifier(classifier, mapping);
        // To prevent collision during mapping
        if (!uniqueClassifiers.add(updatedClassifier)) {
            String msg = String.format(
                    "Duplicate classifier detected after mapping: classifier='%s', mapping='%s'. " +
                            "Ensure all classifiers remain unique after mapping.",
                    classifier, mapping);
            log.error(msg);
            throw new IllegalResourceStateException(msg, Source.builder().build());
        }
        return updatedClassifier;
    }

    protected void startRestore(Restore restore, boolean dryRun) {
        List<LogicalRestore> logicalRestores = restore.getLogicalRestores();
        log.info("Starting requesting adapters to restore startup process: restore={}, dryRun={} logicalRestoreCount={}",
                restore.getName(), dryRun, restore.getLogicalRestores().size());
        List<CompletableFuture<Void>> futures = logicalRestores.stream()
                .map(logicalRestore ->
                        CompletableFuture.supplyAsync(asyncOperations.wrapWithContext(() ->
                                        logicalRestore(logicalRestore, dryRun)))
                                .thenAccept(response ->
                                        refreshLogicalRestoreState(logicalRestore, response))
                                .exceptionally(throwable -> {
                                    logicalRestore.setStatus(RestoreTaskStatus.FAILED);
                                    logicalRestore.setErrorMessage(extractErrorMessage(throwable));
                                    log.error("Logical restore failed: adapterId={}, error={}",
                                            logicalRestore.getAdapterId(), logicalRestore.getErrorMessage());
                                    return null;
                                })
                )
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void refreshLogicalRestoreState(LogicalRestore logicalRestore, LogicalRestoreAdapterResponse response) {
        log.info("Starting LogicalRestore state update [restoreName={}, logicalRestoreName={}]",
                logicalRestore.getRestore().getName(),
                response.getRestoreId());
        logicalRestore.setLogicalRestoreName(response.getRestoreId());
        logicalRestore.setStatus(mapper.toRestoreTaskStatus(response.getStatus()));
        logicalRestore.setErrorMessage(response.getErrorMessage());
        logicalRestore.setCreationTime(response.getCreationTime());
        logicalRestore.setCompletionTime(response.getCompletionTime());

        response.getDatabases().forEach(db -> {
            RestoreDatabase restoreDatabase = logicalRestore.getRestoreDatabases().stream()
                    .filter(restoreDb -> restoreDb.getBackupDatabase().getName().equals(db.getPreviousDatabaseName()))
                    .findAny().orElse(null);

            if (restoreDatabase != null) {
                restoreDatabase.setStatus(mapper.toRestoreTaskStatus(db.getStatus()));
                restoreDatabase.setDuration(db.getDuration());
                restoreDatabase.setPath(db.getPath());
                restoreDatabase.setErrorMessage(db.getErrorMessage());
                restoreDatabase.setCreationTime(db.getCreationTime());
                if (!restoreDatabase.getName().equals(db.getDatabaseName())) {
                    restoreDatabase.setName(db.getDatabaseName());
                    log.debug("For restore={} backup database updated: old name={}, new name={}",
                            logicalRestore.getRestore().getName(), db.getPreviousDatabaseName(), restoreDatabase.getName());
                }
            } else {
                List<String> existingDbNames = logicalRestore.getRestoreDatabases().stream()
                        .map(RestoreDatabase::getName)
                        .toList();
                String errorMsg = String.format("Database from adapter response not found in current LogicalRestore databases. " +
                                "logicalRestoreName: %s adapterReportedDb: %s knownDatabases: %s",
                        logicalRestore.getLogicalRestoreName(),
                        db.getPreviousDatabaseName(),
                        existingDbNames);
                log.warn(errorMsg);
                logicalRestore.setStatus(RestoreTaskStatus.FAILED);
                logicalRestore.setErrorMessage(errorMsg);
            }
        });
        log.debug("Updated logicalRestore={}, status={}, error message={}",
                logicalRestore.getLogicalRestoreName(), logicalRestore.getStatus(), logicalRestore.getErrorMessage());
    }

    private LogicalRestoreAdapterResponse logicalRestore(LogicalRestore logicalRestore, boolean dryRun) {
        String logicalBackupName = logicalRestore.getRestoreDatabases().getFirst()
                .getBackupDatabase()
                .getLogicalBackup()
                .getLogicalBackupName();
        List<Map<String, String>> databases = buildRestoreDatabases(logicalRestore);

        Restore restore = logicalRestore.getRestore();
        RetryPolicy<Object> retryPolicy = buildRetryPolicy(logicalRestore.getLogicalRestoreName(), RESTORE_OPERATION);

        try {
            return Failsafe.with(retryPolicy)
                    .get(() -> executeRestore(logicalRestore, logicalBackupName, restore, databases, dryRun));
        } catch (Exception e) {
            log.error("Logical restore startup for adapterId={} failed, restore={}", logicalRestore.getAdapterId(), restore.getName());
            throw new BackupExecutionException(
                    String.format("Logical restore startup for adapterId=%s failed, restore=%s",
                            logicalRestore.getAdapterId(), restore.getName()), e);
        }
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
            List<Map<String, String>> databases,
            boolean dryRun
    ) {
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalRestore.getAdapterId());

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
            if (!Objects.equals(restore.getTotal(), restore.getCompleted()) && RestoreStatus.COMPLETED != restore.getStatus()) {
                return;
            }
            Map<String, List<EnsuredUser>> dbNameToEnsuredUsers = restore.getLogicalRestores().stream()
                    .flatMap(lr -> lr.getRestoreDatabases().stream()
                            .map(rd -> Map.entry(
                                    rd.getName(),
                                    ensureUsers(lr.getAdapterId(), rd.getName(), rd.getUsers())
                            ))
                    )
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            initializeLogicalDatabasesFromRestore(restore, dbNameToEnsuredUsers);
        });
    }

    protected List<EnsuredUser> ensureUsers(String adapterId,
                                            String dbName,
                                            List<RestoreDatabase.User> users) {
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);
        RetryPolicy<Object> retryPolicy = buildRetryPolicy(dbName, ENSURE_USER_OPERATION);

        log.info("Ensuring {} users for databaseName=[{}] via adapter [{}]",
                users.size(), dbName, adapterId);

        return users.stream()
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
                .toList();
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
        log.debug("Starting checking status for logical restores: restore={}, logicalRestores={}",
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
        long totalDuration = 0;
        List<String> errorMessages = new ArrayList<>();

        for (LogicalRestore lr : logicalRestoreList) {
            statusSet.add(lr.getStatus());

            String errorMessage = lr.getErrorMessage();
            if (errorMessage != null && !errorMessage.isBlank()) {
                String warn = lr.getLogicalRestoreName() != null
                        ? String.format("LogicalRestore %s failed: %s", lr.getLogicalRestoreName(), errorMessage)
                        : String.format("LogicalRestore failed in adapterId '%s' with error: %s", lr.getAdapterId(), errorMessage);
                log.warn(warn);
                errorMessages.add(errorMessage);
            }

            List<RestoreDatabase> dbs = lr.getRestoreDatabases();
            for (RestoreDatabase restoreDatabase : dbs) {
                totalDbCount += 1;
                countCompletedDb += RestoreTaskStatus.COMPLETED == restoreDatabase.getStatus() ? 1 : 0;
                totalDuration += restoreDatabase.getDuration();
            }
        }

        restore.setStatus(aggregateRestoreStatus(statusSet));
        restore.setTotal(totalDbCount);
        restore.setCompleted(countCompletedDb);
        restore.setDuration(totalDuration);
        restore.setErrorMessage(String.join("; ", errorMessages));
        log.info("Aggregated restore status: restoreName={}, status={}, totalDb={}, completed={}, errorMessage={}",
                restore.getName(), restore.getStatus(), restore.getTotal(), restore.getCompleted(), restore.getErrorMessage());
    }

    @Transactional
    protected void initializeLogicalDatabasesFromRestore(Restore restore, Map<String, List<EnsuredUser>> dbNameToEnsuredUsers) {
        log.info("Start creating logicalDatabases from restore {}", restore.getName());
        try {
            // Creating LogicalDb based logicalRestores
            restore.getLogicalRestores().forEach(logicalRestore -> {
                log.info("Processing logicalRestore={}, type={}, adapterId={}", logicalRestore.getLogicalRestoreName(), logicalRestore.getType(), logicalRestore.getAdapterId());
                logicalRestore.getRestoreDatabases().forEach(restoreDatabase -> {
                    String type = logicalRestore.getType();
                    Set<SortedMap<String, Object>> classifiers = new HashSet<>();

                    log.info("Processing restoreDatabase={}", restoreDatabase.getName());
                    findSimilarDbByClassifier(classifiers, restoreDatabase.getClassifiers(), type);
                    String adapterId = logicalRestore.getAdapterId();
                    String physicalDatabaseId = physicalDatabasesService.getByAdapterId(adapterId).getPhysicalDatabaseIdentifier(); // TODO write assertions
                    List<EnsuredUser> ensuredUsers = dbNameToEnsuredUsers.get(restoreDatabase.getName());
                    Database newDatabase = createLogicalDatabase(
                            restoreDatabase.getName(),
                            restoreDatabase.getSettings(),
                            classifiers,
                            type,
                            false,
                            false,
                            adapterId,
                            physicalDatabaseId,
                            restoreDatabase.getBgVersion());

                    newDatabase.setConnectionProperties(ensuredUsers.stream().map(EnsuredUser::getConnectionProperties).toList());
                    newDatabase.setResources(ensuredUsers.stream().map(EnsuredUser::getResources).filter(Objects::nonNull).flatMap(Collection::stream).toList());
                    newDatabase.setResources(newDatabase.getResources().stream().distinct().collect(Collectors.toList()));
                    encryption.encryptPassword(newDatabase);
                    databaseRegistryDbaasRepository.saveInternalDatabase(newDatabase.getDatabaseRegistry().getFirst());
                    log.info("Based restoreDatabase={}, database id={} created", restore.getName(), newDatabase.getId());
                });
            });
            // Creating LogicalDb based externalDbs
            restore.getExternalDatabases().forEach(externalDatabase -> {
                log.info("Processing externalDatabase={}, type={}", externalDatabase.getName(), externalDatabase.getType());
                String type = externalDatabase.getType();
                Set<SortedMap<String, Object>> classifiers = new HashSet<>();

                findSimilarDbByClassifier(classifiers, externalDatabase.getClassifiers(), type);
                Database newDatabase = createLogicalDatabase(
                        externalDatabase.getName(),
                        null,
                        classifiers,
                        type,
                        true,
                        true,
                        null,
                        null,
                        null);
                databaseRegistryDbaasRepository.saveExternalDatabase(newDatabase.getDatabaseRegistry().getFirst());
                log.info("Based externalDb={}, database id={} created", externalDatabase.getName(), newDatabase.getId());
            });
            restore.setStatus(RestoreStatus.COMPLETED);
            log.info("Finished initializing logical databases from restore {}", restore.getName());
        } catch (Exception e) {
            log.error("Exception occurred during restore process", e);
            restore.setStatus(RestoreStatus.FAILED);
            restore.setErrorMessage(e.getMessage());
        }

    }

    private void findSimilarDbByClassifier(Set<SortedMap<String, Object>> uniqueClassifiers,
                                           List<SortedMap<String, Object>> classifiers,
                                           String type) {
        classifiers.forEach(classifier -> {
            uniqueClassifiers.add(new TreeMap<>(classifier));
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

                        uniqueClassifiers.addAll(existClassifiers);
                        dBaaService.markDatabasesAsOrphan(dbRegistry);
                        log.info("Database {} marked as orphan", db.getId());
                        databaseRegistryDbaasRepository.saveAnyTypeLogDb(dbRegistry);
                    });
        });
    }

    private Database createLogicalDatabase(String dbName,
                                           Map<String, Object> settings,
                                           Set<SortedMap<String, Object>> classifiers,
                                           String type,
                                           boolean backupDisabled,
                                           boolean externallyManageable,
                                           String adapterId,
                                           String physicalDatabaseId,
                                           String bgVersion
    ) {
        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setName(dbName);
        database.setType(type);
        database.setBackupDisabled(backupDisabled);
        database.setExternallyManageable(externallyManageable);
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
        database.setTimeDbCreation(new Date());
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

    protected Map<Database, List<DatabaseRegistry>> validateAndFilterDatabasesForBackup(Map<Database, List<DatabaseRegistry>> databasesForBackup,
                                                                                        boolean ignoreNotBackupableDatabases,
                                                                                        ExternalDatabaseStrategy strategy) {

        Map<Boolean, Map<Database, List<DatabaseRegistry>>> partitioned = databasesForBackup.entrySet().stream()
                .collect(Collectors.groupingBy(entry ->
                                entry.getKey().isExternallyManageable(),
                        Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)
                ));

        Map<Database, List<DatabaseRegistry>> externalDatabases = partitioned.get(true);
        Map<Database, List<DatabaseRegistry>> internalDatabases = partitioned.get(false);

        if (externalDatabases != null && !externalDatabases.isEmpty()) {
            String externalNames = externalDatabases.keySet().stream()
                    .map(Database::getName)
                    .collect(Collectors.joining(", "));

            switch (strategy) {
                case FAIL:
                    log.error("External databases not allowed for backup by strategy={}: {}", ExternalDatabaseStrategy.FAIL, externalNames);
                    throw new DatabaseBackupRestoreNotSupportedException(
                            String.format("External databases not allowed for backup by strategy=%s: %s", ExternalDatabaseStrategy.FAIL, externalNames),
                            Source.builder().parameter("ExternalDatabaseStrategy").build()
                    );
                case SKIP:
                    log.info("Excluding external databases from backup by strategy={}: {}",
                            ExternalDatabaseStrategy.SKIP, externalNames);
                    break;
                case INCLUDE:
                    break;
            }
        }

        List<Database> nonBackupableDatabases = internalDatabases.keySet().stream()
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


        if (!nonBackupableDatabases.isEmpty()) {
            String dbNames = nonBackupableDatabases.stream()
                    .map(AbstractDatabase::getName)
                    .collect(Collectors.joining(", "));

            if (ignoreNotBackupableDatabases) {
                log.info("Excluding not backupable databases: {}", dbNames);
            } else {
                log.error("Backup operation unsupported for databases: {}", dbNames);
                throw new DatabaseBackupRestoreNotSupportedException(
                        String.format("Backup operation unsupported for databases: %s", dbNames),
                        Source.builder().parameter("ignoreNotBackupableDatabases").build()
                );
            }
        }
        Map<Database, List<DatabaseRegistry>> filteredDatabases = new HashMap<>(internalDatabases);

        if (ignoreNotBackupableDatabases) {
            for (Database db : nonBackupableDatabases)
                filteredDatabases.remove(db);
        }
        if (ExternalDatabaseStrategy.INCLUDE.equals(strategy))
            filteredDatabases.putAll(externalDatabases);

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
        return throwable != null ? throwable.getMessage() : "Unknown error";
    }

    private boolean isFilterEmpty(FilterCriteria filterCriteria) {
        if (filterCriteria == null || filterCriteria.getFilter() == null)
            return true;

        return filterCriteria.getFilter().isEmpty()
                || filterCriteria.getFilter().stream().allMatch(this::isSingleFilterEmpty);
    }

    private boolean isSingleFilterEmpty(Filter f) {
        return (f.getNamespace() == null || f.getNamespace().isEmpty())
                && (f.getMicroserviceName() == null || f.getMicroserviceName().isEmpty())
                && (f.getDatabaseType() == null || f.getDatabaseType().isEmpty())
                && (f.getDatabaseKind() == null || f.getDatabaseKind().isEmpty());
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
