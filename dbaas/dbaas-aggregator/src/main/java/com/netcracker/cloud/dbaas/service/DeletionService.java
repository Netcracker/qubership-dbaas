package com.netcracker.cloud.dbaas.service;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.netcracker.cloud.dbaas.DatabaseType;
import com.netcracker.cloud.dbaas.entity.pg.*;
import com.netcracker.cloud.dbaas.entity.shared.AbstractDbState;
import com.netcracker.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.DatabaseDeclarativeConfigRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.LogicalDbOperationErrorRepository;
import com.netcracker.cloud.dbaas.service.composite.CompositeNamespaceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.NotEmpty;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;

import java.util.*;
import java.util.function.Predicate;

@Slf4j
@ApplicationScoped
public class DeletionService {
    public static final String MARKED_FOR_DROP = "MARKED_FOR_DROP";

    @Inject
    LogicalDbDbaasRepository logicalDbDbaasRepository;
    @Inject
    PasswordEncryption encryption;
    @Inject
    DatabaseRolesService databaseRolesService;
    @Inject
    LogicalDbOperationErrorRepository logicalDbOperationErrorRepository;
    @Inject
    DatabaseDeclarativeConfigRepository declarativeConfigRepository;
    @Inject
    BalancingRulesService balancingRulesService;
    @Inject
    CompositeNamespaceService compositeNamespaceService;
    @Inject
    UserService userService;
    @Inject
    DBaaService dBaaService;
    @Inject
    AsyncOperations asyncOperations;


    public static boolean isMarkedForDrop(DatabaseRegistry registry) {
        return registry.getDatabase().isMarkedForDrop() || registry.getClassifier().containsKey(MARKED_FOR_DROP);
    }

    public static boolean isOrphan(DatabaseRegistry registry) {
        return registry.getDatabase().getDbState().getDatabaseState() == AbstractDbState.DatabaseStateStatus.ORPHAN
                && registry.getClassifier().containsKey(MARKED_FOR_DROP);
    }

    /**
     * DELETING status means that immediate deletion action will be performed for this DB.
     */
    @SuppressWarnings("deprecation")
    private void markRegistryForDropWithoutTransaction(DatabaseRegistry registry) {
        registry.getClassifier().put(MARKED_FOR_DROP, MARKED_FOR_DROP);

        Database database = registry.getDatabase();
        long notDroppedClassifiersCount = database.getDatabaseRegistry().stream().filter(Predicate.not(DeletionService::isMarkedForDrop)).count();
        if (notDroppedClassifiersCount == 0) {
            database.setClassifier(new TreeMap<>(registry.getClassifier()));
            database.setMarkedForDrop(true);
            database.getDbState().setDatabaseState(DbState.DatabaseStateStatus.DELETING);
        }
    }

    @Transactional
    public DatabaseRegistry markRegistryForDrop(DatabaseRegistry registry) {
        markRegistryForDropWithoutTransaction(registry);
        return logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAnyTypeLogDb(registry);
    }

    @Transactional
    public List<DatabaseRegistry> markRegistriesForDrop(String namespace, List<DatabaseRegistry> registries) {
        StructuredLog.info(log, "Mark registries for drop in '' namespace", "arg0", registries.size(), "namespace", namespace);
        registries.forEach(this::markRegistryForDropWithoutTransaction);
        return logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAll(registries);
    }

    @Transactional
    public int markNamespaceRegistriesForDrop(String namespace) {
        List<DatabaseRegistry> registries = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findAnyLogDbRegistryTypeByNamespace(namespace);
        markRegistriesForDrop(namespace, registries);
        return registries.size();
    }

    /**
     * Marking entire logical database and all it's registries for drop and sets an ORPHAN status.
     * ORPHAN status means that this database was discontinued due to some operation and will usually be deleted only after manual request.
     */
    @SuppressWarnings("deprecation")
    private void markDatabaseAsOrphanWithoutTransaction(DatabaseRegistry registry) {
        Database database = registry.getDatabase();
        database.getDatabaseRegistry().forEach(r -> r.getClassifier().put(MARKED_FOR_DROP, MARKED_FOR_DROP));
        database.setClassifier(new TreeMap<>(registry.getClassifier()));
        database.setMarkedForDrop(true);
        database.getDbState().setDatabaseState(DbState.DatabaseStateStatus.ORPHAN);
    }

