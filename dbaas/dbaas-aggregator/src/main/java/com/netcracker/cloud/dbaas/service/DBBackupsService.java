package com.netcracker.cloud.dbaas.service;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.dbaas.DatabaseType;
import com.netcracker.cloud.dbaas.dto.DescribedDatabase;
import com.netcracker.cloud.dbaas.dto.EnsuredUser;
import com.netcracker.cloud.dbaas.dto.Source;
import com.netcracker.cloud.dbaas.dto.backup.DeleteResult;
import com.netcracker.cloud.dbaas.dto.backup.NamespaceBackupDeletion;
import com.netcracker.cloud.dbaas.dto.backup.Status;
import com.netcracker.cloud.dbaas.dto.role.Role;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import com.netcracker.cloud.dbaas.entity.pg.DbState;
import com.netcracker.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import com.netcracker.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import com.netcracker.cloud.dbaas.entity.pg.backup.NamespaceRestoration;
import com.netcracker.cloud.dbaas.entity.pg.backup.RestoreResult;
import com.netcracker.cloud.dbaas.exceptions.*;
import com.netcracker.cloud.dbaas.repositories.dbaas.BackupsDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.encryption.cipher.exception.DecryptException;
import com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextObject;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.netcracker.cloud.dbaas.Constants.ROLE;
import static com.netcracker.cloud.dbaas.DbaasApiPath.VERSION_1;
import static com.netcracker.cloud.dbaas.entity.pg.backup.NamespaceBackup.Status.FAIL;
import static com.netcracker.cloud.dbaas.service.AbstractDbaasAdapterRESTClient.buildMetadata;
import static com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextObject.X_REQUEST_ID;

@Slf4j
@ApplicationScoped
public class DBBackupsService {
    private final PhysicalDatabasesService physicalDatabasesService;
    private final DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    private final BackupsDbaasRepository backupsDbaasRepository;
    private final PasswordEncryption encryption;
    private final EntityManager entityManager;
    private final DbaaSHelper dbaaSHelper;
    private final DeletionService deletionService;

    private final ExecutorService asyncExecutorService = Executors.newSingleThreadExecutor();

    public DBBackupsService(PhysicalDatabasesService physicalDatabasesService,
                            DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository, BackupsDbaasRepository backupsDbaasRepository,
                            PasswordEncryption encryption, EntityManager entityManager, DbaaSHelper dbaaSHelper, DeletionService deletionService) {
        this.physicalDatabasesService = physicalDatabasesService;
        this.databaseRegistryDbaasRepository = databaseRegistryDbaasRepository;
        this.backupsDbaasRepository = backupsDbaasRepository;
        this.encryption = encryption;
        this.entityManager = entityManager;
        this.dbaaSHelper = dbaaSHelper;
        this.deletionService = deletionService;
    }

    private Predicate<DatabaseRegistry> notMarkedForDrop() {
        return Predicate.not(DeletionService::isMarkedForDrop);
    }

    @Transactional
    public NamespaceBackupDeletion deleteBackup(NamespaceBackup backupToDelete) throws NamespaceBackupDeletionFailedException, IllegalArgumentException {
StructuredLog.info(log, "Start delete backup for id in namespace", "arg0", backupToDelete.getId(), "namespace", backupToDelete.getNamespace());
        if (log.isDebugEnabled()) {
StructuredLog.debug(log, "Backup information", "backupToDelete", backupToDelete);
        }

        NamespaceBackupDeletion result = new NamespaceBackupDeletion();
        List<DeleteResult> deleteResults = new ArrayList<>();

        var allDatabasesBackupsWithoutLocalId = backupToDelete.getBackups().stream()
                .allMatch(databasesBackup -> databasesBackup.getLocalId() == null);

        if (allDatabasesBackupsWithoutLocalId) {
            result.setStatus(Status.SUCCESS);
        } else {
            backupToDelete.setStatus(NamespaceBackup.Status.PROCEEDING);
            backupsDbaasRepository.save(backupToDelete);

            deleteResults = backupToDelete.getBackups().stream()
                    .filter(databasesBackup -> databasesBackup.getLocalId() != null)
                    .peek(this::backupValidation)
                    .map(backup -> {
                        String adapterId = backup.getAdapterId();
StructuredLog.info(log, "backup with adapter id has databases to delete", "adapterId", adapterId, "count", backup.getDatabases().size());
                        if (log.isDebugEnabled()) {
StructuredLog.debug(log, "Backup contains adapter", "adapter", physicalDatabasesService.getAdapterById(adapterId).toString());
                        }
                        return physicalDatabasesService.getAdapterById(adapterId).delete(backup);
                    }).collect(Collectors.toList());

            if (log.isDebugEnabled()) {
StructuredLog.debug(log, "Backup deletion result is", "deleteResults", deleteResults);
            }

            result.setStatus(deleteResults.stream()
                    .map(DeleteResult::getStatus)
                    .map(Status::ordinal)
                    .max(Integer::compareTo)
                    .map(it -> Status.values()[it])
                    .orElse(Status.FAIL));

            if (log.isDebugEnabled()) {
StructuredLog.debug(log, "Backup deletion result is", "result", result);
            }
        }

        result.setDeleteResults(deleteResults);

        if (result.getStatus() == Status.FAIL) {
            StructuredLog.error(log, "Not all backups was deleted successfully in namespace", "backup", backupToDelete.getBackups(), "namespace", backupToDelete.getNamespace());
            backupToDelete.setStatus(NamespaceBackup.Status.DELETION_FAILED);
            String failReason = "Expected all backups deleted successful, but got: " + stringifyFailedDeletion(deleteResults);
            if (log.isDebugEnabled()) {
StructuredLog.debug(log, "Fail reasons are", "failReason", failReason);
            }
            result.getFailReasons().add(failReason);
            long failedNumber = deleteResults
                    .stream()
                    .map(DeleteResult::getStatus)
                    .filter(it -> Status.FAIL == it).count();
            StructuredLog.error(log, "During deletion of backup failed subdeletion and fail reason:", "backup", backupToDelete.getId(), "failedNumber", failedNumber, "failReason", failReason);
            backupsDbaasRepository.save(backupToDelete);
            throw new NamespaceBackupDeletionFailedException(backupToDelete.getId(), failedNumber, backupToDelete);
        }
        backupsDbaasRepository.delete(backupToDelete);
StructuredLog.info(log, "Deletion of backup succeed", "arg0", backupToDelete.getId());
        return result;
    }

