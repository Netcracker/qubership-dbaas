package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.context.propagation.core.ContextManager;
import org.qubership.cloud.dbaas.DatabaseType;
import org.qubership.cloud.dbaas.dto.DescribedDatabase;
import org.qubership.cloud.dbaas.dto.EnsuredUser;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.dto.backup.DeleteResult;
import org.qubership.cloud.dbaas.dto.backup.NamespaceBackupDeletion;
import org.qubership.cloud.dbaas.dto.backup.Status;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.DbState;
import org.qubership.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceRestoration;
import org.qubership.cloud.dbaas.entity.pg.backup.RestoreResult;
import org.qubership.cloud.dbaas.exceptions.*;
import org.qubership.cloud.dbaas.repositories.dbaas.BackupsDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.utils.DbaasBackupUtils;
import org.qubership.cloud.encryption.cipher.exception.DecryptException;
import org.qubership.cloud.framework.contexts.xrequestid.XRequestIdContextObject;
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

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.qubership.cloud.dbaas.DbaasApiPath.VERSION_1;
import static org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup.Status.FAIL;
import static org.qubership.cloud.dbaas.service.AbstractDbaasAdapterRESTClient.buildMetadata;
import static org.qubership.cloud.framework.contexts.xrequestid.XRequestIdContextObject.X_REQUEST_ID;

@Slf4j
@ApplicationScoped
public class DBBackupsService {
    private final DBaaService dBaaService;
    private final PhysicalDatabasesService physicalDatabasesService;
    private final DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    private final BackupsDbaasRepository backupsDbaasRepository;
    private final PasswordEncryption encryption;
    private final EntityManager entityManager;
    private final DbaaSHelper dbaaSHelper;

    private final ExecutorService asyncExecutorService = Executors.newSingleThreadExecutor();

    public DBBackupsService(DBaaService dBaaService, PhysicalDatabasesService physicalDatabasesService,
                            DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository, BackupsDbaasRepository backupsDbaasRepository,
                            PasswordEncryption encryption, EntityManager entityManager, DbaaSHelper dbaaSHelper) {
        this.dBaaService = dBaaService;
        this.physicalDatabasesService = physicalDatabasesService;
        this.databaseRegistryDbaasRepository = databaseRegistryDbaasRepository;
        this.backupsDbaasRepository = backupsDbaasRepository;
        this.encryption = encryption;
        this.entityManager = entityManager;
        this.dbaaSHelper = dbaaSHelper;
    }

    private Predicate<DatabaseRegistry> notMarkedForDrop() {
        return ((Predicate<DatabaseRegistry>) DatabaseRegistry::isMarkedForDrop).negate();
    }

    @Transactional
    public NamespaceBackupDeletion deleteBackup(NamespaceBackup backupToDelete) throws NamespaceBackupDeletionFailedException, IllegalArgumentException {
        log.info("Start delete backup for id {} in namespace {}", backupToDelete.getId(), backupToDelete.getNamespace());
        if (log.isDebugEnabled()) {
            log.debug("Backup information {}", backupToDelete);
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
                    log.info("backup with adapter id {} has {} databases to delete", adapterId, backup.getDatabases().size());
                    if (log.isDebugEnabled()) {
                        log.debug("Backup contains adapter {}", physicalDatabasesService.getAdapterById(adapterId).toString());
                    }
                    return physicalDatabasesService.getAdapterById(adapterId).delete(backup);
                }).collect(Collectors.toList());

            if (log.isDebugEnabled()) {
                log.debug("Backup deletion result is {}", deleteResults);
            }

            result.setStatus(deleteResults.stream()
                .map(DeleteResult::getStatus)
                .map(Status::ordinal)
                .max(Integer::compareTo)
                .map(it -> Status.values()[it])
                .orElse(Status.FAIL));