    @Transactional
    public void markDatabaseAsOrphan(DatabaseRegistry registry) {
        markDatabaseAsOrphanWithoutTransaction(registry);
        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAnyTypeLogDb(registry);
    }

    @Transactional
    public void markDatabasesAsOrphan(List<DatabaseRegistry> registries) {
        StructuredLog.info(log, "Mark databases as orphan", "arg0", registries.size());
        registries.forEach(this::markDatabaseAsOrphanWithoutTransaction);
        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAll(registries);
    }

    @Transactional
    public boolean dropRegistrySafe(DatabaseRegistry databaseRegistry) {
        return dropRegistrySafe(databaseRegistry, false);
    }

    @Transactional
    public boolean dropRegistrySafe(DatabaseRegistry databaseRegistry, boolean force) {
        try {
            dropRegistry(databaseRegistry.getId(), force);
        } catch (Exception ex) {
            StructuredLog.error(log, "Error happened during dropping registry", "arg0", databaseRegistry.getId());
            registerDeletionError(ex, databaseRegistry);
            return false;
        }
        return true;
    }

    @Transactional
    public long dropRegistriesSafe(String namespace, List<DatabaseRegistry> registriesForDrop) {
        return dropRegistriesSafe(namespace, registriesForDrop, false);
    }

    @Transactional
    public long dropRegistriesSafe(String namespace, List<DatabaseRegistry> registriesForDrop, boolean force) {
        long droppedCount = registriesForDrop.stream()
                .map(databaseRegistry -> dropRegistrySafe(databaseRegistry, force))
                .filter(Boolean::booleanValue)
                .count();
        StructuredLog.info(log, "Successfully dropped of databases in", "droppedCount", droppedCount, "arg1", registriesForDrop.size(), "namespace", namespace);
        return droppedCount;
    }

    private void dropRegistry(UUID registryId, boolean force) {
        Optional<DatabaseRegistry> optionalRegistry = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findDatabaseRegistryById(registryId);
        if (optionalRegistry.isEmpty()) {
            StructuredLog.warn(log, "Registry with id does not exist, no deletion performed", "registryId", registryId);
            return;
        }

        DatabaseRegistry databaseRegistry = optionalRegistry.get();
        StructuredLog.info(log, "Drop registry in '' namespace with classifier '' and type ''", "arg0", databaseRegistry.getId(), "namespace", databaseRegistry.getNamespace(), "classifier", databaseRegistry.getClassifier(), "type", databaseRegistry.getType());

        if (databaseRegistry.getDatabase().getDatabaseRegistry().size() == 1) {
            Database database = databaseRegistry.getDatabase();
            StructuredLog.info(log, "Drop logical database with name '' along with registry", "arg0", DBaaService.getDatabaseName(database), "registryId", registryId);
            try {
                dropDatabaseInAdapter(databaseRegistry);
            } catch (Exception e) {
                if (!force) {
                    throw e;
                } else {
                    StructuredLog.warn(log, "Ignored error during dropping database with name '' in the respective physical adapter", "arg0", DBaaService.getDatabaseName(database));
                }
            }
            encryption.deletePassword(database);
            StructuredLog.info(log, "Successfully dropped logical database with name ''", "arg0", DBaaService.getDatabaseName(database));
        }
        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().delete(databaseRegistry);
        StructuredLog.info(log, "Successfully dropped registry", "registryId", registryId);
    }

