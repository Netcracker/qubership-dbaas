package org.qubership.cloud.dbaas.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.dto.backupV2.BackupMetadataResponse;
import org.qubership.cloud.dbaas.dto.backupV2.BackupRequest;
import org.qubership.cloud.dbaas.dto.backupV2.BackupStatusResponse;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.backupV2.*;
import org.qubership.cloud.dbaas.entity.shared.AbstractDatabase;
import org.qubership.cloud.dbaas.entity.shared.AbstractDatabaseRegistry;
import org.qubership.cloud.dbaas.exceptions.BackupExecutionException;
import org.qubership.cloud.dbaas.exceptions.BackupNotFoundException;
import org.qubership.cloud.dbaas.exceptions.DBBackupValidationException;
import org.qubership.cloud.dbaas.mapper.BackupV2Mapper;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BackupRepository;
import org.qubership.cloud.dbaas.utils.DbaasBackupUtils;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
@AllArgsConstructor
public class DbBackupV2Service {

    private final static RetryPolicy<Object> OPERATION_STATUS_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(2).withDelay(Duration.ofSeconds(1));
    protected static int TRACK_DELAY_MS = 3000;

    private final BackupRepository backupRepository;
    private final PhysicalDatabasesService physicalDatabasesService;
    private final DatabaseDbaasRepository databaseDbaasRepository;
    private final BackupV2Mapper mapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(20);


    public void backup(BackupRequest backupRequest) {
        String backupName = backupRequest.getBackupName();
        String namespace = backupRequest.getFilterCriteria().getFilter().getFirst().getNamespace().getFirst();

        log.info("Start backup processing for backup: {}", backupName);

        List<Database> databasesForBackup = getAllDbByNamespace(namespace);
        if (databasesForBackup.isEmpty()) {
            log.warn("Namespace {} doesn't contain any databases for backup", namespace);
            throw new BackupExecutionException(URI.create("path"),
                    String.format("Namespace %s doesn't contain any databases for backup", namespace),
                    null); //TODO fill correct path
        }
        if (backupRepository.findByIdOptional(backupName).isPresent()) {
            log.error("Backup with name {} already exists", backupName);
            throw new DBBackupValidationException(Source.builder().build(),
                    String.format("Backup with name %s already exists", backupName));
        }
        Backup backup = initializeFullBackupStructure(databasesForBackup, backupRequest);
        startBackup(backup);
        waitBackupCompletion(backup);
    }

    protected Backup initializeFullBackupStructure(List<Database> databasesForBackup, BackupRequest backupRequest) {
        Backup backup = new Backup(backupRequest.getBackupName(), "", "", backupRequest.getExternalDatabaseStrategy(), null);

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
        updateAggregatedStatus(backup);
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

        logicalBackups.forEach(logicalBackup -> {
            Future<String> future = executor.submit(startLogicalBackup(logicalBackup));
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

    protected Callable<String> startLogicalBackup(LogicalBackup logicalBackup) {
        List<String> dbNames = logicalBackup.getStatus().getDatabases().stream()
                .map(LogicalBackupStatus.Database::getDatabaseName)
                .toList();
        String adapterId = logicalBackup.getAdapterId();
        return () -> {
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
        };
    }

    protected void waitBackupCompletion(Backup backup) {
        log.info("Start tracking backup for: {}", backup);
        int maxAttempts = 25;
        int currAttempt = 1;

        List<LogicalBackup> notFinishedLogicalBackups = backup.getLogicalBackups();

        while (!notFinishedLogicalBackups.isEmpty() && currAttempt <= maxAttempts) {
            try {
                Thread.sleep(TRACK_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Waiting between attempts is interrupted", e);
                break;
            }

            List<LogicalBackup> logicalBackupsToAggregate = fetchAndUpdateStatuses(notFinishedLogicalBackups);
            updateAggregatedStatus(backup);
            backupRepository.save(backup);

            notFinishedLogicalBackups = filterBackupTasks(logicalBackupsToAggregate);
            currAttempt++;
        }

        if (!notFinishedLogicalBackups.isEmpty()) {
            notFinishedLogicalBackups.forEach(logicalBackup -> {
                logicalBackup.getStatus().setStatus(Status.FAILED);
                logicalBackup.getStatus().setErrorMessage("Timeout");
            });
            updateAggregatedStatus(backup);
            backupRepository.save(backup);
        }
    }


    private List<LogicalBackup> filterBackupTasks(List<LogicalBackup> backupsToTrack) {
        return backupsToTrack.stream()
                .filter(backup -> {
                    Status status = backup.getStatus().getStatus();
                    return status == Status.NOT_STARTED || status == Status.IN_PROGRESS;
                })
                .toList();
    }

    private List<LogicalBackup> fetchAndUpdateStatuses(List<LogicalBackup> logicalBackupList) {
        Map<LogicalBackup, Future<LogicalBackupStatus>> statuses = new HashMap<>();

        logicalBackupList
                .forEach(logicalBackup -> {
                    Future<LogicalBackupStatus> future = executor.submit(() ->
                            Failsafe.with(OPERATION_STATUS_RETRY_POLICY).get(() -> {
                                        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(logicalBackup.getAdapterId());
                                        return adapter.trackBackupV2(logicalBackup.getLogicalBackupName());
                                    }
                            ));
                    statuses.put(logicalBackup, future);
                });

        return statuses.entrySet().stream().map(entry -> {
            LogicalBackup logicalBackup = entry.getKey();
            try {
                LogicalBackupStatus logicalBackupStatus = entry.getValue().get();
                logicalBackup.setStatus(logicalBackupStatus);
            } catch (InterruptedException | ExecutionException e) {
                logicalBackup.getStatus().setStatus(Status.FAILED);
                logicalBackup.getStatus().setErrorMessage(e.getCause().getMessage());
            }
            return logicalBackup;
        }).toList();
    }

    protected void updateAggregatedStatus(Backup backup) {
        log.info("Start aggregating for backupName: {}", backup.getName());

        List<LogicalBackup> logicalBackuplist = backup.getLogicalBackups();
        List<LogicalBackupStatus> logicalBackupStatusList = logicalBackuplist.stream()
                .map(LogicalBackup::getStatus)
                .toList();

        log.info("List of logicalBackupStatusList: {}", logicalBackupStatusList);

        int totalDbCount = logicalBackuplist.stream().mapToInt(lb -> lb.getBackupDatabases().size()).sum();

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
                .build());
        //TODO fill errorMsg of aggregated backupStatus
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

    public BackupStatusResponse getCurrentStatus(String backupName) {
        BackupStatus backupStatus = backupRepository.findByIdOptional(backupName)
                .orElseThrow(() -> new BackupNotFoundException(backupName, Source.builder().build()))
                .getStatus();
        return mapper.toBackupStatusResponse(backupStatus);
    }

    protected List<Database> getAllDbByNamespace(String namespace) {
        return databaseDbaasRepository.findAnyLogDbTypeByNamespace(namespace);
    }


    public BackupMetadataResponse getBackupMetadata(String backupName) {
        Backup backup = backupRepository.findByIdOptional(backupName)
                .orElseThrow(() -> new BackupNotFoundException(backupName, Source.builder().build()));

        return mapper.toBackupMetadataResponse(backup);
    }
}
