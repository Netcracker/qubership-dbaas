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
import org.hibernate.exception.ConstraintViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.netcracker.cloud.dbaas.Constants.*;
import static com.netcracker.cloud.dbaas.entity.shared.AbstractDbState.DatabaseStateStatus.CREATED;
import static com.netcracker.cloud.dbaas.enums.RestoreTaskStatus.NOT_STARTED;
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
    private static final String LOGICAL_BACKUP = "logicalBackup";
    private static final String LOGICAL_RESTORE = "logicalRestore";
    private static final String RESTORE_DATABASE = "restoreDatabase";

    private static final Integer LOCK_AT_MOST = 2;
    private static final Integer LOCK_AT_LEAST = 0;

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
        backupExistenceCheck(backupRequest.getBackupName());

        log.info("Start backup process with backupRequest {}", backupRequest);
        Map<Database, List<DatabaseRegistry>> filteredDb = validateAndFilterDatabasesForBackup(
                getAllDbByFilter(backupRequest.getFilterCriteria()),
                backupRequest.getIgnoreNotBackupableDatabases(),
                backupRequest.getExternalDatabaseStrategy()
        );

        Backup backup = initializeFullBackupStructure(filteredDb, backupRequest);
        if (!dryRun) {
            try {
                backup = backupRepository.save(backup);
            } catch (ConstraintViolationException ex) {
                throwBackupAlreadyExistsException(backupRequest.getBackupName());
            }
            startBackup(backup);
        }
        updateAggregatedStatus(backup);
        if (!dryRun) {
            backup = backupRepository.save(backup);
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
                    return new BackupExternalDatabase(
                            UUID.randomUUID(),
                            backup,
                            database.getName(),
                            database.getDatabaseRegistry().getFirst().getType(),
                            databaseRegistries.stream()
                                    .map(DatabaseRegistry::getClassifier)
                                    .toList()
                    );
                }).toList();

        // Persist and return
        backup.setExternalDatabases(externalDatabases);
        backup.setLogicalBackups(logicalBackups);
        return backup;
    }

    private LogicalBackup createLogicalBackup(String adapterId, Map<Database, List<DatabaseRegistry>> databaseToRegistry, Backup backup) {
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);

        if (isBackupRestoreUnsupported(adapter)) {
            log.error("Adapter {} does not support backup operation", adapterId);
            throw new DatabaseBackupRestoreNotSupportedException(
                    String.format("Adapter %s does not support backup operation", adapterId),
                    Source.builder().build());
        }

        LogicalBackup logicalBackup = new LogicalBackup(UUID.randomUUID(), backup, adapterId, adapter.type());

        // Initializing backup database entity
        logicalBackup.getBackupDatabases().addAll(databaseToRegistry.entrySet().stream()
                .map(entry -> {
                    Database db = entry.getKey();
                    List<DatabaseRegistry> databaseRegistries = entry.getValue();
                    return new BackupDatabase(
                            UUID.randomUUID(),
                            logicalBackup,
                            DbaasBackupUtils.getDatabaseName(db),
                            databaseRegistries.stream().map(DatabaseRegistry::getClassifier).toList(),
                            db.getSettings(),
                            getBackupDatabaseUsers(db.getConnectionProperties()),
                            db.getBgVersion() != null && !db.getBgVersion().isBlank()
                    );
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
                                    if (is4xxError(throwable)) {
                                        logicalBackup.setStatus(BackupTaskStatus.FAILED);
                                    } else {
                                        logicalBackup.setStatus(BackupTaskStatus.RETRYABLE_FAIL);
                                    }
                                    logicalBackup.setErrorMessage(extractErrorMessage(throwable));
                                    log.error("Logical backup failed: logicalBackup={}, error={}",
                                            logicalBackup.getId(), logicalBackup.getErrorMessage(), throwable);
                                    return null;
                                }))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    protected LogicalBackupAdapterResponse startLogicalBackup(LogicalBackup logicalBackup) {
        Backup backup = logicalBackup.getBackup();
        String storageName = backup.getStorageName();
        String blobPath = backup.getBlobPath();
        String adapterId = logicalBackup.getAdapterId();

        List<Map<String, String>> dbNames = logicalBackup.getBackupDatabases().stream()
                .map(db -> Map.of(DATABASE_NAME, db.getName()))
                .toList();

        RetryPolicy<Object> retryPolicy = buildRetryPolicy(BACKUP_OPERATION, LOGICAL_BACKUP, logicalBackup.getId().toString(), adapterId);

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
        List<Backup> backupsToAggregate = backupRepository.findBackupsToTrack();
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
            backup.incrementAttempt();
        }
        backupRepository.save(backup);
    }

    private void fetchAndUpdateStatuses(Backup backup) {
        List<LogicalBackup> notFinishedBackups = backup.getLogicalBackups().stream()
                .filter(db ->
                        db.getStatus() == BackupTaskStatus.IN_PROGRESS
                                || db.getStatus() == BackupTaskStatus.NOT_STARTED
                                || db.getStatus() == BackupTaskStatus.RETRYABLE_FAIL
                ).toList();

        List<CompletableFuture<Void>> futures = notFinishedBackups.stream()
                .map(this::trackLogicalBackupAsync)
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private CompletableFuture<Void> trackLogicalBackupAsync(LogicalBackup logicalBackup) {
        RetryPolicy<Object> retryPolicy =
                buildRetryPolicy(TRACK_BACKUP_OPERATION, LOGICAL_BACKUP, logicalBackup.getId().toString(), logicalBackup.getAdapterId());

        return CompletableFuture.supplyAsync(
                        asyncOperations.wrapWithContext(() -> Failsafe.with(retryPolicy)
                                .get(() -> executeTrackBackup(logicalBackup))),
                        asyncOperations.getBackupPool()
                )
                .thenAccept(response -> refreshLogicalBackupState(logicalBackup, response))
                .exceptionally(throwable -> {
                    if (is4xxError(throwable)) {
                        logicalBackup.setStatus(BackupTaskStatus.FAILED);
                    } else {
                        logicalBackup.setStatus(BackupTaskStatus.RETRYABLE_FAIL);
                    }
                    logicalBackup.setErrorMessage(extractErrorMessage(throwable));
                    return null;
                });
    }

    private LogicalBackupAdapterResponse executeTrackBackup(LogicalBackup logicalBackup) {
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalBackup.getAdapterId());

        if (isLogicalBackupNotStarted(logicalBackup)) {
            LogicalBackupAdapterResponse response = startLogicalBackup(logicalBackup);
            refreshLogicalBackupState(logicalBackup, response);
        }

        LogicalBackupAdapterResponse response = adapter.trackBackupV2(
                logicalBackup.getLogicalBackupName(),
                logicalBackup.getBackup().getStorageName(),
                logicalBackup.getBackup().getBlobPath()
        );

        if (response == null) {
            log.error("Operation {} return empty response from adapter={} for logicalBackup={}", TRACK_BACKUP_OPERATION, logicalBackup.getAdapterId(), logicalBackup.getId()); //not obvious from
            throw new BackupExecutionException(
                    String.format("Operation %s return empty response from adapter=%s for logicalBackup=%s", TRACK_BACKUP_OPERATION, logicalBackup.getAdapterId(), logicalBackup.getId()),
                    new Throwable()
            );
        }
        return response;
    }

    protected void updateAggregatedStatus(Backup backup) {
        log.info("Start aggregating for backupName: {}", backup.getName());

        List<LogicalBackup> logicalBackupList = backup.getLogicalBackups();
        Set<BackupTaskStatus> statusSet = new HashSet<>();

        int totalDbCount = backup.getExternalDatabases() == null ? 0 : backup.getExternalDatabases().size();
        int completedDbCount = backup.getExternalDatabases() == null ? 0 : backup.getExternalDatabases().size();
        long totalBytes = 0;
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

        backup.setStatus(aggregateBackupTaskStatus(statusSet));
        backup.setSize(totalBytes);
        backup.setTotal(totalDbCount);
        backup.setCompleted(completedDbCount);
        backup.setErrorMessage(String.join("; ", errorMessages));
    }

    public BackupStatusResponse getCurrentStatus(String backupName) {
        return mapper.toBackupStatusResponse(getBackupOrThrowException(backupName));
    }

    protected Map<Database, List<DatabaseRegistry>> getAllDbByFilter(FilterCriteria filterCriteria) {
        List<DatabaseRegistry> filteredDatabases = databaseRegistryDbaasRepository
                .findAllDatabasesByFilter(filterCriteria.getInclude())
                .stream()
                .filter(registry -> {
                    if (!isValidRegistry(registry))
                        return false;
                    return filterCriteria.getExclude().stream()
                            .noneMatch(exclude -> {
                                boolean configurational = registry.getBgVersion() != null && !registry.getBgVersion().isBlank();
                                return isMatches(exclude,
                                        (String) registry.getClassifier().get(NAMESPACE),
                                        (String) registry.getClassifier().get(MICROSERVICE_NAME),
                                        registry.getType(),
                                        configurational);
                            });
                })
                .toList();

        if (filteredDatabases.isEmpty()) {
            log.warn("No databases matching the filtering criteria were found during the backup");
            throw new DbNotFoundException("No databases matching the filtering criteria were found during the backup", Source.builder().build());
        }

        return filteredDatabases.stream()
                .collect(Collectors.groupingBy(DatabaseRegistry::getDatabase));
    }

    private boolean isValidRegistry(DatabaseRegistry registry) {
        if (registry.isMarkedForDrop()
                || registry.getClassifier().containsKey(MARKED_FOR_DROP))
            return false;

        return registry.isExternallyManageable()
                || CREATED.equals(registry.getDbState().getDatabaseState());
    }

    private boolean isMatches(Filter filter, String namespace, String microserviceName, String type, boolean configurational) {
        if (!filter.getNamespace().isEmpty() &&
                !filter.getNamespace().contains(namespace)) {
            return false;
        }

        if (!filter.getMicroserviceName().isEmpty() &&
                !filter.getMicroserviceName().contains(microserviceName)) {
            return false;
        }

        if (!filter.getDatabaseType().isEmpty() &&
                filter.getDatabaseType().stream().noneMatch(dt -> dt.getType().equals(type))) {
            return false;
        }

        if (!filter.getDatabaseKind().isEmpty()) {
            return isKindMatched(configurational, filter.getDatabaseKind().getFirst());
        }
        return true;
    }

    private boolean isKindMatched(boolean configurational, DatabaseKind kind) {
        if (kind == DatabaseKind.CONFIGURATION)
            return configurational;
        if (kind == DatabaseKind.TRANSACTIONAL)
            return !configurational;
        return true;
    }

    public BackupResponse getBackup(String backupName) {
        return mapper.toBackupResponse(getBackupOrThrowException(backupName));
    }

    public BackupResponse getBackupMetadata(String backupName) {
        Backup backup = getBackupOrThrowException(backupName);

        if (BackupStatus.COMPLETED != backup.getStatus()) {
            throw new UnprocessableEntityException(backupName,
                    String.format("can't produce metadata for backup %s in status %s", backupName, backup.getStatus()),
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
                        String.format("can't restore a %s backup that is not imported", BackupStatus.DELETED),
                        Source.builder().build()
                );
            }
        }

        // if backup status not DELETED
        throw new IllegalResourceStateException(
                String.format("backup already exists and is not in %s status", BackupStatus.DELETED),
                Source.builder().build()
        );
    }

    public void deleteBackup(String backupName, boolean force) {
        log.info("Starting delete backup={}, force={}", backupName, force);
        Backup backup = backupRepository.findByIdOptional(backupName).orElse(null);
        if (backup == null)
            return;

        BackupStatus status = backup.getStatus();
        if (status != BackupStatus.COMPLETED &&
                status != BackupStatus.FAILED &&
                status != BackupStatus.DELETED) {
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
                buildRetryPolicy(DELETE_BACKUP_OPERATION, LOGICAL_BACKUP, logicalBackup.getId().toString(), adapterId);

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

    public RestoreResponse restore(String backupName, RestoreRequest restoreRequest, boolean dryRun, boolean allowParallel) {
        String restoreName = restoreRequest.getRestoreName();
        if (restoreRepository.findByIdOptional(restoreName).isPresent()) {
            log.error("Restore with name {} already exists", restoreName);
            throw new ResourceAlreadyExistsException(restoreName, Source.builder().build());
        }

        Backup backup = getBackupOrThrowException(backupName);
        checkBackupStatusForRestore(restoreName, backup.getStatus());

        if (dryRun) {
            return applyDryRunRestore(backup, restoreRequest);
        }

        Restore restore = restoreLockWrapper(() -> {
            Restore currRestore = initializeFullRestoreStructure(backup, restoreRequest);
            return restoreRepository.save(currRestore);
        }, allowParallel);

        log.info("Start restore {} for backup {}", restoreName, backupName);
        // DryRun on adapters
        startRestore(restore, true);
        aggregateRestoreStatus(restore);
        if (RestoreStatus.FAILED != restore.getStatus()) {
            // Real run on adapters
            startRestore(restore, false);
            aggregateRestoreStatus(restore);
        }
        // To always complete restore process async
        if (RestoreStatus.COMPLETED == restore.getStatus())
            restore.setStatus(RestoreStatus.IN_PROGRESS);

        restoreRepository.save(restore);
        return mapper.toRestoreResponse(restore);
    }

    private RestoreResponse applyDryRunRestore(Backup backup, RestoreRequest restoreRequest) {
        Restore currRestore = initializeFullRestoreStructure(backup, restoreRequest);
        startRestore(currRestore, true);
        aggregateRestoreStatus(currRestore);
        return mapper.toRestoreResponse(currRestore);
    }

    protected List<DatabaseWithClassifiers> getAllDbByFilter(List<BackupDatabase> backupDatabasesToFilter, FilterCriteria filterCriteria) {
        return backupDatabasesToFilter.stream()
                .map(db -> {
                    String type = db.getLogicalBackup().getType();
                    boolean configurational = db.isConfigurational();

                    List<ClassifierDetails> filteredClassifiers = db.getClassifiers().stream()
                            .filter(classifier -> {
                                String namespace = (String) classifier.get(NAMESPACE);
                                String microserviceName = (String) classifier.get(MICROSERVICE_NAME);

                                return isAllowedByFilterCriteria(filterCriteria, namespace, microserviceName, type, configurational);
                            })
                            .map(c -> new ClassifierDetails(ClassifierType.NEW, null, null, new TreeMap<>(c)))
                            .toList();

                    if (filteredClassifiers.isEmpty()) {
                        return null;
                    }

                    return new DatabaseWithClassifiers(db, filteredClassifiers);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private boolean isAllowedByFilterCriteria(FilterCriteria filterCriteria, String namespace, String microserviceName, String type, boolean configurational) {
        if (filterCriteria == null)
            return true;

        boolean included = isEmpty(filterCriteria.getInclude())
                || filterCriteria.getInclude().stream().anyMatch(filter -> isMatches(filter, namespace, microserviceName, type, configurational));

        boolean notExcluded = isEmpty(filterCriteria.getExclude())
                || filterCriteria.getExclude().stream().noneMatch(ex -> isMatches(ex, namespace, microserviceName, type, configurational));

        return included && notExcluded;
    }

    protected Restore initializeFullRestoreStructure(
            Backup backup,
            RestoreRequest restoreRequest
    ) {
        // Apply ExternalDatabaseStrategy to external databases and filter by FilterCriteria
        List<RestoreExternalDatabase> filteredExternalDbs = validateAndFilterExternalDb(
                backup.getExternalDatabases(),
                restoreRequest.getExternalDatabaseStrategy(),
                restoreRequest.getFilterCriteria());

        // Filter internal database classifiers
        List<DatabaseWithClassifiers> filteredBackupDatabases = getAllDbByFilter(
                backup.getLogicalBackups().stream()
                        .flatMap(logicalBackup -> logicalBackup.getBackupDatabases().stream())
                        .toList(),
                restoreRequest.getFilterCriteria());

        if (filteredExternalDbs.isEmpty() && filteredBackupDatabases.isEmpty()) {
            log.warn("Databases that match filterCriteria during restore not found");
            throw new DbNotFoundException("Databases that match filterCriteria not found", Source.builder().build());
        }

        // Mapping classifiers
        List<RestoreExternalDatabase> mappedExternalDbs =
                executeMappingForExternalDb(filteredExternalDbs, restoreRequest.getMapping());
        List<DatabaseWithClassifiers> mappedBackupDatabases =
                applyMappingToBackupDatabases(filteredBackupDatabases, restoreRequest.getMapping());

        checkForCollision(mappedExternalDbs, mappedBackupDatabases);
        // Enrich classifiers
        List<DatabaseWithClassifiers> enrichedBackupClassifiers = enrichInternalDbClassifiers(mappedBackupDatabases);
        List<RestoreExternalDatabase> enrichedExternalDbs = enrichExternalDbClassifiers(mappedExternalDbs);

        // Group BackupDatabases by updated adapter and logicalBackupName
        Map<AdapterBackupKey, List<DatabaseWithClassifiers>> groupedByTypeAndAdapter =
                groupBackupDatabasesByLogicalBackupNameAndAdapter(enrichedBackupClassifiers);

        log.info("Initializing restore structure: restoreName={}, backupName={}",
                restoreRequest.getRestoreName(),
                backup.getName());

        // Build logicalRestores for each new adapter
        List<LogicalRestore> logicalRestores = groupedByTypeAndAdapter.entrySet().stream()
                .map(entry -> {
                    LogicalRestore logicalRestore = new LogicalRestore();
                    logicalRestore.setId(UUID.randomUUID());
                    logicalRestore.setType(entry.getValue().getFirst().backupDatabase().getLogicalBackup().getType());
                    logicalRestore.setAdapterId(entry.getKey().adapterId());

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
        restore.setExternalDatabases(enrichedExternalDbs);
        restore.setMapping(mapper.toMappingEntity(restoreRequest.getMapping()));
        restore.setFilterCriteria(mapper.toFilterCriteriaEntity(restoreRequest.getFilterCriteria()));

        // set up relation
        logicalRestores.forEach(lr -> lr.setRestore(restore));
        enrichedExternalDbs.forEach(db -> db.setRestore(restore));
        int totalDatabases = logicalRestores.stream()
                .mapToInt(lr -> lr.getRestoreDatabases().size())
                .sum();

        log.info("Restore structure initialized: restoreName={}, logicalRestores={}, restoreDatabases={}, externalDatabases={}",
                restore.getName(), logicalRestores.size(), totalDatabases, enrichedExternalDbs.size());

        return restore;
    }

    private List<DatabaseWithClassifiers> applyMappingToBackupDatabases(List<DatabaseWithClassifiers> backupDatabases, Mapping mapping) {
        return backupDatabases.stream()
                .map(db ->
                        new DatabaseWithClassifiers(db.backupDatabase(), applyMapping(db.classifiers(), mapping))
                ).toList();
    }

    protected List<ClassifierDetails> applyMapping(List<ClassifierDetails> classifiers, Mapping mapping) {
        for (ClassifierDetails classifier : classifiers) {
            SortedMap<String, Object> sourceClassifier = classifier.getClassifierBeforeMapper();
            SortedMap<String, Object> mappedClassifier = new TreeMap<>(sourceClassifier);
            if (mapping != null) {
                String targetNamespace = applyMapping(mapping.getNamespaces(), (String) sourceClassifier.get(NAMESPACE));
                String targetTenant = (String) sourceClassifier.get(TENANT_ID);
                mappedClassifier.put(NAMESPACE, targetNamespace);

                if (targetTenant != null) {
                    targetTenant = applyMapping(mapping.getTenants(), targetTenant);
                    mappedClassifier.put(TENANT_ID, targetTenant);
                }
            }
            classifier.setClassifier(mappedClassifier);
        }
        return classifiers;
    }

    private List<DatabaseWithClassifiers> enrichInternalDbClassifiers(List<DatabaseWithClassifiers> backupDatabases) {
        return backupDatabases.stream()
                .map(db -> {
                    String type = db.backupDatabase().getLogicalBackup().getType();
                    List<ClassifierDetails> updatedClassifiers = findSimilarDbByClassifier(db.classifiers(), type).stream().toList();
                    return new DatabaseWithClassifiers(db.backupDatabase(), updatedClassifiers);
                }).toList();
    }

    private List<RestoreExternalDatabase> enrichExternalDbClassifiers(List<RestoreExternalDatabase> externalDatabases) {
        List<RestoreExternalDatabase> enrichedExternalDbs = new ArrayList<>();

        for (RestoreExternalDatabase db : externalDatabases) {
            List<ClassifierDetails> classifiers = findSimilarDbByClassifier(db.getClassifiers(), db.getType()).stream().toList();
            db.setClassifiers(classifiers);
            enrichedExternalDbs.add(db);
        }

        return enrichedExternalDbs;
    }

    private void checkForCollision(List<RestoreExternalDatabase> externalDatabases, List<DatabaseWithClassifiers> backupDatabases) {
        Set<SortedMap<String, Object>> uniqueClassifiers = new HashSet<>();
        List<ClassifierDetails> duplicateClassifiers = new ArrayList<>();

        Stream.concat(
                externalDatabases.stream().flatMap(ext -> ext.getClassifiers().stream()),
                backupDatabases.stream().flatMap(db -> db.classifiers().stream())
        ).forEach(classifier -> {
            SortedMap<String, Object> c = classifier.getClassifier();
            if (!uniqueClassifiers.add(c)) {
                duplicateClassifiers.add(classifier);
            }
        });

        if (!duplicateClassifiers.isEmpty()) {
            String msg = String.format(
                    "Duplicate classifiers detected after mapping. Duplicate classifiers=%s. " +
                            "Ensure all classifiers remain unique after mapping.", duplicateClassifiers);
            log.error(msg);
            throw new IllegalResourceStateException(msg, Source.builder().build());
        }
    }

    protected List<RestoreExternalDatabase> validateAndFilterExternalDb(
            List<BackupExternalDatabase> externalDatabases,
            ExternalDatabaseStrategy strategy,
            FilterCriteria filterCriteria
    ) {
        if (isEmpty(externalDatabases))
            return List.of();

        String externalNames = extractExternalDbName(externalDatabases, BackupExternalDatabase::getName);

        return switch (strategy) {
            case FAIL -> {
                log.error("External databases not allowed by strategy={}. External db names: [{}]",
                        ExternalDatabaseStrategy.FAIL, externalNames);
                throw new DatabaseBackupRestoreNotSupportedException(
                        String.format(
                                "External databases not allowed by strategy=%s. External db names: [%s]",
                                ExternalDatabaseStrategy.FAIL, externalNames
                        ),
                        Source.builder().parameter("ExternalDatabaseStrategy").build()
                );
            }
            case SKIP -> {
                log.info("Excluding external databases from restore by strategy={}. External db names: [{}]",
                        ExternalDatabaseStrategy.SKIP, externalNames);
                yield List.of();
            }
            case INCLUDE -> {
                List<RestoreExternalDatabase> restoreExternalDatabases = externalDatabases.stream()
                        .map(db -> {
                            String type = db.getType();
                            List<ClassifierDetails> filteredClassifiers = db.getClassifiers().stream()
                                    .filter(classifier -> {
                                        String namespace = (String) classifier.get(NAMESPACE);
                                        String microserviceName = (String) classifier.get(MICROSERVICE_NAME);
                                        return isAllowedByFilterCriteria(filterCriteria, namespace, microserviceName, type, false);
                                    })
                                    .map(c -> new ClassifierDetails(ClassifierType.NEW, null, null, new TreeMap<>(c)))
                                    .toList();

                            if (filteredClassifiers.isEmpty()) {
                                return null;
                            }

                            return mapper.toRestoreExternalDatabase(db, filteredClassifiers);
                        })
                        .filter(Objects::nonNull)
                        .toList();

                log.info("Including external databases to restore by strategy={}. External db names: [{}]",
                        ExternalDatabaseStrategy.INCLUDE, extractExternalDbName(restoreExternalDatabases, RestoreExternalDatabase::getName));
                yield restoreExternalDatabases;
            }
        };
    }

    private <T> String extractExternalDbName(List<T> externalDatabases, Function<T, String> d) {
        return externalDatabases.stream()
                .map(d)
                .collect(Collectors.joining(", "));
    }


    private List<RestoreExternalDatabase> executeMappingForExternalDb(
            List<RestoreExternalDatabase> externalDatabases,
            Mapping mapping
    ) {
        List<RestoreExternalDatabase> updatedExternals = new ArrayList<>();

        for (RestoreExternalDatabase externalDatabase : externalDatabases) {
            List<ClassifierDetails> mappedClassifiers = applyMapping(externalDatabase.getClassifiers(), mapping);
            externalDatabase.setClassifiers(mappedClassifiers);
            updatedExternals.add(externalDatabase);
        }
        return updatedExternals;
    }

    private List<RestoreDatabase> createRestoreDatabases(
            List<DatabaseWithClassifiers> backupDatabases
    ) {
        return backupDatabases.stream()
                .map(delegatedBackupDatabase -> {
                    BackupDatabase backupDatabase = delegatedBackupDatabase.backupDatabase();
                    List<ClassifierDetails> classifiers = delegatedBackupDatabase.classifiers();
                    String namespace = (String) classifiers.getFirst().getClassifier().get(NAMESPACE);
                    String bgVersion = null;
                    if (backupDatabase.isConfigurational()) {
                        Optional<BgNamespace> bgNamespace = bgNamespaceRepository.findBgNamespaceByNamespace(namespace);
                        if (bgNamespace.isPresent())
                            bgVersion = bgNamespace.get().getVersion();
                    }

                    List<RestoreDatabase.User> users = backupDatabase.getUsers().stream()
                            .map(u -> new RestoreDatabase.User(null, u.getRole()))
                            .toList();

                    RestoreDatabase restoreDatabase = new RestoreDatabase();
                    restoreDatabase.setId(UUID.randomUUID());
                    restoreDatabase.setBackupDatabase(backupDatabase);
                    restoreDatabase.setName(backupDatabase.getName());
                    restoreDatabase.setClassifiers(classifiers);
                    restoreDatabase.setSettings(backupDatabase.getSettings());
                    restoreDatabase.setUsers(users);
                    restoreDatabase.setBgVersion(bgVersion);

                    return restoreDatabase;
                })
                .toList();
    }

    private String applyMapping(Map<String, String> map, String oldValue) {
        if (map == null || map.isEmpty()) {
            return oldValue;
        }
        return map.getOrDefault(oldValue, oldValue);
    }

    private Map<AdapterBackupKey, List<DatabaseWithClassifiers>> groupBackupDatabasesByLogicalBackupNameAndAdapter(
            List<DatabaseWithClassifiers> backupDatabases
    ) {
        return backupDatabases.stream()
                .map(this::mapToAdapterBackupKeyEntry)
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                ));
    }

    private Map.Entry<AdapterBackupKey, DatabaseWithClassifiers> mapToAdapterBackupKeyEntry(
            DatabaseWithClassifiers backupDatabaseDelegate
    ) {
        List<ClassifierDetails> classifiers = backupDatabaseDelegate.classifiers();
        String type = backupDatabaseDelegate.backupDatabase().getLogicalBackup().getType();
        String logicalBackupName = backupDatabaseDelegate.backupDatabase().getLogicalBackup().getLogicalBackupName();

        SortedMap<String, Object> firstNewClassifier = classifiers.getFirst().getClassifier();
        String targetNamespace = (String) firstNewClassifier.get(NAMESPACE);
        String microserviceName = (String) firstNewClassifier.get(MICROSERVICE_NAME);
        PhysicalDatabase physicalDatabase = balancingRulesService
                .applyBalancingRules(type, targetNamespace, microserviceName);
        String adapterId = physicalDatabase.getAdapter().getAdapterId();

        // Checking adapter support backup restore
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);
        if (isBackupRestoreUnsupported(adapter)) {
            throw new DatabaseBackupRestoreNotSupportedException(
                    String.format("Adapter %s does not support restore operation", adapterId),
                    Source.builder().build());
        }

        return Map.entry(new AdapterBackupKey(adapterId, logicalBackupName),
                new DatabaseWithClassifiers(backupDatabaseDelegate.backupDatabase(), classifiers));
    }

    protected void startRestore(Restore restore, boolean dryRun) {
        List<LogicalRestore> logicalRestores = restore.getLogicalRestores();
        log.info("Starting requesting adapters to restore startup process: restore={}, dryRun={}, logicalRestoreCount={}",
                restore.getName(), dryRun, logicalRestores.size());
        String storageName = restore.getStorageName();
        String blobPath = restore.getBlobPath();

        List<CompletableFuture<Void>> futures = logicalRestores.stream()
                .map(logicalRestore ->
                        runLogicalRestoreAsync(logicalRestore, storageName, blobPath, dryRun)
                )
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private CompletableFuture<Void> runLogicalRestoreAsync(LogicalRestore logicalRestore,
                                                           String storageName,
                                                           String blobPath,
                                                           boolean dryRun
    ) {
        return CompletableFuture.supplyAsync(
                        asyncOperations.wrapWithContext(
                                () -> logicalRestore(
                                        logicalRestore,
                                        storageName,
                                        blobPath,
                                        dryRun
                                )
                        ),
                        asyncOperations.getBackupPool()
                )
                .thenAccept(response ->
                        refreshLogicalRestoreState(logicalRestore, response))
                .exceptionally(throwable -> {
                    if (is4xxError(throwable)) {
                        logicalRestore.setStatus(RestoreTaskStatus.FAILED);
                    } else {
                        logicalRestore.setStatus(RestoreTaskStatus.RETRYABLE_FAIL);
                    }

                    logicalRestore.setErrorMessage(extractErrorMessage(throwable));
                    log.error("Logical restore failed: logicalRestoreId={}, adapterId={}, error={}",
                            logicalRestore.getId(), logicalRestore.getAdapterId(), logicalRestore.getErrorMessage());
                    return null;
                });
    }

    private boolean is4xxError(Throwable throwable) {
        Throwable cause = throwable;

        while (cause != null) {
            if (cause instanceof WebApplicationException ex) {
                Response res = ex.getResponse();
                return 400 <= res.getStatus() && res.getStatus() < 500;
            }
            cause = cause.getCause();
        }
        return false;
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
                    log.debug("For restore={} restoreDatabase updated: old name={}, new name={}",
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

    private LogicalRestoreAdapterResponse logicalRestore(LogicalRestore logicalRestore,
                                                         String storageName,
                                                         String blobPath,
                                                         boolean dryRun
    ) {
        List<RestoreDatabase> restoreDatabases = logicalRestore.getRestoreDatabases();
        String logicalBackupName = restoreDatabases
                .getFirst()
                .getBackupDatabase()
                .getLogicalBackup()
                .getLogicalBackupName();

        List<Map<String, String>> databases = buildRestoreDatabases(restoreDatabases);
        RetryPolicy<Object> retryPolicy = buildRetryPolicy(RESTORE_OPERATION, LOGICAL_RESTORE, logicalRestore.getId().toString(), logicalRestore.getAdapterId());

        return Failsafe.with(retryPolicy)
                .get(() -> executeRestore(logicalRestore, logicalBackupName, storageName, blobPath, databases, dryRun));
    }

    private List<Map<String, String>> buildRestoreDatabases(List<RestoreDatabase> restoreDatabases) {
        return restoreDatabases.stream()
                .map(restoreDatabase -> {
                    String namespace = restoreDatabase.getClassifiers().stream()
                            .map(c -> (String) c.getClassifier().get(NAMESPACE))
                            .findFirst()
                            .orElse("");

                    String microserviceName = restoreDatabase.getClassifiers().stream()
                            .map(c -> (String) c.getClassifier().get(MICROSERVICE_NAME))
                            .findFirst()
                            .orElse("");

                    return Map.of(
                            MICROSERVICE_NAME, microserviceName,
                            DATABASE_NAME, restoreDatabase.getBackupDatabase().getName(),
                            NAMESPACE, namespace
                    );
                })
                .toList();
    }

    private LogicalRestoreAdapterResponse executeRestore(
            LogicalRestore logicalRestore,
            String logicalBackupName,
            String storageName,
            String blobPath,
            List<Map<String, String>> databases,
            boolean dryRun
    ) {
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalRestore.getAdapterId());

        LogicalRestoreAdapterResponse result = adapter.restoreV2(
                logicalBackupName,
                dryRun,
                new RestoreAdapterRequest(storageName, blobPath, databases)
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
        List<Restore> restoresToAggregate = restoreRepository.findRestoresToTrack();

        log.info("Found restores to aggregate {}",
                restoresToAggregate.stream().map(Restore::getName).toList());

        restoresToAggregate.forEach(restore -> {
            trackAndAggregateRestore(restore);

            if (RestoreStatus.COMPLETED != restore.getStatus()) {
                restoreRepository.save(restore);
                return;
            }

            createLogicalDatabases(restore);
        });
    }

    private void createLogicalDatabases(Restore restore) {
        try {
            Map<String, List<EnsuredUser>> dbNameToEnsuredUsers = restore.getLogicalRestores().stream()
                    .flatMap(lr -> lr.getRestoreDatabases().stream()
                            .map(rd -> Map.entry(
                                    rd.getName(),
                                    ensureUsers(lr.getAdapterId(), rd.getId().toString(), rd.getName(), rd.getUsers())
                            ))
                    )
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

            createLogicalDatabasesFromRestore(restore, dbNameToEnsuredUsers);
            restoreRepository.save(restore);
        } catch (Exception e) {
            log.error("Exception occurred during restore process", e);
            restore.setStatus(RestoreStatus.FAILED);
            restore.setErrorMessage(extractErrorMessage(e));
            restoreRepository.save(restore);
            throw e;
        }
    }

    protected List<EnsuredUser> ensureUsers(String adapterId,
                                            String dbId,
                                            String dbName,
                                            List<RestoreDatabase.User> users) {
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);
        RetryPolicy<Object> retryPolicy = buildRetryPolicy(ENSURE_USER_OPERATION, RESTORE_DATABASE, dbId, adapterId);

        log.info("Start ensuring {} users for database=[{}] via adapter [{}]",
                users.size(), dbId, adapterId);

        return users.stream()
                .map(user -> {
                    try {
                        return Failsafe.with(retryPolicy)
                                .get(() -> {
                                    EnsuredUser ensuredUser = adapter.ensureUser(null, null, dbName, user.getRole());
                                    user.setName(ensuredUser.getName());
                                    log.info("User ensured for database=[{}], user=[name:{}, connectionProperties:{}]",
                                            dbId, ensuredUser.getName(), ensuredUser.getConnectionProperties());
                                    return ensuredUser;
                                });
                    } catch (Exception e) {
                        log.error("Failed to ensure user in database [{}] with role [{}]", dbId, user.getRole());
                        throw new BackupExecutionException(
                                String.format("Failed to ensure user in database [%s] with role [%s]", dbId, user.getRole()), e);
                    }
                })
                .toList();
    }


    protected void trackAndAggregateRestore(Restore restore) {
        if (restore.getAttemptCount() > retryCount) {
            log.warn("The number of attempts to track restore {} exceeded {}", restore.getName(), retryCount);
            restore.setStatus(RestoreStatus.FAILED);
            restore.setErrorMessage(String.format("The number of attempts exceeded %s", retryCount));
        } else {
            fetchStatuses(restore);
            aggregateRestoreStatus(restore);
            restore.incrementAttempt();
        }
    }


    private void fetchStatuses(Restore restore) {
        List<LogicalRestore> notFinishedLogicalRestores = restore.getLogicalRestores().stream()
                .filter(db ->
                        db.getStatus() == RestoreTaskStatus.IN_PROGRESS
                                || db.getStatus() == NOT_STARTED
                                || db.getStatus() == RestoreTaskStatus.RETRYABLE_FAIL
                ).toList();
        log.debug("Starting checking status for logical restores: restore={}, logicalRestores={}",
                restore.getName(),
                notFinishedLogicalRestores.stream()
                        .map(LogicalRestore::getId)
                        .toList());

        List<CompletableFuture<Void>> futures = notFinishedLogicalRestores.stream()
                .map(logicalRestore -> {
                    RetryPolicy<Object> retryPolicy = buildRetryPolicy(TRACK_RESTORE_OPERATION, LOGICAL_RESTORE, logicalRestore.getId().toString(), logicalRestore.getAdapterId());
                    return CompletableFuture.supplyAsync(
                                    asyncOperations.wrapWithContext(
                                            () -> Failsafe.with(retryPolicy).get(() -> {
                                                        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalRestore.getAdapterId());
                                                        if (isLogicalRestoreNotStarted(logicalRestore)) {
                                                            LogicalRestoreAdapterResponse response = logicalRestore(logicalRestore, restore.getStorageName(), restore.getBlobPath(), false);
                                                            refreshLogicalRestoreState(logicalRestore, response);
                                                        }
                                                        return adapter.trackRestoreV2(logicalRestore.getLogicalRestoreName(), restore.getStorageName(), restore.getBlobPath());
                                                    }
                                            )
                                    ), asyncOperations.getBackupPool())
                            .thenAccept(response ->
                                    refreshLogicalRestoreState(logicalRestore, response))
                            .exceptionally(throwable -> {
                                if (is4xxError(throwable)) {
                                    logicalRestore.setStatus(RestoreTaskStatus.FAILED);
                                } else {
                                    logicalRestore.setStatus(RestoreTaskStatus.RETRYABLE_FAIL);
                                }

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

        int totalDbCount = restore.getExternalDatabases() == null ? 0 : restore.getExternalDatabases().size();
        int completedDbCount = restore.getExternalDatabases() == null ? 0 : restore.getExternalDatabases().size();
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
                completedDbCount += RestoreTaskStatus.COMPLETED == restoreDatabase.getStatus() ? 1 : 0;
            }
        }

        restore.setStatus(aggregateRestoreTaskStatus(statusSet));
        restore.setTotal(totalDbCount);
        restore.setCompleted(completedDbCount);
        restore.setErrorMessage(String.join("; ", errorMessages));
        log.info("Aggregated restore status: restoreName={}, status={}, totalDb={}, completed={}, errorMessage={}",
                restore.getName(), restore.getStatus(), restore.getTotal(), restore.getCompleted(), restore.getErrorMessage());
    }

    @Transactional
    protected void createLogicalDatabasesFromRestore(Restore restore,
                                                     Map<String, List<EnsuredUser>> dbNameToEnsuredUsers) {
        log.info("Start creating logical databases from restore {}", restore.getName());
        // Creating LogicalDb based logicalRestores
        restore.getLogicalRestores().forEach(logicalRestore -> {
            log.info("Processing logicalRestore={}, type={}, adapterId={}", logicalRestore.getLogicalRestoreName(), logicalRestore.getType(), logicalRestore.getAdapterId());
            logicalRestore.getRestoreDatabases().forEach(restoreDatabase -> {
                String type = logicalRestore.getType();
                log.info("Processing restoreDatabase={}", restoreDatabase.getName());
                findAndMarkDatabaseAsOrphan(restoreDatabase.getClassifiers(), type);
                String adapterId = logicalRestore.getAdapterId();
                String physicalDatabaseId = physicalDatabasesService.getByAdapterId(adapterId).getPhysicalDatabaseIdentifier();
                List<EnsuredUser> ensuredUsers = dbNameToEnsuredUsers.get(restoreDatabase.getName());
                Database newDatabase = createLogicalDatabase(
                        restoreDatabase.getName(),
                        restoreDatabase.getSettings(),
                        restoreDatabase.getClassifiers().stream()
                                .map(ClassifierDetails::getClassifier).collect(Collectors.toSet()),
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
                log.info("Based on restoreDatabase={}, database with id={} created", restoreDatabase.getName(), newDatabase.getId());
            });
        });
        // Creating LogicalDb based externalDbs
        restore.getExternalDatabases().forEach(externalDatabase -> {
            log.info("Processing externalDatabase={}, type={}", externalDatabase.getName(), externalDatabase.getType());
            String type = externalDatabase.getType();
            findAndMarkDatabaseAsOrphan(externalDatabase.getClassifiers(), type);
            Database newDatabase = createLogicalDatabase(
                    externalDatabase.getName(),
                    null,
                    externalDatabase.getClassifiers().stream()
                            .map(ClassifierDetails::getClassifier).collect(Collectors.toSet()),
                    type,
                    true,
                    true,
                    null,
                    null,
                    null);
            databaseRegistryDbaasRepository.saveExternalDatabase(newDatabase.getDatabaseRegistry().getFirst());
            log.info("Based on externalDb={}, database with id={} created", externalDatabase.getName(), newDatabase.getId());
        });
        restore.setStatus(RestoreStatus.COMPLETED);
        log.info("Finished initializing logical databases from restore {}", restore.getName());
    }

    private Set<ClassifierDetails> findSimilarDbByClassifier(List<ClassifierDetails> classifiers, String type) {
        Set<ClassifierDetails> result = new HashSet<>();
        Map<UUID, Database> databases = new HashMap<>();
        Set<SortedMap<String, Object>> foundClassifier = new HashSet<>();

        for (ClassifierDetails classifierDetails : classifiers) {
            SortedMap<String, Object> currClassifier = classifierDetails.getClassifier();

            Optional<DatabaseRegistry> opt = databaseRegistryDbaasRepository
                    .getDatabaseByClassifierAndType(currClassifier, type);

            if (opt.isEmpty()) {
                result.add(classifierDetails); // NEW
                continue;
            }

            DatabaseRegistry databaseRegistry = opt.get();

            classifierDetails.setType(ClassifierType.REPLACED);
            classifierDetails.setPreviousDatabase(databaseRegistry.getName());

            result.add(classifierDetails);
            foundClassifier.add(classifierDetails.getClassifier());

            Database database = databaseRegistry.getDatabase();

            if (!databases.containsKey(database.getId())) {
                databases.put(database.getId(), database);
            }
        }


        for (Database database : databases.values()) {
            for (DatabaseRegistry databaseRegistry : database.getDatabaseRegistry()) {
                if (!foundClassifier.contains(databaseRegistry.getClassifier())) {
                    result.add(
                            new ClassifierDetails(ClassifierType.TRANSIENT_REPLACED, database.getName(), new TreeMap<>(databaseRegistry.getClassifier()), null)
                    );
                }
            }
        }
        return result;
    }

    private void findAndMarkDatabaseAsOrphan(List<ClassifierDetails> classifiers, String type) {
        classifiers.stream()
                .filter(classifier -> ClassifierType.REPLACED == classifier.getType() ||
                        ClassifierType.TRANSIENT_REPLACED == classifier.getType()
                )
                .forEach(classifier -> {
                    SortedMap<String, Object> currClassifier = classifier.getClassifier();
                    databaseRegistryDbaasRepository
                            .getDatabaseByClassifierAndType(currClassifier, type)
                            .ifPresentOrElse(dbRegistry -> {
                                        dBaaService.markDatabasesAsOrphan(dbRegistry);
                                        databaseRegistryDbaasRepository.saveAnyTypeLogDb(dbRegistry);
                                        log.info(
                                                "Database marked as orphan: dbId={}, dbType={}, classifier={}",
                                                dbRegistry.getDatabase().getId(),
                                                type,
                                                currClassifier
                                        );
                                    }, () -> log.debug("Database not found for classifier: dbType={}, classifierType={}, classifier={}",
                                            type,
                                            classifier.getType(),
                                            currClassifier)
                            );
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

    public RestoreResponse retryRestore(String restoreName, boolean allowParallel) {
        Restore restore = getRestoreOrThrowException(restoreName);

        if (RestoreStatus.FAILED != restore.getStatus()) {
            throw new UnprocessableEntityException(
                    restoreName,
                    String.format(
                            "has invalid status '%s'. Only %s restores can be processed.",
                            restore.getStatus(), RestoreStatus.FAILED
                    ),
                    Source.builder().build());
        }

        Restore retriedRestore = restoreLockWrapper(() -> {
            retryRestore(restore);
            aggregateRestoreStatus(restore);
            return restoreRepository.save(restore);
        }, allowParallel);

        return mapper.toRestoreResponse(retriedRestore);
    }

    private void checkBackupStatusForRestore(String restoreName, BackupStatus status) {
        if (status != BackupStatus.COMPLETED) {
            log.error("Restore {} can't be processed due to backup status {}", restoreName, status);
            throw new UnprocessableEntityException(
                    restoreName, String.format("Restore can't be processed due to backup status %s", status),
                    Source.builder().build());
        }
    }

    private void retryRestore(Restore restore) {
        restore.resetAttempt();
        restore.setStatus(RestoreStatus.IN_PROGRESS);
        restore.getLogicalRestores().stream()
                .filter(logicalRestore ->
                        RestoreTaskStatus.FAILED == logicalRestore.getStatus()
                                || logicalRestore.getLogicalRestoreName() == null
                                || logicalRestore.getLogicalRestoreName().isEmpty()
                )
                .forEach(logicalRestore -> {
                    logicalRestore.setStatus(RestoreTaskStatus.RETRYABLE_FAIL);
                    logicalRestore.setLogicalRestoreName(null);
                });
    }

    protected Map<Database, List<DatabaseRegistry>> validateAndFilterDatabasesForBackup(
            Map<Database, List<DatabaseRegistry>> databasesForBackup,
            boolean ignoreNotBackupableDatabases,
            ExternalDatabaseStrategy strategy) {

        Map<Boolean, Map<Database, List<DatabaseRegistry>>> partitioned = databasesForBackup.entrySet().stream()
                .collect(Collectors.groupingBy(entry ->
                                entry.getKey().isExternallyManageable(),
                        Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)
                ));

        Map<Database, List<DatabaseRegistry>> externalDatabases = partitioned.getOrDefault(true, Map.of());
        Map<Database, List<DatabaseRegistry>> internalDatabases = partitioned.getOrDefault(false, Map.of());

        if (!externalDatabases.isEmpty()) {
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
                        return isBackupRestoreUnsupported(physicalDatabasesService.getAdapterById(db.getAdapterId()));
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

    @Transactional
    public void deleteBackupFromDb(String backupName) {
        backupRepository.deleteById(backupName);
    }

    private boolean isBackupRestoreUnsupported(DbaasAdapter adapter) {
        return !adapter.isBackupRestoreSupported();
    }

    private void backupExistenceCheck(String backupName) {
        if (backupRepository.findByIdOptional(backupName).isPresent()) {
            throwBackupAlreadyExistsException(backupName);
        }
    }

    private void throwBackupAlreadyExistsException(String backupName) {
        log.error("Backup with name {} already exists", backupName);
        throw new ResourceAlreadyExistsException(backupName, Source.builder().build());
    }

    private Backup getBackupOrThrowException(String backupName) {
        return backupRepository.findByIdOptional(backupName)
                .orElseThrow(() -> new BackupNotFoundException(backupName, Source.builder().build()));
    }

    private Restore getRestoreOrThrowException(String restoreName) {
        return restoreRepository.findByIdOptional(restoreName)
                .orElseThrow(() -> new BackupRestorationNotFoundException(restoreName, Source.builder().build()));
    }

    private <T> T restoreLockWrapper(Supplier<T> action, boolean allowParallel) {
        if (allowParallel)
            return action.get();

        // Only one restore operation able to process
        LockConfiguration config = new LockConfiguration(
                Instant.now(),
                RESTORE,
                Duration.ofMinutes(LOCK_AT_MOST),
                Duration.ofMinutes(LOCK_AT_LEAST));

        Optional<SimpleLock> optLock = lockProvider.lock(config);

        if (optLock.isEmpty())
            throw new OperationAlreadyRunningException("restore");
        // Start locking action
        SimpleLock lock = optLock.get();
        try {
            if (restoreRepository.countNotCompletedRestores() > 0)
                throw new OperationAlreadyRunningException("restore");
            return action.get();
        } finally {
            try {
                lock.unlock();
            } catch (IllegalStateException ex) {
                log.debug("Lock is already unlocked", ex);
            }
        }
    }

    private RetryPolicy<Object> buildRetryPolicy(String operation, String entityType, String entityId, String adapterId) {
        String context = String.format("%s operation [%s=%s, adapter=%s]", operation, entityType, entityId, adapterId);
        return new RetryPolicy<>()
                .handle(WebApplicationException.class)
                .withMaxRetries(retryAttempts)
                .withDelay(retryDelay)
                .onFailedAttempt(e -> log.warn("Attempt failed for {}: {}",
                        context, extractErrorMessage(e.getLastFailure())))
                .onRetry(e -> log.info("Retrying {}...", context))
                .onFailure(e -> log.error("Request limit exceeded for {}", context));
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

    private boolean isEmpty(Collection<?> c) {
        return c == null || c.isEmpty();
    }

    private boolean isLogicalBackupNotStarted(LogicalBackup logicalBackup) {
        return logicalBackup.getLogicalBackupName() == null || logicalBackup.getLogicalBackupName().isBlank();
    }

    private boolean isLogicalRestoreNotStarted(LogicalRestore logicalRestore) {
        return logicalRestore.getLogicalRestoreName() == null || logicalRestore.getLogicalRestoreName().isBlank();
    }

    protected BackupStatus aggregateBackupTaskStatus(Set<BackupTaskStatus> backupTaskStatuses) {
        if (backupTaskStatuses.contains(BackupTaskStatus.NOT_STARTED) ||
                backupTaskStatuses.contains(BackupTaskStatus.RETRYABLE_FAIL) ||
                backupTaskStatuses.contains(BackupTaskStatus.IN_PROGRESS))
            return BackupStatus.IN_PROGRESS;

        if (backupTaskStatuses.contains(BackupTaskStatus.FAILED))
            return BackupStatus.FAILED;

        return BackupStatus.COMPLETED;
    }

    protected RestoreStatus aggregateRestoreTaskStatus(Set<RestoreTaskStatus> backupTaskStatuses) {
        if (backupTaskStatuses.contains(RestoreTaskStatus.NOT_STARTED) ||
                backupTaskStatuses.contains(RestoreTaskStatus.RETRYABLE_FAIL) ||
                backupTaskStatuses.contains(RestoreTaskStatus.IN_PROGRESS))
            return RestoreStatus.IN_PROGRESS;

        if (backupTaskStatuses.contains(RestoreTaskStatus.FAILED))
            return RestoreStatus.FAILED;

        return RestoreStatus.COMPLETED;
    }

}