    private void dropDatabaseInAdapter(DatabaseRegistry databaseRegistry) {
        if (databaseRegistry.isExternallyManageable()) {
            log.info("Dropping in adapter skipped because local database is externally manageable");
            return;
        }

        userService.deleteDatabaseUsers(databaseRegistry.getDatabase());

        if (CollectionUtils.isEmpty(databaseRegistry.getDatabase().getResources())) {
            log.info("Dropping in adapter skipped because local database doesn't contain any resources");
            return;
        }
        Optional<DbaasAdapter> adapter = dBaaService.getAdapter(databaseRegistry.getDatabase().getAdapterId());
        if (adapter.isPresent()) {
            adapter.get().dropDatabase(databaseRegistry);
        } else {
            if (DbState.DatabaseStateStatus.PROCESSING.equals(databaseRegistry.getDbState().getDatabaseState())) {
                log.warn("Deleting database with PROCESSING status");
            } else {
                throw new RuntimeException("Failed to find appropriate adapter");
            }
        }
    }

    public List<DatabaseRegistry> getOrphanRegistries(List<String> namespaces) {
        return namespaces.stream().map(this::getOrphanRegistries).flatMap(Collection::stream).toList();
    }

    public List<DatabaseRegistry> getOrphanRegistries(String namespace) {
        return logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findAnyLogDbRegistryTypeByNamespace(namespace)
                .stream().filter(DeletionService::isOrphan).toList();
    }

    public List<DatabaseRegistry> getMarkedForDropRegistries(List<String> namespaces) {
        return namespaces.stream().map(this::getMarkedForDropRegistries).flatMap(Collection::stream).toList();
    }

    public List<DatabaseRegistry> getMarkedForDropRegistries(String namespace) {
        return logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findAnyLogDbRegistryTypeByNamespace(namespace)
                .stream().filter(DeletionService::isMarkedForDrop).toList();
    }

    @Transactional
    public void cleanupNamespaceResources(String namespace, boolean removeRules) {
        StructuredLog.info(log, "Remove declarative configs in namespace", "namespace", namespace);
        declarativeConfigRepository.deleteByNamespace(namespace);
        if (removeRules) {
            StructuredLog.info(log, "Remove balancing rules in namespace", "namespace", namespace);
            balancingRulesService.removeRulesByNamespace(namespace);
            balancingRulesService.removePerMicroserviceRulesByNamespace(namespace);
        }
        StructuredLog.info(log, "Remove database roles in namespace", "namespace", namespace);
        databaseRolesService.removeDatabaseRole(namespace);
    }

    /**
     * Called during Clean Install
     */
    @Transactional
    public CleanupResult cleanupNamespaceFullAsync(String namespace, boolean removeRules) {
        compositeNamespaceService.deleteNamespace(namespace);
        markNamespaceRegistriesForDrop(namespace);
        CleanupResult cleanupResult = cleanupMarkedForDropRegistries(namespace);
        cleanupNamespaceResources(namespace, removeRules);
        return cleanupResult;
    }

    public boolean checkNamespaceAlreadyDropped(String namespace) {
        List<DatabaseRegistry> namespaceRegistries = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findAnyLogDbRegistryTypeByNamespace(namespace);
        return namespaceRegistries.isEmpty() && !areRulesExistingInNamespace(namespace)
                && areDeclarativeConfigurationByNamespaceEmpty(namespace);
    }

    private boolean areRulesExistingInNamespace(String namespace) {
        return balancingRulesService.areRulesExistingInNamespace(namespace);
    }

    private boolean areDeclarativeConfigurationByNamespaceEmpty(String namespace) {
        List<DatabaseDeclarativeConfig> allByNamespace = declarativeConfigRepository.findAllByNamespace(namespace);
        return allByNamespace == null || allByNamespace.isEmpty();
    }

    public void cleanupOrphanRegistriesAsync(String namespace) {
        List<DatabaseRegistry> orphanRegistries = getOrphanRegistries(namespace);
        dropRegistriesAsync(namespace, orphanRegistries, false);
    }

    public void cleanupMarkedForDropRegistriesAsync(String namespace, boolean force) {
        List<DatabaseRegistry> markedForDropRegistries = getMarkedForDropRegistries(namespace);
        dropRegistriesAsync(namespace, markedForDropRegistries, force);
    }