    @Transactional
    public void asyncDeleteAllNamespaceBackupsInNamespacesByPortions(@NotEmpty Set<String> namespaces, boolean forceRemoveNotDeletableBackups) {
StructuredLog.info(log, "Scheduling async deletion of namespace backups in namespaces", "namespaces", namespaces);

        var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();

        asyncExecutorService.submit(() -> {
            ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));

            deleteAllNamespaceBackupsInNamespacesByPortions(namespaces, forceRemoveNotDeletableBackups);
        });
    }

    protected void deleteAllNamespaceBackupsInNamespacesByPortions(Set<String> namespaces, boolean forceRemoveNotDeletableBackups) {
        if (dbaaSHelper.isProductionMode()) {

            log.warn("Skipped deletion of backups because it is not supported in production mode");
            return;
        }

        var allSuccessfullyDeletedNamespaceBackupIdsAmount = 0;
        var allSkippedDeletedNamespaceBackupIdsAmount = 0;
        var allFailedDeletedNamespaceBackupIdsAmount = 0;

StructuredLog.info(log, "Started deletion of all namespace backups in namespaces", "count", namespaces.size(), "namespaces", namespaces);

        if (!forceRemoveNotDeletableBackups) {

            var notDeletableNamespaceBackupIds = backupsDbaasRepository.findAllNotDeletableBackupIdsByNamespaces(namespaces);

            if (CollectionUtils.isNotEmpty(notDeletableNamespaceBackupIds)) {

                allSkippedDeletedNamespaceBackupIdsAmount = notDeletableNamespaceBackupIds.size();

                StructuredLog.warn(log, "Skipped deletion of namespace backups with ids because its can not be deleted", "namespace", notDeletableNamespaceBackupIds.size(), "notDeletableNamespaceBackupIds", notDeletableNamespaceBackupIds);
            }
        }

        var portionNumber = 0;
        List<NamespaceBackup> namespaceBackups;

        do {
            if (forceRemoveNotDeletableBackups) {
                namespaceBackups = backupsDbaasRepository.findByNamespacesWithOffsetBasedPagination(namespaces, allFailedDeletedNamespaceBackupIdsAmount, 100 + allFailedDeletedNamespaceBackupIdsAmount);
            } else {
                namespaceBackups = backupsDbaasRepository.findDeletableByNamespacesWithOffsetBasedPagination(namespaces, allFailedDeletedNamespaceBackupIdsAmount, 100 + allFailedDeletedNamespaceBackupIdsAmount);
            }

            if (CollectionUtils.isNotEmpty(namespaceBackups)) {
                portionNumber += 1;

                var namespaceBackupsIds = namespaceBackups.stream()
                        .map(NamespaceBackup::getId)
                        .toList();

                StructuredLog.info(log, "Started deletion of namespace backups by portion with number , namespace backup ids", "namespace", namespaceBackupsIds.size(), "portionNumber", portionNumber, "namespaceBackupsIds", namespaceBackupsIds);

                var failedDeleteNamespaceBackups = new ArrayList<NamespaceBackup>();

                for (var namespaceBackup : namespaceBackups) {
                    var namespaceBackupId = namespaceBackup.getId();

                    try {
StructuredLog.info(log, "Started deletion of namespace backup with id", "namespaceBackupId", namespaceBackupId);

                        deleteBackup(namespaceBackup);

StructuredLog.info(log, "Finished deletion of namespace backup with id", "namespaceBackupId", namespaceBackupId);
                    } catch (Exception ex) {
                        failedDeleteNamespaceBackups.add(namespaceBackup);

                        StructuredLog.error(log, "Error happened during deletion of namespace backup with id", "namespaceBackupId", namespaceBackupId);
                    }
                }

                var failedDeleteNamespaceBackupsIds = failedDeleteNamespaceBackups.stream()
                        .map(NamespaceBackup::getId)
                        .toList();

                var successfullyDeletedNamespaceBackupsIds = namespaceBackups.stream()
                        .map(NamespaceBackup::getId)
                        .filter(Predicate.not(failedDeleteNamespaceBackupsIds::contains))
                        .toList();

                allSuccessfullyDeletedNamespaceBackupIdsAmount += successfullyDeletedNamespaceBackupsIds.size();
                allFailedDeletedNamespaceBackupIdsAmount += failedDeleteNamespaceBackupsIds.size();

                StructuredLog.info(log, """ Finished deletion of namespace backups by portion with number , successfully deleted namespace backups with ids , \ failed deleted ones with ids """, "namespace", namespaceBackupsIds.size(), "portionNumber", portionNumber, "namespace", successfullyDeletedNamespaceBackupsIds.size(), "successfullyDeletedNamespaceBackupsIds", successfullyDeletedNamespaceBackupsIds, "namespace", failedDeleteNamespaceBackupsIds.size(), "failedDeleteNamespaceBackupsIds", failedDeleteNamespaceBackupsIds);
            }
        } while (CollectionUtils.isNotEmpty(namespaceBackups));

        var allNamespaceBackupsAmount = allSuccessfullyDeletedNamespaceBackupIdsAmount
                + allSkippedDeletedNamespaceBackupIdsAmount + allFailedDeletedNamespaceBackupIdsAmount;

        StructuredLog.info(log, """ Finished deletion of all namespace backups in namespaces , successfully deleted namespace backups, \ skipped deletion of ones, failed deleted ones""", "allNamespaceBackupsAmount", allNamespaceBackupsAmount, "namespace", namespaces.size(), "namespaces", namespaces, "allSuccessfullyDeletedNamespaceBackupIdsAmount", allSuccessfullyDeletedNamespaceBackupIdsAmount, "allSkippedDeletedNamespaceBackupIdsAmount", allSkippedDeletedNamespaceBackupIdsAmount, "allFailedDeletedNamespaceBackupIdsAmount", allFailedDeletedNamespaceBackupIdsAmount);
    }

    private void backupValidation(DatabasesBackup backup) throws MultiValidationException {
        List<ValidationException> errors = new ArrayList<>();
        String adapterId = backup.getAdapterId();
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);
StructuredLog.info(log, "Validating adapter with adapter id", "adapter", adapter, "adapterId", adapterId);
        if (adapter == null || !Objects.equals(adapter.identifier(), backup.getAdapterId())) {
            errors.add(new DBBackupValidationException(Source.builder().pointer("/adapter_id").build(),
                    String.format("Incorrect adapter identifier: '%s'", adapterId)));
        }
        if (!errors.isEmpty()) {
            throw new MultiValidationException(errors);
        }
    }

    private String stringifyFailedDeletion(Collection<DeleteResult> deleteResults) {
        return deleteResults == null || deleteResults.isEmpty() ? "no subdeletions" :
                ((Supplier<String>) () -> {
                    StringBuilder builder = new StringBuilder();
                    deleteResults.forEach(res -> {
                        appendFailed(builder, res.getStatus(), res.getAdapterId());
                    });
                    builder.append(" ] ");
                    return builder.toString();
                }).get();
    }

    public NamespaceBackup collectBackup(String namespace, UUID id, Boolean allowEviction) {
        try {
StructuredLog.info(log, "Start collect backup for id in namespace", "id", id, "namespace", namespace);
            List<DatabaseRegistry> databasesForBackup = getDatabasesForBackup(namespace);
            return getNamespaceBackup(namespace, id, allowEviction, databasesForBackup);
        } catch (Exception ex) {
StructuredLog.error(log, "Failed collecting backup for id in namespace", ex, "id", id, "namespace", namespace);
            throw ex;
        }
    }

    public NamespaceBackup collectBackupSingleDatabase(String namespace, UUID id, Boolean allowEviction, UUID databaseId) throws InteruptedPollingException {
StructuredLog.info(log, "Start collect backup for id in namespace with databaseId", "id", id, "namespace", namespace, "databaseId", databaseId);
        List<DatabaseRegistry> databasesForBackup = getDatabasesForBackup(databaseId);
        return getNamespaceBackup(namespace, id, allowEviction, databasesForBackup);
    }

    @NotNull
    private NamespaceBackup getNamespaceBackup(String namespace, UUID id, Boolean allowEviction, List<DatabaseRegistry> databasesForBackup) throws InteruptedPollingException {
        log.debug("Check adapters on backup operator");
        List<String> unsupportedBackupAdapterId = checkAdaptersOnBackupOperation(databasesForBackup).stream().map(DbaasAdapter::identifier).collect(Collectors.toList());
        Map<DatabaseRegistry, Database> logDbsForBackup = databasesForBackup.stream()
                .filter(databaseRegistry -> !unsupportedBackupAdapterId.contains(databaseRegistry.getAdapterId()))
                .collect(Collectors.toMap(
                        databaseRegistry -> databaseRegistry,
                        DatabaseRegistry::getDatabase
                ));
        databasesForBackup = new ArrayList<>(logDbsForBackup.keySet());
        boolean interrupted = false;
        NamespaceBackup backup = new NamespaceBackup(id, namespace, new ArrayList<>(logDbsForBackup.values()), databasesForBackup);
        try {
            backupsDbaasRepository.save(backup); // save proceeding backup
            Map<String, List<DatabaseRegistry>> map = new HashMap<>();
            for (DatabaseRegistry registry : databasesForBackup) {
                map.computeIfAbsent(registry.getAdapterId(), k -> new ArrayList<>()).add(registry);
            }
            List<DatabasesBackup> collectedBackups = new ArrayList<>();
            for (Map.Entry<String, List<DatabaseRegistry>> stringListEntry : map
                    .entrySet()) {
                String adapterId = stringListEntry.getKey();
                List<DatabaseRegistry> databaseRegistries = stringListEntry.getValue();
StructuredLog.info(log, "Adapter with id has registered databases to collect backup from", "adapterId", adapterId, "count", databaseRegistries.size());

                var databases = databaseRegistries.stream()
                        .filter(db -> db.getBackupDisabled() == null || !db.getBackupDisabled())
                        .map(DBaaService::getDatabaseName)
                        .toList();

                var databasesBackup = physicalDatabasesService.getAdapterById(adapterId).backup(databases, allowEviction);

                collectedBackups.add(databasesBackup);
            }
            backup.setStatus(aggregateStatus(collectedBackups));
            backup.setBackups(collectedBackups);
            backup.setId(id);
        } catch (InteruptedPollingException e) {
            interrupted = true;
            throw e;
        } catch (Exception e) {
StructuredLog.error(log, "Error during backup collection in", e, "id", id, "namespace", namespace);
            backup.setStatus(NamespaceBackup.Status.FAIL);
            List<String> failReasons = backup.getFailReasons() == null ? new ArrayList<>() : backup.getFailReasons();
            failReasons.add("Exception " + e.getClass() + " during backup collection: " + e.getMessage());
            backup.setFailReasons(failReasons);
        } finally {
            if (!interrupted) {
                backupsDbaasRepository.save(backup);
            }
        }
        return backup;
    }

    <T> T runInNewTransaction(Callable<T> task) {
        return QuarkusTransaction.requiringNew().call(task);
    }

    private NamespaceBackup.Status aggregateStatus(List<DatabasesBackup> collectedBackups) {
        return collectedBackups.stream().map(DatabasesBackup::getStatus)
                .map(Status::ordinal).max(Integer::compareTo)
                .map(it -> Status.values()[it]).map(it -> {
                    return switch (it) {
                        case SUCCESS -> NamespaceBackup.Status.ACTIVE;
                        case PROCEEDING -> NamespaceBackup.Status.PROCEEDING;
                        default -> FAIL;
                    };
                })
                // allow backups with no dbs for scenarios with clean namespace
                .orElse(NamespaceBackup.Status.ACTIVE);
    }

    public boolean validateBackup(NamespaceBackup backup) {
        boolean valid = backup.getBackups().stream().allMatch(
                it -> physicalDatabasesService.getAdapterById(it.getAdapterId()).validate(it)
        );
        if (!valid) {
            backup.setStatus(NamespaceBackup.Status.INVALIDATED);
            backupsDbaasRepository.save(backup);
        }
        return valid;
    }

    private List<DbaasAdapter> checkAdaptersOnBackupOperation(List<DatabaseRegistry> databasesForBackup) {
        return databasesForBackup.stream()
                .filter(database -> database.getBackupDisabled() == null || !database.getBackupDisabled())
                .map(DatabaseRegistry::getAdapterId)
                .distinct()
                .map(adapterId -> physicalDatabasesService.getAdapterById(adapterId))
                .filter(dbaasAdapter -> !dbaasAdapter.isBackupRestoreSupported())
                .collect(Collectors.toList());
    }

    public List<DbaasAdapter> checkAdaptersOnBackupOperation(String namespace) {
        return checkAdaptersOnBackupOperation(getDatabasesForBackup(namespace));
    }

    public List<DatabaseRegistry> getDatabasesForBackup(String namespace) {
        return databaseRegistryDbaasRepository.findInternalDatabaseRegistryByNamespace(namespace).stream()
                .filter(notMarkedForDrop()).collect(Collectors.toList());
    }

    public List<DatabaseRegistry> getDatabasesForBackup(UUID databaseIds) {
        return databaseRegistryDbaasRepository.findDatabaseRegistryById(databaseIds).stream()
                .filter(notMarkedForDrop()).collect(Collectors.toList());
    }

    public NamespaceBackup deleteRestore(UUID namespaceBackupId, UUID restorationId) {
StructuredLog.info(log, "delete restoration with id =", "restorationId", restorationId);
        NamespaceBackup namespaceBackup = backupsDbaasRepository.findById(namespaceBackupId).get();
        if (NamespaceBackup.Status.RESTORING.equals(namespaceBackup.getStatus())) {
StructuredLog.error(log, "namespace in status", "namespace", namespaceBackup.getStatus());
            return namespaceBackup;
        }
        Optional<NamespaceRestoration> optionalResult = namespaceBackup.getRestorations()
                .stream().filter(o -> restorationId.equals(o.getId())).findFirst();
StructuredLog.debug(log, "namespaceBackup before delete", "namespaceBackup", namespaceBackup);
        if (optionalResult.isPresent()) {
            namespaceBackup.getRestorations().remove(optionalResult.get());
            NamespaceBackup save = backupsDbaasRepository.save(namespaceBackup);
StructuredLog.debug(log, "namespaceBackup after delete", "save", save);
            return save;
        }
        return namespaceBackup;
    }

    @Data
    @RequiredArgsConstructor
    class TempDbKey {
        @NonNull
        private String adapterId;
        @NonNull
        private String name;

        private TempDbKey(Database database) {
            this(database.getAdapterId(), database.getName());
        }
    }

    public NamespaceRestoration restore(NamespaceBackup backup, UUID restorationId, String targetNamespace)
            throws NamespaceRestorationFailedException {
        return restore(backup, restorationId, targetNamespace, false, null);
    }

    public NamespaceRestoration restore(NamespaceBackup backup, UUID restorationId, String targetNamespace,
                                        boolean createCopyWithoutDeletion, String version) throws NamespaceRestorationFailedException {

        return restore(backup, restorationId, targetNamespace, createCopyWithoutDeletion, version, null, new HashMap<>());
    }

    @NotNull
    @Transactional
    public NamespaceRestoration restore(NamespaceBackup backup, UUID restorationId, String targetNamespace,
                                        boolean createCopyWithoutDeletion, String version,
                                        SortedMap<String, Object> targetClassifier, Map<String, String> prefixMap) throws NamespaceRestorationFailedException {
        try {
            try {
                if (StringUtils.isEmpty(targetNamespace) || Objects.equals(backup.getNamespace(), targetNamespace)) {
                    if (createCopyWithoutDeletion) {
StructuredLog.info(log, "Restoration proceeded to the backup source namespace, without deletion", "arg0", backup.getId(), "namespace", backup.getNamespace());
                        return restoreToSameNamespace(backup, restorationId, true, version, targetClassifier, prefixMap);
                    }
StructuredLog.info(log, "Restoration proceeded to the backup source namespace", "arg0", backup.getId(), "namespace", backup.getNamespace());
                    return restoreToSameNamespace(backup, restorationId, false, prefixMap);
                } else {
StructuredLog.info(log, "Restoration of backup sourced from proceeded targeted to, deletion =", "deletion", backup.getId(), "namespace", backup.getNamespace(), "targetNamespace", targetNamespace, "arg3", !createCopyWithoutDeletion);
                    return restoreToAnotherNamespace(backup, restorationId, targetNamespace, version, targetClassifier, createCopyWithoutDeletion, prefixMap);
                }
            } catch (Exception e) {
StructuredLog.error(log, "Restoration failed, save status", e, "restorationId", restorationId);
                NamespaceRestoration restoration = backup.getRestorations()
                        .stream()
                        .filter(it -> Objects.equals(restorationId, it.getId()))
                        .findFirst().orElse(new NamespaceRestoration());
                restoration.setStatus(Status.FAIL);
                restoration.getFailReasons().add(e.getMessage());
                if (!Objects.equals(restorationId, restoration.getId())) { // means it's new restoration
                    restoration.setId(restorationId);
                    addToBackup(backup, restoration);
                }
                throw e;
            }
        } finally {
            backup.setStatus(NamespaceBackup.Status.ACTIVE); // save restoration completed
            backupsDbaasRepository.save(backup);
        }
    }

    private NamespaceRestoration restoreToSameNamespace(NamespaceBackup backup, UUID restorationId, boolean regenerateName, Map<String, String> prefixMap) throws NamespaceRestorationFailedException {

        var notMarkedForDropInBackup = backupsDbaasRepository.findById(backup.getId())
                .map(NamespaceBackup::getDatabaseRegistries)
                .orElse(Collections.emptyList()).stream()
                .filter(notMarkedForDrop())
                .toList();

        var currentRegisteredDatabases = databaseRegistryDbaasRepository.findInternalDatabaseRegistryByNamespace(backup.getNamespace());

        if (log.isDebugEnabled()) {
            StructuredLog.debug(log, "Current registered databases in namespace :", "namespace", backup.getNamespace(), "arg1", currentRegisteredDatabases.stream()
                            .map(DBaaService::getDatabaseName)
                            .toList());
        }

        var result = runRestoration(backup, restorationId, notMarkedForDropInBackup, backup.getNamespace(), regenerateName, prefixMap);
        var cleanedDeltaDatabases = cleanDelta(backup, currentRegisteredDatabases, notMarkedForDropInBackup);

        var recreatedDatabasesFromBackup = recreateRemovedBackupedDatabases(backup, currentRegisteredDatabases, notMarkedForDropInBackup);

        var userEnsured = userEnsure(backup, notMarkedForDropInBackup, false);

        setEnsuredResult(result, userEnsured);

        StructuredLog.info(log, """ Databases registration restored of backup in namespace , current databases removed, \ databases recreated from backup, users successfully ensured, skipped""", "backup", backup.getId(), "namespace", backup.getNamespace(), "arg2", cleanedDeltaDatabases.size(), "backup", recreatedDatabasesFromBackup.size(), "arg4", userEnsured.successful.size(), "arg5", userEnsured.skipped);

        return result;
    }

    private List<DatabaseRegistry> recreateRemovedBackupedDatabases(NamespaceBackup backup, List<DatabaseRegistry> currentRegisteredDatabases, List<DatabaseRegistry> notMarkedForDropInBackup) {
        var recreatedDatabasesFromBackup = notMarkedForDropInBackup.stream()
                .filter(Predicate.not(currentRegisteredDatabases::contains))
                .toList();

        if (!recreatedDatabasesFromBackup.isEmpty()) {
StructuredLog.info(log, "Start recreate databases registration of backup in namespace", "arg0", backup.getId(), "namespace", backup.getNamespace());

            if (log.isDebugEnabled()) {
                StructuredLog.debug(log, "Saving the recreated databases with names:", "backup", notMarkedForDropInBackup.stream()
                                .map(DBaaService::getDatabaseName)
                                .toList());
            }

            recreatedDatabasesFromBackup = databaseRegistryDbaasRepository.saveAll(recreatedDatabasesFromBackup);
        }

        return recreatedDatabasesFromBackup;
    }

    private NamespaceRestoration restoreToSameNamespace(NamespaceBackup backup, UUID restorationId, boolean regenerateName,
                                                        String version, SortedMap<String, Object> targetClassifier, Map<String, String> prefixMap) throws NamespaceRestorationFailedException {
        NamespaceRestoration result;

        List<DatabaseRegistry> notMarkedForDropInBackup = backup.getDatabaseRegistries()
                .stream()
                .filter(notMarkedForDrop())
                .collect(Collectors.toList());
        result = runRestoration(backup, restorationId, notMarkedForDropInBackup, backup.getNamespace(), regenerateName, prefixMap);

        log.info("Saving databases in database collection");
        saveRestoreDbWithAnotherName(backup.getNamespace(), result, notMarkedForDropInBackup, version, targetClassifier);
        return getNamespaceRestoration(backup, result, notMarkedForDropInBackup);
    }

    private void setEnsuredResult(NamespaceRestoration result, BulkUserEnsureResult userEnsured) {
        if (!userEnsured.fails.isEmpty()) {
            result.setStatus(Status.FAIL);
            StringBuilder failReasonBuilder = new StringBuilder("Failed to ensure some of users:")
                    .append(System.lineSeparator());
            userEnsured.fails.forEach(it -> {
                failReasonBuilder.append(it.getClass().getSimpleName());
                failReasonBuilder.append(" : ");
                failReasonBuilder.append(it.getMessage());
                failReasonBuilder.append(System.lineSeparator());
            });
            result.getFailReasons().add(failReasonBuilder.toString());
        }
    }

    private List<DatabaseRegistry> cleanDelta(NamespaceBackup backup, List<DatabaseRegistry> currentRegisteredDatabases, List<DatabaseRegistry> notMarkedForDropInBackup) {
        log.info("All database backups has been restored successfully, start calculate databases delta");
StructuredLog.debug(log, "Restored backup contains databases", "arg0", backup.getId(), "count", notMarkedForDropInBackup.size());

        var deltaDatabases = getBackupDelta(backup, currentRegisteredDatabases);

        StructuredLog.info(log, "Start clean delta of databases during restoration of backup in namespace", "arg0", deltaDatabases.size(), "backup", backup.getId(), "namespace", backup.getNamespace());

        deltaDatabases = deletionService.markRegistriesForDrop(backup.getNamespace(), deltaDatabases);
        deletionService.dropRegistriesSafe(backup.getNamespace(), deltaDatabases);

StructuredLog.info(log, "Delta cleaned during restoration of backup in namespace", "arg0", backup.getId(), "namespace", backup.getNamespace());

        return deltaDatabases;
    }

    @NotNull
    private NamespaceRestoration getNamespaceRestoration(NamespaceBackup backup, NamespaceRestoration result, List<DatabaseRegistry> notMarkedForDropInBackup) {
        BulkUserEnsureResult userEnsured = userEnsure(backup, notMarkedForDropInBackup, true);
        setEnsuredResult(result, userEnsured);

        StructuredLog.info(log, "Databases registration restored of backup in namespace , " + " databases saved from backup, users successfully ensured, skipped", "backup", backup.getId(), "namespace", backup.getNamespace(), "backup", notMarkedForDropInBackup.size(), "arg3", userEnsured.successful.size(), "arg4", userEnsured.skipped);

        StringBuilder failReasonBuilder = new StringBuilder("Restoration failed during metadata restoration phase:")
                .append(System.lineSeparator());
        notMarkedForDropInBackup.forEach(database -> {
            DbaasAdapter adapter = physicalDatabasesService.getAdapterById(database.getAdapterId());
            String dbName = DBaaService.getDatabaseName(database);
            try {
                Map<String, Object> metadata = buildMetadata(database.getClassifier());
                adapter.changeMetaData(dbName, metadata);
StructuredLog.info(log, "Metadata was change successfully from db with name", "dbName", dbName);
            } catch (Exception e) {
StructuredLog.error(log, "Failed to update metadata for database", e, "dbName", dbName);
                failReasonBuilder.append("Failed to update metadata for database ").append(dbName);
                failReasonBuilder.append(System.lineSeparator());
                result.setStatus(Status.FAIL);
            }
        });
        if (Status.FAIL == result.getStatus()) {
            result.getFailReasons().add(failReasonBuilder.toString());
        }
        return result;
    }

    private NamespaceRestoration restoreToAnotherNamespace(NamespaceBackup backup, UUID restorationId, String targetNamespace, boolean restoreWithoutDelete, Map<String, String> prefixMap) throws NamespaceRestorationFailedException {
        return restoreToAnotherNamespace(backup, restorationId, targetNamespace, null, null, restoreWithoutDelete, prefixMap);
    }

    private NamespaceRestoration restoreToAnotherNamespace(NamespaceBackup backup, UUID restorationId, String targetNamespace,
                                                           String version, SortedMap<String, Object> targetClassifier, boolean restoreWithoutDelete, Map<String, String> prefixMap) throws NamespaceRestorationFailedException {
        NamespaceRestoration result;

        List<DatabaseRegistry> notMarkedForDropInBackup = backup.getDatabaseRegistries()
                .stream()
                .filter(notMarkedForDrop())
                .collect(Collectors.toList());

        result = runRestoration(backup, restorationId, notMarkedForDropInBackup, targetNamespace, true, prefixMap);
        if (restoreWithoutDelete) {
            saveRestoreDbWithAnotherName(targetNamespace, result, notMarkedForDropInBackup, version, targetClassifier);
        } else {
            List<DatabaseRegistry> targetDatabasesToDrop = databaseRegistryDbaasRepository.findInternalDatabaseRegistryByNamespace(targetNamespace);
            StructuredLog.info(log, "Clean databases in target namespace during restoration of backup", "arg0", targetDatabasesToDrop.size(), "targetNamespace", targetNamespace, "backup", backup.getId());
StructuredLog.debug(log, "databases to drop =", "targetDatabasesToDrop", targetDatabasesToDrop);
            targetDatabasesToDrop = deletionService.markRegistriesForDrop(targetNamespace, targetDatabasesToDrop);
            deletionService.dropRegistriesSafe(targetNamespace, targetDatabasesToDrop);
            saveRestoreDbWithAnotherName(targetNamespace, result, notMarkedForDropInBackup);
        }
        log.info("Saving databases in database collection");
        return getNamespaceRestoration(backup, result, notMarkedForDropInBackup);
    }

    private void saveRestoreDbWithAnotherName(String targetNamespace, NamespaceRestoration result, List<DatabaseRegistry> notMarkedForDropInBackup) {
        saveRestoreDbWithAnotherName(targetNamespace, result, notMarkedForDropInBackup, null);
    }

    private void saveRestoreDbWithAnotherName(String targetNamespace, NamespaceRestoration result, List<DatabaseRegistry> notMarkedForDropInBackup,
                                              String version) {
        saveRestoreDbWithAnotherName(targetNamespace, result, notMarkedForDropInBackup, version, null);
    }

    private void saveRestoreDbWithAnotherName(String targetNamespace, NamespaceRestoration result,
                                              List<DatabaseRegistry> notMarkedForDropInBackup, String version,
                                              SortedMap<String, Object> targetClassifier) {
        result.getRestoreResults().stream().map(RestoreResult::getChangedNameDb)
StructuredLog.info(log, "Map old name database to new name", "arg0", oldToNewDbName))
                .forEach(oldToNewDbName -> oldToNewDbName
                        .forEach((oldName, newName) -> notMarkedForDropInBackup.stream()
                                .filter(currentDatabase -> DBaaService.getDatabaseName(currentDatabase).equalsIgnoreCase(oldName))
                                .forEach(currentDatabase -> {
                                    entityManager.detach(currentDatabase);

                                    currentDatabase.setId(UUID.randomUUID());
                                    currentDatabase.getDatabase().setId(UUID.randomUUID());
                                    currentDatabase.setName(newName);
                                    currentDatabase.getDatabase().setNamespace(targetNamespace);
                                    currentDatabase.setNamespace(targetNamespace);
                                    currentDatabase.setOldClassifier(null);

                                    if (targetClassifier != null) {
                                        currentDatabase.setClassifier(targetClassifier);
                                        currentDatabase.getDatabase().setClassifier(targetClassifier);
                                    }

                                    var newDbResources = currentDatabase.getResources().stream()
                                            .map(DbResource::new)
                                            .toList();

                                    currentDatabase.setResources(newDbResources);
                                    currentDatabase.getClassifier().put("namespace", targetNamespace);
                                    currentDatabase.getDatabase().setClassifier(currentDatabase.getClassifier());
                                    currentDatabase.setBgVersion(version);
                                    currentDatabase.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));

                                    if (currentDatabase.getType().equalsIgnoreCase(DatabaseType.OPENSEARCH.name())) {
                                        currentDatabase.setName(StringUtils.EMPTY);

                                        currentDatabase.getConnectionProperties()
                                                .forEach(cp -> cp.put("resourcePrefix", newName));

                                        currentDatabase.getResources().stream()
                                                .filter(dbResource -> "resourcePrefix".equals(dbResource.getKind()))
                                                .forEach(dbResource -> dbResource.setName(newName));
                                    }

                                    // end of block
                                    StructuredLog.info(log, "Change database name from on was successful. " + "This db has been saved in database collection with attributes", "oldName", oldName, "newName", newName, "currentDatabase", currentDatabase);
                                })));
    }

    private NamespaceRestoration runRestoration(NamespaceBackup backup,
                                                UUID restorationId,
                                                List<DatabaseRegistry> notMarkedForDropInBackup,
                                                String namespaceRestore,
                                                boolean regenerateNames,
                                                Map<String, String> prefixMap) throws NamespaceRestorationFailedException {
StructuredLog.info(log, "Start restore backup for id in namespace", "arg0", backup.getId(), "namespaceRestore", namespaceRestore);
        if (log.isDebugEnabled()) {
            StructuredLog.debug(log, "Restoring backup contains databases:", "backup", notMarkedForDropInBackup.size(), "backup", notMarkedForDropInBackup.stream()
                            .map(DBaaService::getDatabaseName)
                            .toList());
        }

        NamespaceRestoration result;
        Optional<NamespaceRestoration> existingRestoration = backup.getRestorations().stream().filter(r -> r.getId().equals(restorationId)).findAny();
        if (existingRestoration.isPresent()) {
            result = existingRestoration.get();
        } else {
            result = new NamespaceRestoration();
            result.setId(restorationId);
            addToBackup(backup, result);
        }
        result.setStatus(Status.PROCEEDING);

        backup.setStatus(NamespaceBackup.Status.RESTORING);
        runInNewTransaction(() -> backupsDbaasRepository.save(backup)); // save backup restore proceeds
        if (backupsDbaasRepository.getEntityManager().contains(backup)) {
            backupsDbaasRepository.getEntityManager().refresh(backup);
        }

StructuredLog.info(log, "Namespace restoration with id was saved to backup id", "restorationId", restorationId, "arg1", backup.getId());
        if (regenerateNames) {
            StructuredLog.info(log, "Backup would be proceeded with names regeneration from namespace to", "namespace", backup.getNamespace(), "namespaceRestore", namespaceRestore);
        }
        List<RestoreResult> subrestorations = backup.getBackups().stream()
                .map(dbBackup -> physicalDatabasesService.getAdapterById(dbBackup.getAdapterId())
                        .restore(namespaceRestore, dbBackup, regenerateNames, backup.getDatabaseRegistries(), prefixMap)
                )
                .toList();

        result.setRestoreResults(subrestorations);

        result.setStatus(subrestorations.stream()
                .map(RestoreResult::getStatus)
                .map(Status::ordinal)
                .max(Integer::compareTo)
                .map(it -> Status.values()[it])
                // allow restoration with no dbs for scenarios with clean namespace
                .orElse(Status.SUCCESS));
        if (Status.FAIL == result.getStatus()) {
            StructuredLog.error(log, "Not all backups was restored successfully in namespace", "backup", backup.getBackups(), "namespace", backup.getNamespace());
            String failReason = "Expected all subrestorations to be successful, but got: " + stringifyFailedRestoration(subrestorations);
            result.getFailReasons().add(failReason);
            long failedNumber = subrestorations
                    .stream()
                    .map(RestoreResult::getStatus)
                    .filter(it -> Status.FAIL == it).count();
            StructuredLog.error(log, "During restoration of backup failed subrestorations and fail reason:", "restorationId", restorationId, "backup", backup.getId(), "failedNumber", failedNumber, "failReason", failReason);
            throw new NamespaceRestorationFailedException("Failed " + failedNumber + " subrestorations", null, result);
        }
        return result;
    }

    private String stringifyFailedRestoration(Collection<RestoreResult> restoreResults) {
        return restoreResults == null || restoreResults.isEmpty() ? "no subrestorations" :
                ((Supplier<String>) () -> {
                    StringBuilder builder = new StringBuilder();
                    restoreResults.forEach(res -> {
                        appendFailed(builder, res.getStatus(), res.getAdapterId());
                    });
                    builder.append(" ] ");
                    return builder.toString();
                }).get();
    }

    private StringBuilder appendFailed(StringBuilder builder, Status status, String adapterId) {
        if (Status.FAIL.equals(status)) {
            DbaasAdapter adapter = adapterOf(adapterId);
            builder.append(!builder.isEmpty() ? " , " : " [ ")
                    .append(status)
                    .append(" restoration  in adapter ")
                    .append(adapterId)
                    .append(" of type ")
                    .append(adapter.type());
        }
        return builder;
    }

    private DbaasAdapter adapterOf(String adapterId) {
        return physicalDatabasesService.getAdapterById(adapterId);
    }

    private void addToBackup(NamespaceBackup backup, NamespaceRestoration result) {
        List<NamespaceRestoration> restorations = backup.getRestorations();
        if (restorations == null) {
            restorations = new ArrayList<>();
        }
        restorations.add(result);
        backup.setRestorations(restorations);
    }

    class BulkUserEnsureResult {
        List<EnsuredUser> successful = Collections.emptyList();
        List<Throwable> fails = new ArrayList<>();
        int skipped = 0;
    }

    private static final EnsuredUser ADAPTER_DOES_NOT_SUPPORT_ENSURE = new EnsuredUser();

    private DescribedDatabase describeDatabase(DbaasAdapter adapter, String name) {
        DescribedDatabase describedDatabase = adapter.describeDatabases(Collections.singleton(name)).get(name);
        if (VERSION_1.equals(adapter.getSupportedVersion())) {
            if (describedDatabase.getConnectionProperties() != null) {
                describedDatabase.getConnectionProperties().get(0).put(ROLE, Role.ADMIN.toString());
            }
        }
        return describedDatabase;
    }

    BulkUserEnsureResult userEnsure(NamespaceBackup backup, List<DatabaseRegistry> notMarkedForDropInBackup, Boolean regenerateCredentials) {
        if (!notMarkedForDropInBackup.isEmpty()) {
StructuredLog.info(log, "Start ensure users after backup restoration in namespace", "arg0", backup.getId(), "namespace", notMarkedForDropInBackup.get(0).getNamespace());
        } else {
            log.info("There are no records for user ensure operation");
        }

        BulkUserEnsureResult res = new BulkUserEnsureResult();

        List<Object> results = notMarkedForDropInBackup.stream().map(db -> {
            var adapter = physicalDatabasesService.getAdapterById(db.getAdapterId());
            var dbName = DBaaService.getDatabaseName(db);

            if (!adapter.isUsersSupported()) {
                if (adapter.isDescribeDatabasesSupported()) {
StructuredLog.info(log, "Describe database by adapter", "dbName", dbName, "adapter", adapter.identifier());
                    DescribedDatabase describedDatabase = describeDatabase(adapter, dbName);
                    db.setConnectionProperties(describedDatabase.getConnectionProperties());
                    db.setResources(describedDatabase.getResources());
                    db.getDatabase().setLastRotatedAt(OffsetDateTime.now());
                    databaseRegistryDbaasRepository.saveInternalDatabase(db);
StructuredLog.info(log, "Database described and saved", "dbName", dbName);
                }
                res.skipped++;
                return Arrays.asList(ADAPTER_DOES_NOT_SUPPORT_ENSURE);
            }
            try {
                List<EnsuredUser> users = db.getConnectionProperties().stream().map(v -> {
                    String password = null;
                    String username = regenerateCredentials ? null : (String) v.get("username");
StructuredLog.info(log, "User with Role would be ensured to have service access to database", "arg0", username == null ? "<NEW>" : username, "arg1", v.get(ROLE), "dbName", dbName);
                    String role;
                    if (v.get(ROLE) instanceof String) {
                        role = (String) v.get(ROLE);
                    } else {
StructuredLog.error(log, "Database connection property contains not supported role type. Expected type is", "arg0", String.class);
                        throw new IllegalArgumentException("Database connection property contains not supported role type. Expected type is " + String.class);
                    }
                    try {
                        password = regenerateCredentials ? null : encryption.getDecryptedPasswordForBackup(db.getDatabase(), role);
                    } catch (DecryptException e) {
StructuredLog.info(log, "Error during password decryption of database. New password will be generated in adapter", "dbName", dbName);
                    }
                    int count = 0;
                    int maxTries = 2;
                    EnsuredUser user;
                    while (true) {
                        try {
                            if (VERSION_1.equals(adapter.getSupportedVersion())) {
                                user = adapter.ensureUser(username, password, dbName);
                            } else {
                                user = adapter.ensureUser(username, password, dbName, role);
                            }
                            break;
                        } catch (Exception e) {
                            if (count == maxTries) {
                                throw e;
                            }
StructuredLog.warn(log, "Failed try № to ensure user", e, "count", count);
                            count++;
                        }
                    }
                    user.getConnectionProperties().putIfAbsent(ROLE, role);
                    return user;
                }).collect(Collectors.toList());
                encryption.deletePassword(db.getDatabase());
                db.setConnectionProperties(users.stream().map(EnsuredUser::getConnectionProperties).collect(Collectors.toList()));
                db.setResources(users.stream().map(EnsuredUser::getResources).filter(Objects::nonNull).flatMap(Collection::stream).collect(Collectors.toList()));
                db.setResources(db.getResources().stream().distinct().collect(Collectors.toList()));

                encryption.encryptPassword(db.getDatabase());
                if (regenerateCredentials) {
                    db.getDatabase().setLastRotatedAt(OffsetDateTime.now());
                }
                databaseRegistryDbaasRepository.saveInternalDatabase(db);
                StructuredLog.info(log, "Users ensured access to db", "arg0", users.stream()
                                .map(EnsuredUser::getName)
                                .toList(), "dbName", dbName);
                return users;
            } catch (Exception e) {
StructuredLog.error(log, "Failed to ensure user for database", e, "db", db);
                return e;
            }
        }).collect(Collectors.toList());
        res.fails.addAll(results.stream().filter(it -> it instanceof Throwable).map(it -> (Throwable) it).collect(Collectors.toList()));
        res.successful = results.stream()
                .filter(it -> it instanceof List).map(v -> ((List<?>) v).stream().filter(it -> it instanceof EnsuredUser)
                        .map(it -> (EnsuredUser) it)
                        .filter(it -> it != ADAPTER_DOES_NOT_SUPPORT_ENSURE).collect(Collectors.toList())).flatMap(List::stream).collect(Collectors.toList());

        return res;
    }

    private List<DatabaseRegistry> getBackupDelta(NamespaceBackup backup, List<DatabaseRegistry> currentRegisteredDatabases) {
StructuredLog.debug(log, "Registered databases in namespace", "count", currentRegisteredDatabases.size(), "namespace", backup.getNamespace());
        Map<String, List<DatabaseRegistry>> groupedCurrentDatabases = currentRegisteredDatabases.stream()
                .collect(Collectors.groupingBy(dbr -> dbr.getDatabase().getAdapterId()));

        Set<String> disabledDatabases = backup.getDatabases().stream()
                .filter(Database::getBackupDisabled)
                .map(DBaaService::getDatabaseName)
                .collect(Collectors.toSet());

        return backup.getBackups().stream().map(databasesBackup -> {
            List<DatabaseRegistry> adapterCurrentDatabases = groupedCurrentDatabases.get(databasesBackup.getAdapterId());

            if (adapterCurrentDatabases == null) {
StructuredLog.debug(log, "For adapter no databases found to remove during backup restoration", "adapter", databasesBackup.getAdapterId(), "arg1", backup.getId());
                return Collections.<DatabaseRegistry>emptyList();
            } else {
                List<DatabaseRegistry> adapterCurrentDatabasesDelta = adapterCurrentDatabases.stream()
                        .filter(current -> !databasesBackup.getDatabases().contains(DBaaService.getDatabaseName(current))
                                && !disabledDatabases.contains(DBaaService.getDatabaseName(current))
                        )
                        .collect(Collectors.toList());
                if (log.isDebugEnabled()) {
                    StructuredLog.debug(log, "For adapter during restoration of backup calculated databases delta to remove:", "backup", databasesBackup.getAdapterId(), "backup", backup.getId(), "adapter", adapterCurrentDatabasesDelta.stream()
                                    .map(DBaaService::getDatabaseName)
                                    .toList());
                }
                return adapterCurrentDatabasesDelta;
            }
        }).flatMap(Collection::stream).collect(Collectors.toList());
    }

}
