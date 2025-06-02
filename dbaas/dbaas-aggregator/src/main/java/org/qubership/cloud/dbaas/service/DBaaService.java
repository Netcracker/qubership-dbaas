package org.qubership.cloud.dbaas.service;

import com.cronutils.utils.Preconditions;
import org.qubership.cloud.context.propagation.core.ContextManager;
import org.qubership.cloud.dbaas.dto.*;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.*;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import org.qubership.cloud.dbaas.exceptions.*;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseHistoryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseDeclarativeConfigRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.LogicalDbOperationErrorRepository;
import org.qubership.cloud.framework.contexts.xrequestid.XRequestIdContextObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.NotEmpty;
import jakarta.ws.rs.WebApplicationException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.cloud.dbaas.Constants.*;
import static org.qubership.cloud.dbaas.DbaasApiPath.VERSION_1;
import static org.qubership.cloud.dbaas.service.PasswordEncryption.PASSWORD_FIELD;
import static org.qubership.cloud.framework.contexts.xrequestid.XRequestIdContextObject.X_REQUEST_ID;

@Slf4j
@ApplicationScoped
public class DBaaService {
    public static final String MARKED_FOR_DROP = "MARKED_FOR_DROP";

    @Inject
    PhysicalDatabasesService physicalDatabasesService;
    @Inject
    BalancingRulesService balancingRulesService;
    @Inject
    PasswordEncryption encryption;
    @Inject
    LogicalDbDbaasRepository logicalDbDbaasRepository;
    @Inject
    DatabaseHistoryDbaasRepository databaseHistoryDbaasRepository;
    @Inject
    LogicalDbOperationErrorRepository logicalDbOperationErrorRepository;
    @Inject
    EntityManager entityManager;
    @Inject
    UserService userService;
    @Inject
    DatabaseDeclarativeConfigRepository declarativeConfigRepository;
    @Inject
    DbaaSHelper dbaaSHelper;
    @Inject
    ProcessConnectionPropertiesService connectionPropertiesService;
    @Inject
    DatabaseRolesService databaseRolesService;

    private ExecutorService asyncExecutorService = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void init() {
        userService.setDBaaService(this);
    }

    private static final String MESSAGE_FAILED_TO_DECRYPT_PASSWORD = "Failed to decrypt password for database with classifier: {}";
    private static final String SCOPE = "scope";

    public Optional<CreatedDatabaseV3> createDatabase(AbstractDatabaseCreateRequest request, String namespace, String microserviceName) {
        log.debug("Creating database by request {} at namespace {} for microserviceName {}", request, namespace, microserviceName);
        return getAdapter(request, namespace, microserviceName)
                .map(adapter -> {
                    log.debug("Checking adapter existence with {} type", adapter.type());
                    request.getClassifier().put("namespace", namespace);
                    CreatedDatabaseV3 databaseV3;
                    if (VERSION_1.equals(adapter.getSupportedVersion())) {
                        CreatedDatabase database = adapter.createDatabase(request, microserviceName);
                        databaseV3 = new CreatedDatabaseV3(database);
                    } else {
                        databaseV3 = adapter.createDatabaseV3(request, microserviceName);
                    }
                    databaseV3.setAdapterId(adapter.identifier());
                    log.debug("Created database from adapter = {}", databaseV3);
                    return databaseV3;
                });
    }