    public CleanupResult cleanupMarkedForDropRegistries(String namespace) {
        List<DatabaseRegistry> markedForDropRegistries = getMarkedForDropRegistries(namespace);
        List<DatabaseRegistry> opensearchDBs = markedForDropRegistries.stream()
                .filter(registry -> DatabaseType.OPENSEARCH.toString().equalsIgnoreCase(registry.getType())).toList();
        if (!opensearchDBs.isEmpty()) {
            // Some OpenSearch DBs cas have a not unique prefix, which will prevent creation of a new OpenSearch DB with the same prefix.
            // So, we are removing OpenSearch DBs synchronously to not fail the next DB creation with the same prefix.
            StructuredLog.info(log, "There is OpenSearch logical DBs, start synchronous dropping", "arg0", opensearchDBs.size());
            markedForDropRegistries = markedForDropRegistries.stream().filter(Predicate.not(opensearchDBs::contains)).toList();
            dropRegistriesSafe(namespace, opensearchDBs);
        }
        dropRegistriesAsync(namespace, markedForDropRegistries, false);
        return new CleanupResult(opensearchDBs.size(), markedForDropRegistries.size());
    }

    private void dropRegistriesAsync(String namespace, List<DatabaseRegistry> registriesForDrop, boolean force) {
        StructuredLog.info(log, "Schedule async databases dropping in namespace", "namespace", namespace);
        asyncOperations.getCleanupExecutor().submit(asyncOperations.wrapWithContext(() -> {
            StructuredLog.info(log, "Start async databases dropping in", "namespace", namespace);
            dropRegistriesSafe(namespace, registriesForDrop, force);
            StructuredLog.info(log, "Async databases dropping finished for", "namespace", namespace);
        }));
    }

    public void cleanupAllLogicalDatabasesInNamespacesByPortionsAsync(@NotEmpty Set<String> namespaces) {
        StructuredLog.info(log, "Scheduling async deletion of logical databases in namespaces", "namespaces", namespaces);
        asyncOperations.getCleanupExecutor().submit(asyncOperations.wrapWithContext(() -> {
            cleanupAllLogicalDatabasesInNamespacesByPortions(namespaces);
        }));
    }

