package org.qubership.cloud.dbaas.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.backupV2.*;
import org.qubership.cloud.dbaas.entity.shared.AbstractDatabase;
import org.qubership.cloud.dbaas.entity.shared.AbstractDatabaseRegistry;
import org.qubership.cloud.dbaas.exceptions.BackupExecutionException;
import org.qubership.cloud.dbaas.exceptions.DBBackupValidationException;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BackupDatabaseRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BackupRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.LogicalBackupRepository;
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

    private final BackupRepository backupRepository;
    private final LogicalBackupRepository logicalBackupRepository;
    private final PhysicalDatabasesService physicalDatabasesService;
    private final BackupDatabaseRepository backupDatabaseRepository;
    private final DatabaseDbaasRepository databaseDbaasRepository;
    private final ExecutorService executor = Executors.newFixedThreadPool(20);

    private final static RetryPolicy<Object> OPERATION_STATUS_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(2).withDelay(Duration.ofSeconds(1));

    @Transactional
    public void backup(String namespace, String backupName) {
        List<Database> databasesForBackup = getAllDbByNamespace(namespace);

        if (databasesForBackup.isEmpty()) {
            log.error("For the namespace: {}, not found any databases for backup", namespace);
            throw new BackupExecutionException(URI.create("path"),
                    "For the namespace: " + namespace + " , not found any databases for backup",
                    null); //TODO fill correct path
        }
        if (backupRepository.findByIdOptional(backupName).isPresent()) {
            log.error("Backup with name: {} already exist", backupName);
            throw new DBBackupValidationException(Source.builder().build(),
                    String.format("Backup with name: '%s' already exist", backupName));
        }

        Backup backup = initializeFullBackupStructure(databasesForBackup, backupName);
        startBackup(backup);
        waitBackupCompletion(backup);
    }

    @Transactional
    protected Backup initializeFullBackupStructure(List<Database> databasesForBackup, String backupName) {
        Backup backup = new Backup(backupName, "", "", "", null);

        List<LogicalBackup> logicalBackups = databasesForBackup.stream()
                .collect(Collectors.groupingBy(AbstractDatabase::getAdapterId))
                .entrySet()
                .stream()
                .map(entry -> {
                    String adapterId = entry.getKey();
                    List<Database> databases = entry.getValue();
                    DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);

                    LogicalBackup lb = LogicalBackup.builder()
                            .id(UUID.randomUUID())
                            .backup(backup)
                            .adapterId(adapterId)
                            .type(adapter.type())
                            .status(new LogicalBackupStatus())
                            .backupDatabases(new ArrayList<>())
                            .build();

                    lb.getBackupDatabases().addAll(databases.stream()
                            .map(db -> BackupDatabase.builder()
                                    .id(UUID.randomUUID())
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
        backupRepository.persist(backup);
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

    @Transactional
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
                        .status(Status.FAIL)
                        .errorMessage(e.getCause().getMessage())
                        .build();
                logicalbackup.setStatus(logicalBackupStatus);
            }
        });
        logicalBackupRepository.persist(logicalBackups);
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
                        throw new BackupExecutionException(URI.create("e"), "Empty result from backup", new Throwable());
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
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Waiting between attempts is interrupted", e);
                break;
            }

            List<LogicalBackup> logicalBackupsToAggregate = fetchAndUpdateStatuses(notFinishedLogicalBackups);
            updateAggregatedStatus(backup);
            backupRepository.persist(backup);

            notFinishedLogicalBackups = filterBackupTasks(logicalBackupsToAggregate);
            currAttempt++;
        }

        if (!notFinishedLogicalBackups.isEmpty()) {
            notFinishedLogicalBackups.forEach(logicalBackup -> {
                logicalBackup.getStatus().setStatus(Status.FAIL);
                logicalBackup.getStatus().setErrorMessage("Timeout");
            });
            updateAggregatedStatus(backup);
            backupRepository.persist(backup);
        }
    }


    private List<LogicalBackup> filterBackupTasks(List<LogicalBackup> backupsToTrack) {
        return backupsToTrack.stream()
                .filter(backup -> {
                    Status status = backup.getStatus().getStatus();
                    return status == Status.NOT_STARTED || status == Status.PROCEEDING;
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
                logicalBackup.getStatus().setStatus(Status.FAIL);
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

        Set<Status> statusSet = new HashSet<>();
        long totalBytes = 0;

        for (LogicalBackupStatus logicalBackupStatus : logicalBackupStatusList) {
            Status currStatus = logicalBackupStatus.getStatus();
            statusSet.add(currStatus);

            totalBytes += logicalBackupStatus.getDatabases().stream()
                    .filter(database -> database.getSize() != null)
                    .mapToLong(LogicalBackupStatus.Database::getSize)
                    .sum();

        }

        backup.setStatus(BackupStatus.builder()
                .status(aggregateStatus(statusSet))
                .size(totalBytes)
                .build());
        //TODO fill fully backupStatus.class
    }

    protected Status aggregateStatus(Set<Status> statusSet) {
        if (statusSet.contains(Status.NOT_STARTED) && statusSet.size() == 1)
            return Status.NOT_STARTED;
        else if (statusSet.contains(Status.NOT_STARTED) && statusSet.size() > 1)
            return Status.PROCEEDING;
        else if (statusSet.contains(Status.PROCEEDING))
            return Status.PROCEEDING;
        else if (statusSet.contains(Status.FAIL))
            return Status.FAIL;
        else
            return Status.SUCCESS;
    }

    private List<Database> getAllDbByNamespace(String namespace) {
        return databaseDbaasRepository.findAnyLogDbTypeByNamespace(namespace);
    }

}