    public boolean isAdapterExists(AbstractDatabaseCreateRequest request, String namespace, String microserviceName) {
        try {
            return getAdapter(request, namespace, microserviceName).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private Optional<DbaasAdapter> getAdapter(AbstractDatabaseCreateRequest request, String namespace, String microserviceName) {
        return getAdapter(request.getPhysicalDatabaseId(), request.getType(), namespace, microserviceName);
    }

    private Optional<DbaasAdapter> getAdapter(String physicalDbId, String type,
                                              String namespace, String microserviceName) {
        if (StringUtils.isNotBlank(physicalDbId)) {
            PhysicalDatabase physicalDatabaseRegistration =
                    physicalDatabasesService.getByPhysicalDatabaseIdentifier(physicalDbId);
            if (physicalDatabaseRegistration == null) {
                throw new UnregisteredPhysicalDatabaseException(Source.builder().pointer("/physicalDatabaseId").build(),
                        "Identifier: " + physicalDbId);
            }
            String adapterId = physicalDatabaseRegistration.getAdapter().getAdapterId();
            log.info("Physical database determined by request: {}", physicalDatabaseRegistration);
            return Optional.of(physicalDatabasesService.getAdapterById(adapterId));
        }

        PhysicalDatabase physicalDatabase = balancingRulesService.applyMicroserviceBalancingRule(namespace, microserviceName, type);
        if (physicalDatabase == null) {
            log.debug("Per microservice rule is not exist, try to apply per namespace rule");
            physicalDatabase = balancingRulesService.applyNamespaceBalancingRule(namespace, type);
        }

        if (physicalDatabase == null) {
            log.error("Unable to determine physical database from rules for microservice '{}' in namespace '{}'", microserviceName, namespace);
            throw new NoBalancingRuleException(ErrorCodes.CORE_DBAAS_4033, ErrorCodes.CORE_DBAAS_4033.getDetail(microserviceName, namespace));
        }

        log.info("Returning adapter of physical database {}", physicalDatabase.getPhysicalDatabaseIdentifier());
        return Optional.ofNullable(physicalDatabasesService.getAdapterById(physicalDatabase.getAdapter().getAdapterId()));
    }

    public Optional<DbaasAdapter> getAdapter(String adapterId) {
        return physicalDatabasesService.getAllAdapters()
                .stream()
                .filter(dbaasAdapter -> Objects.equals(dbaasAdapter.identifier(), adapterId))
                .findFirst();
    }

    /**
     * Finds database with the specified classifier and type. Returns database with actual id, if {@code withId}
     * is {@code true}, or with null id, if {@code withId} parameter is {@code false}.
     *
     * @param classifier classifier to search for
     * @param type       desired database type
     * @param withId     if {@code true}, returning database will have actual id, otherwise it will have null id
     * @return found database or {@code null}, if there is no database with such classifier and type;
     * returning database will have null id, if {@code withId} parameter equals {@code false}
     */
    @Transactional
    @Nullable
    public DatabaseRegistry findDatabaseByClassifierAndType(Map<String, Object> classifier, String type, boolean withId) {
        Optional<DatabaseRegistry> databaseRegistry = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().getDatabaseByClassifierAndType(classifier, type);
        return databaseRegistry.map(registry -> detachDatabaseRegistry(withId, registry)).orElse(null);
    }

    @Transactional
    public void markVersionedDatabasesAsOrphan(List<DatabaseRegistry> databaseRegistries) {
        databaseRegistries.forEach(dbr -> {
            dbr.getClassifier().put(MARKED_FOR_DROP, MARKED_FOR_DROP);
            SortedMap<String, Object> forDropClassifier = new TreeMap<>(dbr.getClassifier());
            dbr.getDatabase().setClassifier(forDropClassifier);
            dbr.getDatabase().getDbState().setDatabaseState(DbState.DatabaseStateStatus.ORPHAN);
        });
        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAll(databaseRegistries);
    }

    public void markDatabasesAsOrphan(DatabaseRegistry databaseRegistry) {
        databaseRegistry.getDatabaseRegistry().forEach(registry -> {
                    registry.getClassifier().put(MARKED_FOR_DROP, MARKED_FOR_DROP);
                }
        );
        databaseRegistry.getDatabase().setClassifier(databaseRegistry.getClassifier());
        databaseRegistry.getDatabase().setMarkedForDrop(true);
        databaseRegistry.getDatabase().getDbState().setDatabaseState(DbState.DatabaseStateStatus.ORPHAN);
    }

    public void saveDatabaseRegistry(DatabaseRegistry databaseRegistry) {
        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAnyTypeLogDb(databaseRegistry);
    }

    @Transactional
    public void dropDatabasesAsync(String namespace, List<DatabaseRegistry> databaseRegistries) {
        if (!dbaaSHelper.isProductionMode()) {
            ExecutorService executorService = Executors.newSingleThreadExecutor();

            executorService.submit(() -> {
                log.info("Start async dropping versioned and transactional databases in {}", namespace);
                dropDatabases(databaseRegistries, namespace);
            });
            executorService.shutdown();
        }
    }

    public DatabaseRegistry findDatabaseByOldClassifierAndType(Map<String, Object> classifier, String type, boolean withId) {
        DatabaseRegistry databaseRegistry = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().getDatabaseByOldClassifierAndType(classifier, type);
        return databaseRegistry == null ? null : detachDatabase(withId, databaseRegistry);
    }

    private DatabaseRegistry detachDatabase(boolean withId, DatabaseRegistry databaseRegistry) {
        // must not include uuid in case the db was search by classifier and withId == false
        if (!withId && databaseRegistry != null) {
            entityManager.detach(databaseRegistry);
            databaseRegistry.setId(null);
        }
        return databaseRegistry;
    }

    private DatabaseRegistry detachDatabaseRegistry(boolean withId, DatabaseRegistry databaseRegistry) {
        // must not include uuid in case the db was search by classifier and withId == false
        if (!withId && databaseRegistry != null) {
            entityManager.detach(databaseRegistry);
            entityManager.detach(databaseRegistry.getDatabase());
            databaseRegistry.getDatabase().setId(null);
            databaseRegistry.setId(null);
        }
        return databaseRegistry;
    }

    @Transactional
    public DatabaseRegistry detach(DatabaseRegistry databaseRegistry) {
        entityManager.detach(databaseRegistry);
        entityManager.detach(databaseRegistry.getDatabase());
        return databaseRegistry;
    }

    public boolean isValidClassifierV3(Map<String, Object> classifier) {
        return classifier != null &&
                (classifier.containsKey(SCOPE) &&
                        ((classifier.get(SCOPE).equals(SCOPE_VALUE_TENANT) && classifier.containsKey(TENANT_ID)) ||
                                classifier.get(SCOPE).equals(SCOPE_VALUE_SERVICE)) &&
                        classifier.containsKey(MICROSERVICE_NAME) && classifier.containsKey(NAMESPACE));
    }

    public void dropDatabase(DatabaseRegistry databaseRegistry) {
        userService.deleteDatabaseUsers(databaseRegistry.getDatabase());
        if (databaseRegistry.getType() != null) {
            Optional<DbaasAdapter> adapter = getAdapter(databaseRegistry.getDatabase());
            if (adapter.isPresent()) {
                adapter.get().dropDatabase(databaseRegistry);
            } else {
                if (DbState.DatabaseStateStatus.PROCESSING.equals(databaseRegistry.getDbState().getDatabaseState())) {
                    log.info("Deleting database with PROCESSING status");
                } else {
                    throw new RuntimeException("Failed to find appropriate adapter");
                }
            }
        }
    }

    private Optional<DbaasAdapter> getAdapter(Database database) {
        return getAdapter(database.getAdapterId());
    }

    public boolean checkNamespaceAlreadyDropped(String namespace, List<DatabaseRegistry> namespaceDatabases) {
        return namespaceDatabases.isEmpty() && !areRulesExistingInNamespace(namespace)
                && areDeclarativeConfigurationByNamespaceEmpty(namespace);
    }

    private boolean areDeclarativeConfigurationByNamespaceEmpty(String namespace) {
        List<DatabaseDeclarativeConfig> allByNamespace = declarativeConfigRepository.findAllByNamespace(namespace);
        return allByNamespace == null || allByNamespace.isEmpty();
    }

    @Transactional
    public void dropDatabases(List<DatabaseRegistry> databasesForDrop, String namespace) {
        Long number = databasesForDrop.stream()
                .filter(databaseRegistry -> databaseRegistry.getClassifier().containsKey(MARKED_FOR_DROP))
                .map(dr -> {
                    DatabaseRegistry databaseRegistry = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findDatabaseRegistryById(dr.getId()).get();
                    try {
                        log.info("Drop internal logical databaseRegistry {} in {} with classifier {}",
                                databaseRegistry.getDatabase().getName(), namespace, databaseRegistry.getClassifier());
                        if (databaseRegistry.getDatabase().getDatabaseRegistry().size() < 2) {
                            dropDatabase(databaseRegistry);
                            encryption.deletePassword(databaseRegistry.getDatabase());
                        }
                        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().deleteById(databaseRegistry.getId());
                        log.info("Successfully dropped internal databaseRegistry with id {} and name {}", databaseRegistry.getDatabase().getId(),
                                databaseRegistry.getDatabase().getName());
                        return 1L;
                    } catch (Exception ex) {
                        log.error("Error happened during dropping internal databaseRegistry {} id {}, with message {}",
                                databaseRegistry.getDatabase().getName(), databaseRegistry.getId(), ex.getMessage());
                        registerDeletionError(ex, databaseRegistry);
                        return 0L;
                    }
                }).mapToLong(Long::valueOf).sum();
        log.info("Successfully dropped {} internal databases in {}", number, namespace);
    }

    @Transactional
    public void dropExternalDatabases(List<DatabaseRegistry> externalDatabases) {
        List<DatabaseRegistry> databasesForDrop = externalDatabases
                .stream()
                .filter(databaseRegistry -> databaseRegistry.getClassifier().containsKey(MARKED_FOR_DROP))
                .collect(Collectors.toList());
        databasesForDrop.forEach(this::dropExternalDatabase);
    }

    @Transactional
    public void dropExternalDatabase(DatabaseRegistry databaseRegistry) {
        encryption.deletePassword(databaseRegistry.getDatabase());
        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().delete(databaseRegistry);
    }

    @Transactional
    public void asyncDeleteAllLogicalDatabasesInNamespacesByPortions(@NotEmpty Set<String> namespaces) {
        log.info("Scheduling async deletion of logical databases in namespaces {}", namespaces);

        var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();

        asyncExecutorService.submit(() -> {
            ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));

            deleteAllLogicalDatabasesInNamespacesByPortions(namespaces);
        });
    }