    private void cleanupAllLogicalDatabasesInNamespacesByPortions(Set<String> namespaces) {
        var allSuccessfullyDeletedLogicalDatabaseIdsAmount = 0;
        var allSkippedDeletedLogicalDatabaseIdsAmount = 0;
        var allFailedDeletedLogicalDatabaseIdsAmount = 0;

        StructuredLog.info(log, "Started deletion of all logical databases in namespaces", "namespace", namespaces.size(), "namespaces", namespaces);
        namespaces.forEach(this::markNamespaceRegistriesForDrop);

        var portionNumber = 0;
        List<Database> logicalDatabases;

        do {
            logicalDatabases = logicalDbDbaasRepository.getDatabaseDbaasRepository().findByNamespacesWithOffsetBasedPagination(
                    namespaces, allFailedDeletedLogicalDatabaseIdsAmount, 100 + allFailedDeletedLogicalDatabaseIdsAmount
            );

            if (CollectionUtils.isNotEmpty(logicalDatabases)) {
                portionNumber += 1;

                var logicalDatabasesIds = logicalDatabases.stream()
                        .map(Database::getId)
                        .toList();

                StructuredLog.info(log, "Started deletion of logical databases by portion with number , logical databases ids", "arg0", logicalDatabasesIds.size(), "portionNumber", portionNumber, "logicalDatabasesIds", logicalDatabasesIds);

                var failedDeleteLogicalDatabases = new ArrayList<Database>();

                for (var logicalDatabase : logicalDatabases) {
                    var logicalDatabaseId = logicalDatabase.getId();
                    StructuredLog.info(log, "Started deletion of logical database with id", "logicalDatabaseId", logicalDatabaseId);
                    List<DatabaseRegistry> registriesForDrop = ListUtils.emptyIfNull(logicalDatabase.getDatabaseRegistry());

                    long dropped = dropRegistriesSafe("some namespaces", registriesForDrop);

                    if (dropped != registriesForDrop.size()) {
                        failedDeleteLogicalDatabases.add(logicalDatabase);
                        StructuredLog.error(log, "Only of registries was deleted for logical database with id", "dropped", dropped, "arg1", registriesForDrop.size(), "logicalDatabaseId", logicalDatabaseId);
                    } else {
                        StructuredLog.info(log, "Finished deletion of logical database with id", "logicalDatabaseId", logicalDatabaseId);
                    }
                }

                var failedDeleteLogicalDatabasesIds = failedDeleteLogicalDatabases.stream()
                        .map(Database::getId)
                        .toList();

                var successfullyDeletedLogicalDatabasesIds = logicalDatabases.stream()
                        .map(Database::getId)
                        .filter(Predicate.not(failedDeleteLogicalDatabasesIds::contains))
                        .toList();

                allSuccessfullyDeletedLogicalDatabaseIdsAmount += successfullyDeletedLogicalDatabasesIds.size();
                allFailedDeletedLogicalDatabaseIdsAmount += failedDeleteLogicalDatabasesIds.size();

                StructuredLog.info(log, """ Finished deletion of logical databases by portion with number , successfully deleted logical databases with ids , \ failed deleted ones with ids """, "arg0", logicalDatabasesIds.size(), "portionNumber", portionNumber, "arg2", successfullyDeletedLogicalDatabasesIds.size(), "successfullyDeletedLogicalDatabasesIds", successfullyDeletedLogicalDatabasesIds, "arg4", failedDeleteLogicalDatabasesIds.size(), "failedDeleteLogicalDatabasesIds", failedDeleteLogicalDatabasesIds);
            }
        } while (CollectionUtils.isNotEmpty(logicalDatabases));

        var allLogicalDatabasesAmount = allSuccessfullyDeletedLogicalDatabaseIdsAmount
                + allSkippedDeletedLogicalDatabaseIdsAmount + allFailedDeletedLogicalDatabaseIdsAmount;

        StructuredLog.info(log, """ Finished deletion of all logical databases in namespaces , successfully deleted logical databases, \ skipped deletion of ones, failed deleted ones""", "allLogicalDatabasesAmount", allLogicalDatabasesAmount, "namespace", namespaces.size(), "namespaces", namespaces, "allSuccessfullyDeletedLogicalDatabaseIdsAmount", allSuccessfullyDeletedLogicalDatabaseIdsAmount, "allSkippedDeletedLogicalDatabaseIdsAmount", allSkippedDeletedLogicalDatabaseIdsAmount, "allFailedDeletedLogicalDatabaseIdsAmount", allFailedDeletedLogicalDatabaseIdsAmount);
    }

    private void registerDeletionError(Exception ex, DatabaseRegistry databaseRegistry) {
        StructuredLog.warn(log, "Register deletion error: '' of database with classifier", "error", ex.getMessage(), "arg1", DBaaService.getDatabaseName(databaseRegistry.getDatabase()), "classifier", databaseRegistry.getClassifier());
        try {
            int status = 0;
            if (ex instanceof WebApplicationException) {
                status = ((WebApplicationException) ex).getResponse().getStatus();
            }
            if (databaseRegistry.getDatabase().getDatabaseRegistry().size() < 2) {
                databaseRegistry.getDatabase().getDbState().setDatabaseState(DbState.DatabaseStateStatus.DELETING_FAILED);
                logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAnyTypeLogDb(databaseRegistry);
            }

            logicalDbOperationErrorRepository.persist(new LogicalDbOperationError(UUID.randomUUID(), databaseRegistry.getDatabase(), new Date(), ex.getMessage(), status, LogicalDbOperationError.Operation.DELETE));
        } catch (Exception e) {
            log.error("Can't register DB deletion error", e);
        }
    }

    public record CleanupResult(int databasesSyncDeletedCount, int databasesAsyncDeletionScheduledCount) {
    }
}