            if (log.isDebugEnabled()) {
                log.debug("Backup deletion result is {}", result);
            }
        }

        result.setDeleteResults(deleteResults);

        if (result.getStatus() == Status.FAIL) {
            log.error("Not all backups was deleted successfully {} in namespace {}",
                backupToDelete.getBackups(), backupToDelete.getNamespace());
            backupToDelete.setStatus(NamespaceBackup.Status.DELETION_FAILED);
            String failReason = "Expected all backups deleted successful, but got: " + stringifyFailedDeletion(deleteResults);
            if (log.isDebugEnabled()) {
                log.debug("Fail reasons are {}", failReason);
            }
            result.getFailReasons().add(failReason);
            long failedNumber = deleteResults
                .stream()
                .map(DeleteResult::getStatus)
                .filter(it -> Status.FAIL == it).count();
            log.error("During deletion of backup {} failed {} subdeletion and fail reason: {}",
                backupToDelete.getId(),
                failedNumber,
                failReason);
            backupsDbaasRepository.save(backupToDelete);
            throw new NamespaceBackupDeletionFailedException(backupToDelete.getId(), failedNumber, backupToDelete);
        }
        backupsDbaasRepository.delete(backupToDelete);
        log.info("Deletion of backup {} succeed", backupToDelete.getId());
        return result;
    }

    @Transactional
    public void asyncDeleteAllNamespaceBackupsInNamespacesByPortions(@NotEmpty Set<String> namespaces, boolean forceRemoveNotDeletableBackups) {
        log.info("Scheduling async deletion of namespace backups in namespaces {}", namespaces);

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

        log.info("Started deletion of all namespace backups in {} namespaces {}", namespaces.size(), namespaces);

        if (!forceRemoveNotDeletableBackups) {

            var notDeletableNamespaceBackupIds = backupsDbaasRepository.findAllNotDeletableBackupIdsByNamespaces(namespaces);

            if (CollectionUtils.isNotEmpty(notDeletableNamespaceBackupIds)) {

                allSkippedDeletedNamespaceBackupIdsAmount = notDeletableNamespaceBackupIds.size();

                log.warn("Skipped deletion of {} namespace backups with ids {} because its can not be deleted",
                    notDeletableNamespaceBackupIds.size(), notDeletableNamespaceBackupIds
                );
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

                log.info("Started deletion of {} namespace backups by portion with number {}, namespace backup ids {}",
                    namespaceBackupsIds.size(), portionNumber, namespaceBackupsIds
                );

                var failedDeleteNamespaceBackups = new ArrayList<NamespaceBackup>();

                for (var namespaceBackup : namespaceBackups) {
                    var namespaceBackupId = namespaceBackup.getId();

                    try {
                        log.info("Started deletion of namespace backup with id {}", namespaceBackupId);

                        deleteBackup(namespaceBackup);

                        log.info("Finished deletion of namespace backup with id {}", namespaceBackupId);
                    } catch (Exception ex) {
                        failedDeleteNamespaceBackups.add(namespaceBackup);

                        log.error("Error happened during deletion of namespace backup with id {}",
                            namespaceBackupId, ex
                        );
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

                log.info("""
                        Finished deletion of {} namespace backups by portion with number {}, successfully deleted {} namespace backups with ids {}, \
                        failed deleted {} ones with ids {}""",

                    namespaceBackupsIds.size(), portionNumber, successfullyDeletedNamespaceBackupsIds.size(),
                    successfullyDeletedNamespaceBackupsIds, failedDeleteNamespaceBackupsIds.size(), failedDeleteNamespaceBackupsIds
                );
            }
        } while (CollectionUtils.isNotEmpty(namespaceBackups));

        var allNamespaceBackupsAmount = allSuccessfullyDeletedNamespaceBackupIdsAmount
            + allSkippedDeletedNamespaceBackupIdsAmount + allFailedDeletedNamespaceBackupIdsAmount;

        log.info("""
            Finished deletion of all {} namespace backups in {} namespaces {}, successfully deleted {} namespace backups, \
            skipped deletion of {} ones, failed deleted {} ones""",

            allNamespaceBackupsAmount, namespaces.size(), namespaces, allSuccessfullyDeletedNamespaceBackupIdsAmount,
            allSkippedDeletedNamespaceBackupIdsAmount, allFailedDeletedNamespaceBackupIdsAmount
        );
    }

    private void backupValidation(DatabasesBackup backup) throws MultiValidationException {
        List<ValidationException> errors = new ArrayList<>();
        String adapterId = backup.getAdapterId();
        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(adapterId);
        log.info("Validating adapter {} with adapter id {}", adapter, adapterId);
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
            log.info("Start collect backup for id {} in namespace {}", id, namespace);
            List<DatabaseRegistry> databasesForBackup = getDatabasesForBackup(namespace);
            return getNamespaceBackup(namespace, id, allowEviction, databasesForBackup);
        } catch (Exception ex) {
            log.error("Failed collecting backup for id {} in namespace {}",id, namespace, ex);
            throw ex;
        }
    }

    public NamespaceBackup collectBackupSingleDatabase(String namespace, UUID id, Boolean allowEviction, UUID databaseId) throws InteruptedPollingException {
        log.info("Start collect backup for id {} in namespace {} with databaseId {}", id, namespace, databaseId);
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
                log.info("Adapter with id {} has registered {} databases to collect backup from", adapterId, databaseRegistries.size());

                var databases = databaseRegistries.stream()
                    .filter(db -> db.getBackupDisabled() == null || !db.getBackupDisabled())
                    .map(DbaasBackupUtils::getDatabaseName)
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
            log.error("Error during backup " + id + " collection in " + namespace, e);
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
                    switch (it) {
                        case SUCCESS:
                            return NamespaceBackup.Status.ACTIVE;
                        case PROCEEDING:
                            return NamespaceBackup.Status.PROCEEDING;
                        case FAIL:
                        default:
                            return FAIL;
                    }
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
        log.info("delete restoration with id = {}", restorationId);
        NamespaceBackup namespaceBackup = backupsDbaasRepository.findById(namespaceBackupId).get();
        if (NamespaceBackup.Status.RESTORING.equals(namespaceBackup.getStatus())) {
            log.error("namespace in status {}", namespaceBackup.getStatus());
            return namespaceBackup;
        }
        Optional<NamespaceRestoration> optionalResult = namespaceBackup.getRestorations()
                .stream().filter(o -> restorationId.equals(o.getId())).findFirst();
        log.debug("namespaceBackup before delete {}", namespaceBackup);
        if (optionalResult.isPresent()) {
            namespaceBackup.getRestorations().remove(optionalResult.get());
            NamespaceBackup save = backupsDbaasRepository.save(namespaceBackup);
            log.debug("namespaceBackup after delete {}", save);
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
                        log.info("Restoration proceeded to the backup {} source namespace {}, without deletion", backup.getId(), backup.getNamespace());
                        return restoreToSameNamespace(backup, restorationId, true, version, targetClassifier, prefixMap);
                    }
                    log.info("Restoration proceeded to the backup {} source namespace {}", backup.getId(), backup.getNamespace());
                    return restoreToSameNamespace(backup, restorationId, false, prefixMap);
                } else {
                    log.info("Restoration of backup {} sourced from {} proceeded targeted to {}, deletion = {}", backup.getId(), backup.getNamespace(), targetNamespace, !createCopyWithoutDeletion);
                    return restoreToAnotherNamespace(backup, restorationId, targetNamespace, version, targetClassifier, createCopyWithoutDeletion, prefixMap);
                }
            } catch (Exception e) {
                log.error("Restoration {} failed, save status", restorationId, e);
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
            log.debug("Current registered databases in namespace {}: {}", backup.getNamespace(),
                currentRegisteredDatabases.stream()
                    .map(DbaasBackupUtils::getDatabaseName)
                    .toList()
            );
        }

        var result = runRestoration(backup, restorationId, notMarkedForDropInBackup, backup.getNamespace(), regenerateName, prefixMap);
        var cleanedDeltaDatabases = cleanDelta(backup, currentRegisteredDatabases, notMarkedForDropInBackup);

        var recreatedDatabasesFromBackup = recreateRemovedBackupedDatabases(backup, currentRegisteredDatabases, notMarkedForDropInBackup);

        var userEnsured = userEnsure(backup, notMarkedForDropInBackup, false);

        setEnsuredResult(result, userEnsured);

        log.info("""
            Databases registration restored of backup {} in namespace {}, {} current databases removed, \
            {} databases recreated from backup, {} users successfully ensured, {} skipped""",

            backup.getId(), backup.getNamespace(), cleanedDeltaDatabases.size(), recreatedDatabasesFromBackup.size(),
            userEnsured.successful.size(), userEnsured.skipped
        );

        return result;
    }

    private List<DatabaseRegistry> recreateRemovedBackupedDatabases(NamespaceBackup backup, List<DatabaseRegistry> currentRegisteredDatabases, List<DatabaseRegistry> notMarkedForDropInBackup) {
        var recreatedDatabasesFromBackup = notMarkedForDropInBackup.stream()
            .filter(Predicate.not(currentRegisteredDatabases::contains))
            .toList();

        if (!recreatedDatabasesFromBackup.isEmpty()) {
            log.info("Start recreate databases registration of backup {} in namespace {}", backup.getId(), backup.getNamespace());

            if (log.isDebugEnabled()) {
                log.debug("Saving the recreated databases with names: {}",
                    notMarkedForDropInBackup.stream()
                        .map(DbaasBackupUtils::getDatabaseName)
                        .toList()
                );
            }

            databaseRegistryDbaasRepository.saveAll(recreatedDatabasesFromBackup);
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
        log.debug("Restored backup {} contains {} databases", backup.getId(), notMarkedForDropInBackup.size());

        var deltaDatabases = getBackupDelta(backup, currentRegisteredDatabases);

        log.info("Start clean delta of {} databases during restoration of backup {} in namespace {}",
                deltaDatabases.size(), backup.getId(), backup.getNamespace()
        );

        dBaaService.markForDrop(backup.getNamespace(), deltaDatabases);
        dBaaService.dropDatabases(deltaDatabases, backup.getNamespace());

        log.info("Delta cleaned during restoration of backup {} in namespace {}", backup.getId(), backup.getNamespace());

        return deltaDatabases;
    }

    @NotNull
    private NamespaceRestoration getNamespaceRestoration(NamespaceBackup backup, NamespaceRestoration result, List<DatabaseRegistry> notMarkedForDropInBackup) {
        BulkUserEnsureResult userEnsured = userEnsure(backup, notMarkedForDropInBackup, true);
        setEnsuredResult(result, userEnsured);

        log.info("Databases registration restored of backup {} in namespace {}, " +
                        " {} databases saved from backup, {} users successfully ensured, {} skipped",
                backup.getId(),
                backup.getNamespace(),
                notMarkedForDropInBackup.size(),
                userEnsured.successful.size(),
                userEnsured.skipped);

        StringBuilder failReasonBuilder = new StringBuilder("Restoration failed during metadata restoration phase:")
                .append(System.lineSeparator());
        notMarkedForDropInBackup.forEach(database -> {
            DbaasAdapter adapter = physicalDatabasesService.getAdapterById(database.getAdapterId());
            String dbName = DbaasBackupUtils.getDatabaseName(database);
            try {
                Map<String, Object> metadata = buildMetadata(database.getClassifier());
                adapter.changeMetaData(dbName, metadata);
                log.info("Metadata was change successfully from db with name {}", dbName);
            } catch (Exception e) {
                log.error("Failed to update metadata for database {}. Exception {}", dbName, e);
                failReasonBuilder.append("Failed to update metadata for database " + dbName);
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
            log.info("Clean {} databases in target namespace {} during restoration of backup {}",
                    targetDatabasesToDrop.size(), targetNamespace, backup.getId());
            log.debug("databases to drop = {}", targetDatabasesToDrop);
            dBaaService.markForDrop(targetNamespace, targetDatabasesToDrop);
            dBaaService.dropDatabases(targetDatabasesToDrop, targetNamespace);
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
                .peek(oldToNewDbName -> log.info("Map old name database to new name {}", oldToNewDbName))
                .forEach(oldToNewDbName -> oldToNewDbName
                        .forEach((oldName, newName) -> notMarkedForDropInBackup.stream()
                                .filter(currentDatabase -> DbaasBackupUtils.getDatabaseName(currentDatabase).equalsIgnoreCase(oldName))
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
                                    log.info("Change database name from {} on {} was successful. " +
                                            "This db has been saved in database collection with attributes {}", oldName, newName, currentDatabase);
                                })));
    }

    private NamespaceRestoration runRestoration(NamespaceBackup backup,
                                                UUID restorationId,
                                                List<DatabaseRegistry> notMarkedForDropInBackup,
                                                String namespaceRestore,
                                                boolean regenerateNames,
                                                Map<String, String> prefixMap) throws NamespaceRestorationFailedException {
        log.info("Start restore backup for id {} in namespace {}", backup.getId(), namespaceRestore);
        if (log.isDebugEnabled()) {
            log.debug("Restoring backup contains {} databases: {}", notMarkedForDropInBackup.size(),
                    notMarkedForDropInBackup.stream()
                        .map(DbaasBackupUtils::getDatabaseName)
                        .toList()
            );
        }
        NamespaceRestoration result = new NamespaceRestoration();
        result.setId(restorationId);
        result.setStatus(Status.PROCEEDING);
        addToBackup(backup, result);

        backup.setStatus(NamespaceBackup.Status.RESTORING);
        runInNewTransaction(() -> backupsDbaasRepository.save(backup)); // save backup restore proceeds
        if (backupsDbaasRepository.getEntityManager().contains(backup)) {
            backupsDbaasRepository.getEntityManager().refresh(backup);
        }

        log.info("Namespace restoration with id {} was saved to backup id {}", restorationId, backup.getId());
        if (regenerateNames) {
            log.info("Backup would be proceeded with names regeneration from namespace {} to {}",
                    backup.getNamespace(), namespaceRestore);
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
            log.error("Not all backups was restored successfully {} in namespace {}",
                    backup.getBackups(), backup.getNamespace());
            String failReason = "Expected all subrestorations to be successful, but got: " + stringifyFailedRestoration(subrestorations);
            result.getFailReasons().add(failReason);
            long failedNumber = subrestorations
                    .stream()
                    .map(RestoreResult::getStatus)
                    .filter(it -> Status.FAIL == it).count();
            log.error("During restoration {} of backup {} failed {} subrestorations and fail reason: {}",
                    restorationId,
                    backup.getId(),
                    failedNumber,
                    failReason);
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
            builder.append(builder.length() > 0 ? " , " : " [ ")
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
            log.info("Start ensure users after backup {} restoration in namespace {}", backup.getId(), notMarkedForDropInBackup.get(0).getNamespace());
        } else {
            log.info("There are no records for user ensure operation");
        }

        BulkUserEnsureResult res = new BulkUserEnsureResult();

        List<Object> results = notMarkedForDropInBackup.stream().map(db -> {
            var adapter = physicalDatabasesService.getAdapterById(db.getAdapterId());
            var dbName = DbaasBackupUtils.getDatabaseName(db);

            if (!adapter.isUsersSupported()) {
                if (adapter.isDescribeDatabasesSupported()) {
                    log.info("Describe database {} by adapter {}", dbName, adapter.identifier());
                    DescribedDatabase describedDatabase = describeDatabase(adapter, dbName);
                    db.setConnectionProperties(describedDatabase.getConnectionProperties());
                    db.setResources(describedDatabase.getResources());
                    databaseRegistryDbaasRepository.saveInternalDatabase(db);
                    log.info("Database {} described and saved", dbName);
                }
                res.skipped++;
                return Arrays.asList(ADAPTER_DOES_NOT_SUPPORT_ENSURE);
            }
            try {
                List<EnsuredUser> users = db.getConnectionProperties().stream().map(v -> {
                    String password = null;
                    String username = regenerateCredentials ? null : (String) v.get("username");
                    log.info("User {} with Role {} would be ensured to have service access to database {}", username == null ? "<NEW>" : username, v.get(ROLE), dbName);
                    String role;
                    if (v.get(ROLE) instanceof String) {
                        role = (String) v.get(ROLE);
                    } else {
                        log.error("Database connection property contains not supported role type. Expected type is {}", String.class);
                        throw new IllegalArgumentException("Database connection property contains not supported role type. Expected type is " + String.class);
                    }
                    try {
                        password = regenerateCredentials ? null : encryption.getDecryptedPasswordForBackup(db.getDatabase(), role);
                    } catch (DecryptException e) {
                        log.info("Error during password decryption of database {}. New password will be generated in adapter", dbName);
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
                            log.warn("Failed try №{} to ensure user", count, e);
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
                databaseRegistryDbaasRepository.saveInternalDatabase(db);
                log.info("Users {} ensured access to db {}",
                    users.stream()
                        .map(EnsuredUser::getName)
                        .toList(),
                    dbName
                );
                return users;
            } catch (Exception e) {
                log.error("Failed to ensure user for database {}", db, e);
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
        log.debug("Registered {} databases in namespace {}", currentRegisteredDatabases.size(), backup.getNamespace());
        Map<String, List<DatabaseRegistry>> groupedCurrentDatabases = currentRegisteredDatabases.stream()
                .collect(Collectors.groupingBy(dbr -> dbr.getDatabase().getAdapterId()));

        Set<String> disabledDatabases = backup.getDatabases().stream()
            .filter(Database::getBackupDisabled)
            .map(DbaasBackupUtils::getDatabaseName)
            .collect(Collectors.toSet());

        return backup.getBackups().stream().map(databasesBackup -> {
            List<DatabaseRegistry> adapterCurrentDatabases = groupedCurrentDatabases.get(databasesBackup.getAdapterId());

            if (adapterCurrentDatabases == null) {
                log.debug("For adapter {} no databases found to remove during backup {} restoration", databasesBackup.getAdapterId(), backup.getId());
                return Collections.<DatabaseRegistry>emptyList();
            } else {
                List<DatabaseRegistry> adapterCurrentDatabasesDelta = adapterCurrentDatabases.stream()
                        .filter(current -> !databasesBackup.getDatabases().contains(DbaasBackupUtils.getDatabaseName(current))
                            && !disabledDatabases.contains(DbaasBackupUtils.getDatabaseName(current))
                        )
                        .collect(Collectors.toList());
                if (log.isDebugEnabled()) {
                    log.debug("For adapter {} during restoration of backup {} calculated databases delta to remove: {}",
                        databasesBackup.getAdapterId(),
                        backup.getId(),
                        adapterCurrentDatabasesDelta.stream()
                            .map(DbaasBackupUtils::getDatabaseName)
                            .toList()
                    );
                }
                return adapterCurrentDatabasesDelta;
            }
        }).flatMap(Collection::stream).collect(Collectors.toList());
    }

}