    @Transactional
    public void deleteAllLogicalDatabasesInNamespacesByPortions(Set<String> namespaces) {
        if (dbaaSHelper.isProductionMode()) {

            log.warn("Skipped deletion of logical databases because it is not supported in production mode");
            return;
        }

        var allSuccessfullyDeletedLogicalDatabaseIdsAmount = 0;
        var allSkippedDeletedLogicalDatabaseIdsAmount = 0;
        var allFailedDeletedLogicalDatabaseIdsAmount = 0;

        log.info("Started deletion of all logical databases in {} namespaces {}", namespaces.size(), namespaces);

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

                log.info("Started deletion of {} logical databases by portion with number {}, logical databases ids {}",
                    logicalDatabasesIds.size(), portionNumber, logicalDatabasesIds
                );

                var failedDeleteLogicalDatabases = new ArrayList<Database>();

                for (var logicalDatabase : logicalDatabases) {
                    var logicalDatabaseId = logicalDatabase.getId();

                    try {
                        log.info("Started deletion of logical database with id {}", logicalDatabaseId);

                        for (var databaseRegistry : new ArrayList<>(CollectionUtils.emptyIfNull(logicalDatabase.getDatabaseRegistry()))) {

                            dropDatabase(databaseRegistry);
                            encryption.deletePassword(databaseRegistry.getDatabase());
                            logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().deleteById(databaseRegistry.getId());
                        }

                        log.info("Finished deletion of logical database with id {}", logicalDatabaseId);
                    } catch (Exception ex) {
                        failedDeleteLogicalDatabases.add(logicalDatabase);

                        log.error("Error happened during deletion of logical database with id {}",
                            logicalDatabaseId, ex
                        );
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

                log.info("""
                        Finished deletion of {} logical databases by portion with number {}, successfully deleted {} logical databases with ids {}, \
                        failed deleted {} ones with ids {}""",

                    logicalDatabasesIds.size(), portionNumber, successfullyDeletedLogicalDatabasesIds.size(),
                    successfullyDeletedLogicalDatabasesIds, failedDeleteLogicalDatabasesIds.size(), failedDeleteLogicalDatabasesIds
                );
            }
        } while (CollectionUtils.isNotEmpty(logicalDatabases));

        var allLogicalDatabasesAmount = allSuccessfullyDeletedLogicalDatabaseIdsAmount
            + allSkippedDeletedLogicalDatabaseIdsAmount + allFailedDeletedLogicalDatabaseIdsAmount;

        log.info("""
            Finished deletion of all {} logical databases in {} namespaces {}, successfully deleted {} logical databases, \
            skipped deletion of {} ones, failed deleted {} ones""",

            allLogicalDatabasesAmount, namespaces.size(), namespaces, allSuccessfullyDeletedLogicalDatabaseIdsAmount,
            allSkippedDeletedLogicalDatabaseIdsAmount, allFailedDeletedLogicalDatabaseIdsAmount
        );
    }

    @Transactional
    public Long deleteDatabasesAsync(String namespace, List<DatabaseRegistry> namespaceDatabases, boolean removeRules) {
        log.info("Mark databases for drop in {} namespace", namespace);
        Long number = markForDrop(namespace, namespaceDatabases);
        if (!dbaaSHelper.isProductionMode()) {
            log.info("Schedule async databases dropping in {} namespace", namespace);
            dropDatabaseAsync(namespace);
        }
        log.info("Remove declarative configs in {} namespace", namespace);
        declarativeConfigRepository.deleteByNamespace(namespace);
        if (removeRules) {
            log.info("Remove rules in {} namespace", namespace);
            balancingRulesService.removeRulesByNamespace(namespace);
            balancingRulesService.removePerMicroserviceRulesByNamespace(namespace);
        }
        log.info("Remove database role in {} namespace", namespace);
        databaseRolesService.removeDatabaseRole(namespace);
        return number;
    }

    public Long markForDrop(String namespace, List<DatabaseRegistry> namespaceDatabases) {
        return namespaceDatabases.stream().map(databaseRegistry -> {
            if (databaseRegistry.getDatabase().getDatabaseRegistry().size() > 1) {
                databaseRegistry.getClassifier().put(MARKED_FOR_DROP, MARKED_FOR_DROP);
                logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAnyTypeLogDb(databaseRegistry);
            } else {
                databaseRegistry.getDatabase().getDbState().setDatabaseState(DbState.DatabaseStateStatus.DELETING);
                databaseRegistry.getClassifier().put(MARKED_FOR_DROP, MARKED_FOR_DROP);
                databaseRegistry.getDatabase().setClassifier(databaseRegistry.getClassifier());
                if (databaseRegistry.getDatabase().getOldClassifier() != null) {
                    databaseRegistry.getDatabase().getOldClassifier().put(MARKED_FOR_DROP, MARKED_FOR_DROP);
                }
                databaseRegistry.getDatabase().setMarkedForDrop(true);
                logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAnyTypeLogDb(databaseRegistry);
            }
            log.info("Marked for drop databaseRegistry in {} with classifier {}", namespace, databaseRegistry.getClassifier());
            return 1L;
        }).mapToLong(Long::valueOf).sum();
    }

    protected void dropDatabaseAsync(String namespace) {
        List<DatabaseRegistry> internalDatabasesForDrop = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()
                .findInternalDatabaseRegistryByNamespace(namespace);
        List<DatabaseRegistry> externalDatabasesForDrop = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()
                .findExternalDatabaseRegistryByNamespace(namespace);

        log.info("Submit async task for databases dropping in {} namespace", namespace);
        String requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();
        asyncExecutorService.submit(() -> {
            ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));
            log.info("Start async dropping databases in {}", namespace);
            dropDatabases(internalDatabasesForDrop, namespace);
            log.info("Start async dropping external databases in {}", namespace);
            dropExternalDatabases(externalDatabasesForDrop);
            log.info("Async databases dropping finished for {}", namespace);
        });
    }


    public Boolean isModifiedFields(AbstractDatabaseCreateRequest databaseRequest, Database currentDatabase) {
        if (currentDatabase.isExternallyManageable()) {
            return false;
        }
        return currentDatabase.getBackupDisabled() != null && !databaseRequest.getBackupDisabled().equals(currentDatabase.getBackupDisabled());
    }

    @Transactional
    public PasswordChangeResponse changeUserPassword(PasswordChangeRequestV3 passwordChangeRequest, String namespace) {
        return changeUserPassword(passwordChangeRequest, namespace, null);
    }

    @Transactional
    public PasswordChangeResponse changeUserPassword(PasswordChangeRequestV3 passwordChangeRequest, String namespace, String role) {
        String dbType = passwordChangeRequest.getType();
        List<DatabaseRegistry> databasesForChangePassword = new ArrayList<>();
        if (!MapUtils.isEmpty(passwordChangeRequest.getClassifier())) {
            passwordChangeRequest.getClassifier().put("namespace", namespace);
            if (!isValidClassifierV3(passwordChangeRequest.getClassifier())) {
                throw new PasswordChangeValidationException("PasswordChangeRequest =" + passwordChangeRequest + " contains not valid V3 classifier",
                        Source.builder().pointer("/classifier").build());
            }
            Optional<DatabaseRegistry> databaseRegistry;
            log.info("Password will be changed from one database with classifier {} and type {}", passwordChangeRequest.getClassifier(), passwordChangeRequest.getType());
            databaseRegistry = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().getDatabaseByClassifierAndType(passwordChangeRequest.getClassifier(), dbType);
            if (databaseRegistry.isPresent()) {
                databasesForChangePassword.add(databaseRegistry.get());
            }
        } else {
            databasesForChangePassword = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findInternalDatabaseRegistryByNamespace(namespace)
                    .stream()
                    .filter(databaseRegistry -> databaseRegistry.getType().equals(dbType))
                    .filter(database -> !database.isMarkedForDrop())
                    .collect(Collectors.toList());
            log.info("The password will be change from {} databases which are located in {} namespace and have {} database type", databasesForChangePassword.size(), namespace, dbType);
            log.debug("List of databases {}", databasesForChangePassword);
        }
        Map<DbaasAdapter, Boolean> adaptersAndUserSupportedMap;
        try {
            adaptersAndUserSupportedMap = getAdaptersAndUserSupportedMap(databasesForChangePassword);
            log.debug("Map of adapters and user support opportunities {}", adaptersAndUserSupportedMap);
        } catch (Exception e) {
            throw new UnknownErrorCodeException(e);
        }
        List<String> unsupportedUserAdapters = adaptersAndUserSupportedMap.entrySet().stream()
                .filter(dbaasAdapterBooleanEntry -> !dbaasAdapterBooleanEntry.getValue())
                .map(dbaasAdapterBooleanEntry -> dbaasAdapterBooleanEntry.getKey().adapterAddress())
                .collect(Collectors.toList());
        if (!CollectionUtils.isEmpty(unsupportedUserAdapters)) {
            throw new PasswordChangeValidationException("The following adapters: " + unsupportedUserAdapters + " do not support user password change",
                    Source.builder().build());
        }
        PasswordChangeResponse response;
        response = performChangePassword(databasesForChangePassword, role);
        if (!CollectionUtils.isEmpty(response.getFailed())) {
            throw new PasswordChangeFailedException(response, response.getFailedHttpStatus());
        } else {
            return response;
        }
    }

    @Transactional
    public DatabaseRegistry updateDatabaseConnectionProperties(UpdateConnectionPropertiesRequest updateConnectionPropertiesRequest, String type) {
        Optional<DatabaseRegistry> databaseRegistry = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().getDatabaseByClassifierAndType(updateConnectionPropertiesRequest.getClassifier(), type);
        return getDatabase(updateConnectionPropertiesRequest, databaseRegistry.orElseThrow());
    }

    private DatabaseRegistry getDatabase(UpdateConnectionPropertiesRequest updateConnectionPropertiesRequest, DatabaseRegistry databaseRegistry) {
        Preconditions.checkNotNull(databaseRegistry, "Database for updating must present");
        String updatedDbName = updateConnectionPropertiesRequest.getDbName();
        List<DbResource> updatedResources = updateConnectionPropertiesRequest.getResources();
        String role = (String) updateConnectionPropertiesRequest.getConnectionProperties().get(ROLE);
        encryption.deletePassword(databaseRegistry.getDatabase(), role);
        List<Map<String, Object>> updatedConnectionProperties = ConnectionPropertiesUtils.replaceConnectionProperties(role,
                databaseRegistry.getConnectionProperties(), updateConnectionPropertiesRequest.getConnectionProperties());
        databaseRegistry.setConnectionProperties(updatedConnectionProperties);
        if (updateConnectionPropertiesRequest.getPhysicalDatabaseId() != null &&
                !updateConnectionPropertiesRequest.getPhysicalDatabaseId().isEmpty()) {
            PhysicalDatabase physicalDatabase = physicalDatabasesService.getByPhysicalDatabaseIdentifier(updateConnectionPropertiesRequest.getPhysicalDatabaseId());
            if (physicalDatabase == null) {
                throw new UnregisteredPhysicalDatabaseException(Source.builder().pointer("/physicalDatabaseId").build(),
                        "Identifier: " + updateConnectionPropertiesRequest.getPhysicalDatabaseId());
            }
            log.info("Physical database determined by request: {}", physicalDatabase);
            String adapterId = physicalDatabase.getAdapter().getAdapterId();
            databaseRegistry.setPhysicalDatabaseId(updateConnectionPropertiesRequest.getPhysicalDatabaseId());

            databaseRegistry.setAdapterId(adapterId);
        }
        if (updatedDbName != null && !updatedDbName.isEmpty()) {
            DatabaseRegistry finalDatabase = databaseRegistry;
            boolean isDbNameProvidedInOldResources = databaseRegistry.getResources().stream()
                    .anyMatch(dbResource -> dbResource.getName().equals(finalDatabase.getName()));
            if (updatedResources != null && !updatedResources.isEmpty()) {
                boolean isDbNameProvidedInUpdatedResources = updatedResources.stream()
                        .anyMatch(dbResource -> (dbResource.getName().equals(updatedDbName)));
                if (isDbNameProvidedInOldResources && !isDbNameProvidedInUpdatedResources) {
                    log.error("New resources does not contains new database name, which equals to dbName in request, 'resources': {}", updatedResources);
                    throw new InvalidUpdateConnectionPropertiesRequestException("New resources does not contains new database name",
                            Source.builder().build());
                }
            } else {
                if (isDbNameProvidedInOldResources) {
                    log.error("For changing dbName provide new resources with kind 'name' too, 'request': {}", updateConnectionPropertiesRequest);
                    throw new InvalidUpdateConnectionPropertiesRequestException("New resources does not contains new database name",
                            Source.builder().build());
                }
            }
            databaseRegistry.setName(updatedDbName);

        }
        if (updatedResources != null && !updatedResources.isEmpty()) {
            if (databaseRegistry.getResources().size() > updatedResources.size()) {
                log.error("New resources size must be no less than the size of the previous ones. 'resources': {}", updatedResources);
                throw new InvalidUpdateConnectionPropertiesRequestException("New resources size must be no less than the size of the previous ones.",
                        Source.builder().build());
            }
            databaseRegistry.setResources(updatedResources);
        }

        Optional<DbaasAdapter> adapter = getAdapter(databaseRegistry.getDatabase());
        encryption.encryptPassword(databaseRegistry.getDatabase(), role);
        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAnyTypeLogDb(databaseRegistry);
        return databaseRegistry;
    }

    PasswordChangeResponse performChangePassword(List<DatabaseRegistry> databasesForChangePassword, @Nullable String role) {
        log.info("Change password {}",
                role == null ? "for whole database roles" : "for role=" + role);
        PasswordChangeResponse response = new PasswordChangeResponse();
        long count = databasesForChangePassword.stream().map(databaseRegistry -> {
            Database database = databaseRegistry.getDatabase();
            long sum = 0L;
            DbaasAdapter adapter = getAdapter(database).get();
            List<Map<String, Object>> connectionProperties = database.getConnectionProperties();
            if (role != null) {
                connectionProperties = connectionProperties.stream()
                        .filter(cp -> cp.get(ROLE) instanceof String && role.equalsIgnoreCase((String) cp.get(ROLE)))
                        .collect(Collectors.toList());
            }
            for (Map<String, Object> cp : connectionProperties) {
                try {
                    String dbName = database.getName();
                    String password = null;
                    EnsuredUser ensuredUser;
                    ensuredUser = recreateUsers(adapter, (String) cp.get("username"), dbName, password, (String) cp.get(ROLE));

                    log.info("Get resources {}", ensuredUser.getConnectionProperties());
                    encryption.deletePassword(database, (String) cp.get(ROLE));
                    List<Map<String, Object>> replaceConnectionProperties = ConnectionPropertiesUtils.replaceConnectionProperties((String) cp.get(ROLE), database.getConnectionProperties(), ensuredUser.getConnectionProperties());
                    database.setConnectionProperties(replaceConnectionProperties);

                    database.setResources(getMergedResources(database.getResources(), ensuredUser.getResources()));


                    response.putSuccessEntity(databaseRegistry.getClassifier(), new HashMap<>(ConnectionPropertiesUtils.getConnectionProperties(database.getConnectionProperties(), (String) cp.get(ROLE))));
                    encryption.encryptPassword(database, (String) cp.get(ROLE));
                    logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveInternalDatabase(databaseRegistry);
                    log.info("The password was changed successfully from database with classifier {} and type {} and role {}", databaseRegistry.getClassifier(), databaseRegistry.getType(), (String) cp.get(ROLE));
                    sum += 1L;
                } catch (WebApplicationException e) {
                    response.putFailedEntity(databaseRegistry.getClassifier(), e.getMessage());
                    log.error("Faled during change password from database with classifier {} and type {} and role {}. Error: ", databaseRegistry.getClassifier(), databaseRegistry.getType(), (String) cp.get(ROLE), e);
                    if (e.getResponse().getStatus() > response.getFailedHttpStatus()) {
                        response.setFailedHttpStatus(e.getResponse().getStatus());
                    }
                }
            }
            return sum;
        }).mapToLong(Long::valueOf).sum();
        log.info("From {} databases was changed password", count);
        return response;
    }

    public boolean decryptPassword(Database database) {
        return encryption.decryptPassword(database);
    }

    public String providePasswordFor(Database database, String role) {
        String dbPassword = null;
        Optional<Map<String, Object>> connectionProperties = Optional.empty();
        if (database.getConnectionProperties() != null && !database.getConnectionProperties().isEmpty()) {
            connectionProperties = ConnectionPropertiesUtils.getSafeConnectionProperties(database.getConnectionProperties(), role);
        }
        if (connectionProperties.isPresent()) {
            dbPassword = ((String) connectionProperties.get().get(PASSWORD_FIELD));
        }
        return dbPassword;
    }

    public EnsuredUser recreateUsers(DbaasAdapter adapter, String username, String dbName, String password, String role) {
        EnsuredUser ensuredUser;
        if (VERSION_1.equals(adapter.getSupportedVersion())) {
            ensuredUser = adapter.ensureUser(username, password, dbName);
            putUserIfAbsent(ensuredUser, Role.ADMIN.toString());
        } else {
            ensuredUser = adapter.ensureUser(username, password, dbName, role);
            putUserIfAbsent(ensuredUser, role);
        }
        return ensuredUser;
    }

    private void putUserIfAbsent(EnsuredUser ensuredUser, String defaultRole) {
        String role = (String) ensuredUser.getConnectionProperties().get(ROLE);
        if (role == null || role.isEmpty()) {
            ensuredUser.getConnectionProperties().put(ROLE, defaultRole);
        }
    }

    protected List<DbResource> getMergedResources(List<DbResource> previous, List<DbResource> current) {
        Collector<DbResource, ?, Map<Pair<String, String>, DbResource>> collector =
                Collectors.toMap(dbr -> Pair.of(dbr.getKind(), dbr.getName()), dbr -> dbr, (first, duplicate) -> first);
        Map<Pair<String, String>, DbResource> previousMap = previous != null ? previous.stream().collect(collector) : Collections.emptyMap();
        Map<Pair<String, String>, DbResource> currentMap = current != null ? current.stream().collect(collector) : Collections.emptyMap();
        return Stream.concat(previousMap.keySet().stream(), currentMap.keySet().stream()).distinct()
                .map(kindAndName -> currentMap.getOrDefault(kindAndName, previousMap.get(kindAndName))).collect(Collectors.toList());
    }

    protected Map<String, Object> getMergedConnectionProperties(Map<String, Object> previous, Map<String, Object> current) {
        Map<String, Object> result = new HashMap<>();
        if (current != null) {
            result.putAll(current);
        }
        if (previous != null) {
            previous.forEach(result::putIfAbsent);
        }
        return result;
    }

    private Map<DbaasAdapter, Boolean> getAdaptersAndUserSupportedMap(List<DatabaseRegistry> databases) {
        return databases
                .stream()
                .map(dbRegistry -> getAdapter(dbRegistry.getDatabase()).orElseThrow(() ->
                        new UnregisteredPhysicalDatabaseException("Adapter identifier: " + dbRegistry.getAdapterId())
                ))
                .distinct()
                .collect(Collectors.toMap(adapter -> adapter, DbaasAdapter::isUsersSupported));
    }

    public DatabaseRegistry saveExternalDatabase(DatabaseRegistry databaseRegistry) {
        if (!databaseRegistry.isExternallyManageable()) {
            throw new DBCreateValidationException(Source.builder().pointer("/externallyManageable").build(),
                    String.format("Unable to save database %s as external manageable, because 'externallyManageable' flag has value %s",
                            databaseRegistry.getName(), databaseRegistry.isExternallyManageable()));
        }
        encryption.encryptPassword(databaseRegistry.getDatabase());
        DatabaseRegistry savedDatabase = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveExternalDatabase(databaseRegistry);
        log.info("External logical database was saved {}.", savedDatabase);
        return savedDatabase;
    }

    @Transactional
    public DatabaseRegistry updateClassifier(SortedMap<String, Object> primaryClassifier, SortedMap<String, Object> targetClassifier,
                                             String type, boolean clone) {
        Optional<DatabaseRegistry> databaseRegistry = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().getDatabaseByClassifierAndType(primaryClassifier, type);
        return updateClassifierDb(primaryClassifier, targetClassifier, clone, databaseRegistry.orElseThrow());
    }

    @Transactional
    public DatabaseRegistry updateFromOldClassifierToClassifier(SortedMap<String, Object> primaryClassifier, SortedMap<String, Object> targetClassifier,
                                                                String type, boolean clone) {
        DatabaseRegistry databaseRegistry = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().getDatabaseByOldClassifierAndType(primaryClassifier, type);
        return updateClassifierDb(primaryClassifier, targetClassifier, clone, databaseRegistry);
    }

    private @NotNull DatabaseRegistry updateClassifierDb(SortedMap<String, Object> primaryClassifier, SortedMap<String, Object> targetClassifier, boolean clone, DatabaseRegistry databaseRegistry) {
        if (databaseRegistry == null) {
            throw new NotFoundException("Database with classifier=" + primaryClassifier + " was not found.");
        }
        backupRecord(databaseRegistry);
        if (clone) {//TODO change it when Classifier will be remove from Database entity
            //TODO Sonya what is done here? Should we return after if?
            boolean externallyManageable = databaseRegistry.isExternallyManageable();
            databaseRegistry.setExternallyManageable(true);
            databaseRegistry = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAnyTypeLogDb(databaseRegistry);

            Database database = new Database(databaseRegistry.getDatabase());
            database.setExternallyManageable(externallyManageable);
            database.setOldClassifier(null);
            DatabaseRegistry finalDatabaseRegistry = databaseRegistry;
            Optional<DatabaseRegistry> optionalDatabaseRegistry = database.getDatabaseRegistry().stream().filter(v -> v.getClassifier().equals(finalDatabaseRegistry.getClassifier())).findFirst();
            databaseRegistry = optionalDatabaseRegistry.orElseThrow();
        }
        databaseRegistry.setClassifier(targetClassifier);
        databaseRegistry.getDatabase().setClassifier(targetClassifier);
        log.debug("database after udpate classifier = {}", databaseRegistry);

        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAnyTypeLogDb(databaseRegistry);
        return databaseRegistry;
    }

    public void updateDatabaseConnectionPropertiesAndResourcesById(UUID id, List<Map<String, Object>> connectionProperties, List<DbResource> resources) {
        Database database = logicalDbDbaasRepository.getDatabaseDbaasRepository().findById(id).orElse(null);
        Preconditions.checkNotNull(database, "Database for updating must present");
        DatabaseRegistry databaseRegistry = database.getDatabaseRegistry().get(0); // todo arvo: id in the parameter list must be id of databaseRegistry. Need to edit role migration procedure
        backupRecordForUpdateConnectionProperties(databaseRegistry); // todo sosh after that need to replace logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository() with plain DatabaseRegistryDbaasRepository
        database.addAllConnectionProperties(connectionProperties);
        database.addAllResources(resources);

        encryption.encryptPassword(database);

        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAnyTypeLogDb(databaseRegistry);
    }

    public List<FailedTransformationDatabaseResponse> findDatabasesWithFailedMigration(String namespace) {
        List<DatabaseRegistry> databases = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findAnyLogDbRegistryTypeByNamespace(namespace);
        return databases.stream()
                .filter(db -> db.getClassifier().get(V3_TRANSFORMATION) != null && db.getClassifier().get(V3_TRANSFORMATION).equals("fail"))
                .map(db -> new FailedTransformationDatabaseResponse(db.getId(), db.getDatabase().getOldClassifier(), db.getType()))
                .collect(Collectors.toList());
    }

    private void backupRecord(DatabaseRegistry databaseRegistry) {
        DatabaseHistory databaseHistory = new DatabaseHistory(databaseRegistry);
        Integer lastVersion = databaseHistoryDbaasRepository.getLastVersionByName(databaseRegistry.getName());
        log.debug("Last version database record with name {} is {}", databaseRegistry.getName(), lastVersion);
        databaseHistory.setVersion(lastVersion == null ? 0 : lastVersion + 1);
        databaseHistory.setChangeAction(DatabaseHistory.ChangeAction.UPDATE_CLASSIFIER);
        databaseHistoryDbaasRepository.save(databaseHistory);
    }

    private void backupRecordForUpdateConnectionProperties(DatabaseRegistry databaseRegistry) {
        DatabaseHistory databaseHistory = new DatabaseHistory(databaseRegistry);
        Integer lastVersion = databaseHistoryDbaasRepository.getLastVersionByName(databaseRegistry.getName());
        log.debug("Last version database record with name {} is {}", databaseRegistry.getName(), lastVersion);
        databaseHistory.setVersion(lastVersion == null ? 0 : lastVersion + 1);
        databaseHistory.setChangeAction(DatabaseHistory.ChangeAction.UPDATE_CONNECTION_PROPERTIES);
        databaseHistoryDbaasRepository.save(databaseHistory);
    }

    public void encryptAndSaveDatabaseEntity(DatabaseRegistry databaseRegistry) { // todo arvo: need to reconsider via deattached
        encryption.encryptPassword(databaseRegistry.getDatabase());
        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveInternalDatabase(databaseRegistry);
    }

    public DatabaseResponse processConnectionProperties(DatabaseRegistry databaseRegistry) {
        if (decryptPassword(databaseRegistry.getDatabase())) {
            return new DatabaseResponse(databaseRegistry);
        } else {
            log.warn(MESSAGE_FAILED_TO_DECRYPT_PASSWORD, databaseRegistry.getClassifier());
        }
        return new DatabaseResponse(databaseRegistry);
    }

    public DatabaseResponseV3ListCP processConnectionPropertiesV3(DatabaseRegistry databaseRegistry) {
        log.info("database for decryption = {}", databaseRegistry);
        connectionPropertiesService.addAdditionalPropToCP(databaseRegistry);
        String physicalDatabaseId = getPhysicalDatabaseId(databaseRegistry.getDatabase());
        if (decryptPassword(databaseRegistry.getDatabase())) {
            return new DatabaseResponseV3ListCP(databaseRegistry, physicalDatabaseId);
        } else {
            log.warn(MESSAGE_FAILED_TO_DECRYPT_PASSWORD, databaseRegistry.getClassifier());
        }
        return new DatabaseResponseV3ListCP(databaseRegistry, physicalDatabaseId);
    }

    @Nullable
    private String getPhysicalDatabaseId(Database database) {
        String physicalDatabaseId = null;
        if (database.getPhysicalDatabaseId() != null && !database.getPhysicalDatabaseId().isEmpty()) {
            physicalDatabaseId = database.getPhysicalDatabaseId();
        } else if (database.getAdapterId() != null) {
            physicalDatabaseId = physicalDatabasesService.getByAdapterId(database.getAdapterId()).getPhysicalDatabaseIdentifier();
        }
        return physicalDatabaseId;
    }

    public DatabaseResponseV3 processConnectionPropertiesV3(DatabaseRegistry databaseRegistry, String role) {
        log.info("database for decryption = {}", databaseRegistry);
        connectionPropertiesService.addAdditionalPropToCP(databaseRegistry);
        String physicalDatabaseId = getPhysicalDatabaseId(databaseRegistry.getDatabase());
        if (decryptPassword(databaseRegistry.getDatabase())) {
            return new DatabaseResponseV3SingleCP(databaseRegistry,
                    physicalDatabaseId,
                    role);
        } else {
            log.warn(MESSAGE_FAILED_TO_DECRYPT_PASSWORD, databaseRegistry.getClassifier());
        }
        return new DatabaseResponseV3SingleCP(databaseRegistry,
                physicalDatabaseId,
                role);
    }

    private void registerDeletionError(Exception ex, DatabaseRegistry databaseRegistry) {
        log.info("Register deletion error: {} of database {} with classifier {}", ex.getMessage(), databaseRegistry.getDatabase().getName(), databaseRegistry.getClassifier());
        int status = 0;
        if (ex instanceof WebApplicationException) {
            status = ((WebApplicationException) ex).getResponse().getStatus();
        }
        if (databaseRegistry.getDatabase().getDatabaseRegistry().size() < 2) {
            databaseRegistry.getDatabase().getDbState().setDatabaseState(DbState.DatabaseStateStatus.DELETING_FAILED);
        }

        logicalDbOperationErrorRepository.persist(new LogicalDbOperationError(UUID.randomUUID(), databaseRegistry.getDatabase(), new Date(), ex.getMessage(), status, LogicalDbOperationError.Operation.DELETE));
    }


    public DatabaseRegistry recreateDatabase(DatabaseRegistry existedDb, String physDbId) {
        log.info("Start recreate logical db with classifier {}, type {}, adapterId {} in physical db with Id {}", existedDb.getClassifier(),
                existedDb.getType(), existedDb.getDatabase().getAdapterId(), physDbId);
        DatabaseCreateRequestV3 databaseCreateRequest = new DatabaseCreateRequestV3();
        databaseCreateRequest.setClassifier(existedDb.getClassifier());
        databaseCreateRequest.setSettings(existedDb.getDatabase().getSettings());
        databaseCreateRequest.setPhysicalDatabaseId(physDbId);
        String microserviceName = (String) existedDb.getClassifier().get(MICROSERVICE_NAME);
        CreatedDatabaseV3 createdDatabase = this.createDatabase(databaseCreateRequest, existedDb.getNamespace(), microserviceName)
                .orElseThrow(() -> new UnregisteredPhysicalDatabaseException("Identifier: " + physDbId));
        log.debug("logical db was recreated. Db resources {}. Try to set previous db as archived", createdDatabase.getResources());

        // database have been created try to set the current db as archived
        SortedMap<String, Object> classifier = existedDb.getClassifier();
        SortedMap<String, Object> archivedClassifier = new TreeMap<>(classifier);
        String archiveDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        archivedClassifier.put("archived", archiveDate);
        existedDb.setClassifier(archivedClassifier);
        existedDb.getDatabase().setClassifier(archivedClassifier);
        SortedMap<String, Object> oldClassifier = null;
        if (existedDb.getDatabase().getOldClassifier() != null) {
            oldClassifier = existedDb.getDatabase().getOldClassifier();
            TreeMap<String, Object> updatedOldClassifier = new TreeMap<>(oldClassifier);
            updatedOldClassifier.put("archived", archiveDate);
            existedDb.getDatabase().setOldClassifier(updatedOldClassifier);
        }
        existedDb.getDatabase().getDbState().setDatabaseState(DbState.DatabaseStateStatus.ARCHIVED);
        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveExternalDatabase(existedDb);
        log.debug("Logical db with classifier {} was saved with a new classifier {}", classifier, archivedClassifier);
        entityManager.detach(existedDb); // we do detach, because otherwise you will not be able to save a copy of the database
        entityManager.detach(existedDb.getDatabase());
        Database newDatabase = new Database();
        try {
            PropertyUtils.copyProperties(newDatabase, existedDb.getDatabase());
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        newDatabase.setId(UUID.randomUUID());
        newDatabase.setClassifier(classifier); // classifier without 'archived' field
        newDatabase.setOldClassifier(oldClassifier);
        newDatabase.setAdapterId(createdDatabase.getAdapterId());
        newDatabase.setConnectionProperties(createdDatabase.getConnectionProperties());
        newDatabase.setResources(createdDatabase.getResources());
        newDatabase.setName(createdDatabase.getName());
        newDatabase.setTimeDbCreation(new Date());
        newDatabase.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));

        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setType(existedDb.getType());
        databaseRegistry.setNamespace(existedDb.getNamespace());
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setTimeDbCreation(newDatabase.getTimeDbCreation());
        databaseRegistry.setDatabase(newDatabase);
        List<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        databaseRegistries.add(databaseRegistry);
        newDatabase.setDatabaseRegistry(databaseRegistries);
        encryption.encryptPassword(newDatabase);

        return logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveExternalDatabase(databaseRegistry);
    }

    public ProcessConnectionPropertiesService getConnectionPropertiesService() {
        return this.connectionPropertiesService;
    }

    public boolean areRulesExistingInNamespace(String namespace) {
        return balancingRulesService.areRulesExistingInNamespace(namespace);
    }

    @SneakyThrows
    public DatabaseRegistry makeCopy(DatabaseRegistry origin) {
        Database originDatabase = origin.getDatabase();
        Database copyDatabase = new Database(originDatabase);
        DatabaseRegistry copyDatabaseRegistry = copyDatabase.getDatabaseRegistry().stream()
                .filter(dbReg -> dbReg.getClassifier().equals(origin.getClassifier())).findFirst().orElseThrow();

        copyDatabase.setId(UUID.randomUUID());
        copyDatabase.getDatabaseRegistry().forEach(dbRegistry -> dbRegistry.setId(UUID.randomUUID()));
        copyDatabaseRegistry.getDbState().setId(UUID.randomUUID());
        copyDatabaseRegistry.getResources().forEach(dbResource -> dbResource.setId(UUID.randomUUID()));
        return copyDatabaseRegistry;
    }

    public DatabaseRegistry shareDbToNamespace(DatabaseRegistry sourceRegistry, String targetNamespace) {
        Database sourceDatabase = sourceRegistry.getDatabase();
        DatabaseRegistry newRegistry = new DatabaseRegistry(sourceRegistry, targetNamespace);
        Optional<DatabaseRegistry> existingRegistry = sourceDatabase.getDatabaseRegistry().stream()
                .filter(dbr -> dbr.getClassifier().equals(newRegistry.getClassifier())
                               && dbr.getType().equals(newRegistry.getType()))
                .findFirst();
        log.debug("Share static database to {} namespace with new classifier {}", targetNamespace, newRegistry.getClassifier());
        if (existingRegistry.isEmpty()) {
            sourceDatabase.getDatabaseRegistry().add(newRegistry);
            logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAnyTypeLogDb(newRegistry);
            return newRegistry;
        } else {
            return existingRegistry.get();
        }
    }
}
