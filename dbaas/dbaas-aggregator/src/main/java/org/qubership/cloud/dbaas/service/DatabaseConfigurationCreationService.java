package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.context.propagation.core.ContextManager;
import org.qubership.cloud.dbaas.dto.bluegreen.AbstractDatabaseProcessObject;
import org.qubership.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import org.qubership.cloud.dbaas.dto.bluegreen.NewDatabaseProcessObject;
import org.qubership.cloud.dbaas.entity.pg.BgNamespace;
import org.qubership.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.exceptions.DeclarativeConfigurationValidationException;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseDeclarativeConfigRepository;
import org.qubership.cloud.dbaas.service.processengine.processes.AllDatabasesCreationProcess;
import org.qubership.cloud.framework.contexts.xrequestid.XRequestIdContextObject;
import org.qubership.core.scheduler.po.DataContext;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.qubership.core.scheduler.po.model.pojo.TaskInstanceImpl;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.*;
import static org.qubership.cloud.dbaas.service.processengine.Const.UPDATE_BG_STATE_TASK;
import static org.qubership.cloud.framework.contexts.xrequestid.XRequestIdContextObject.X_REQUEST_ID;

@ApplicationScoped
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseConfigurationCreationService {

    @Inject
    DBaaService dBaaService;
    @Inject
    LogicalDbDbaasRepository logicalDbDbaasRepository;
    @Inject
    ProcessService processService;
    @Inject
    DatabaseDeclarativeConfigRepository declarativeConfigRepository;


    public ProcessInstanceImpl createProcessInstance(List<AbstractDatabaseProcessObject> processObjects, String operation,
                                                     String namespace) {
        return createProcessInstance(processObjects, operation, namespace, null);
    }

    public ProcessInstanceImpl createProcessInstance(List<AbstractDatabaseProcessObject> processObjects, String operation,
                                                     String namespace, @Nullable String version) {
        ProcessInstanceImpl process = processService.createProcess(new AllDatabasesCreationProcess(processObjects), namespace, operation);
        for (TaskInstanceImpl task : process.getTasks()) {
            task.getContext().apply((DataContext c) -> c.put(X_REQUEST_ID, ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId()));
            if (UPDATE_BG_STATE_TASK.equals(task.getName())) {
                task.getContext().apply((DataContext c) -> {
                    c.put("operation", operation);
                    c.put("namespace", namespace);
                    c.put("version", version);
                });
                continue;
            }
            task.getContext().apply((DataContext c) ->
                    c.put("processObject",
                            processObjects.stream()
                                    .filter(p -> p.getId().toString().equals(task.getName().split(":")[1]))
                                    .findFirst().get()));
        }
        return process;
    }

    public List<AbstractDatabaseProcessObject> createDatabaseProcessObject(DatabaseDeclarativeConfig databaseConfig,
                                                                           Boolean cloneToNewDatabases,
                                                                           @Nullable SortedMap<String, Object> rawSourceClassifier,
                                                                           Optional<BgNamespace> bgNamespace,
                                                                           String operation) {

        List<AbstractDatabaseProcessObject> subProcesses;
        if (WARMUP_OPERATION.equals(operation)) {
            subProcesses = createProcessBasedOnConfiguration(bgNamespace.orElseThrow().getVersion(),
                    rawSourceClassifier, databaseConfig, databaseConfig.getVersioningApproach());
        } else if (APPLY_CONFIG_OPERATION.equals(operation)) {
                subProcesses = createProcessBasedOnConfiguration(bgNamespace.isPresent() ? bgNamespace.get().getVersion() : null,
                        convertClassifierConfigToClassifier(rawSourceClassifier, databaseConfig.getNamespace()), databaseConfig,
                        databaseConfig.getInstantiationApproach(),
                        cloneToNewDatabases);
        } else {
            throw new RuntimeException("not supported type");
        }

        return subProcesses;
    }

    public void commitDatabases(SortedMap<String, Object> classifier, String type, String namespace) {
        classifier.put(NAMESPACE, namespace);
        List<DatabaseRegistry> databaseRegistries;
        if (SCOPE_VALUE_TENANT.equals(classifier.get(SCOPE))) {
            databaseRegistries = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findAllTenantDatabasesInNamespace(namespace)
                    .stream().filter(dbr -> {
                                classifier.put(TENANT_ID, dbr.getClassifier().get(TENANT_ID));
                                return dbr.getBgVersion() != null && dbr.getClassifier().equals(classifier) && dbr.getType().equals(type);
                            }
                    ).toList();
        } else {
            Optional<DatabaseRegistry> databaseRegistry = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()
                    .getDatabaseByClassifierAndType(classifier, type);
            databaseRegistries = databaseRegistry.stream().toList();
        }
        log.debug("Mark versioned databases for drop in namespace = {}", databaseRegistries);
        dBaaService.markVersionedDatabasesAsOrphan(databaseRegistries);
        dBaaService.dropDatabasesAsync(namespace, databaseRegistries);
    }

    private SortedMap<String, Object> convertClassifierConfigToClassifier
            (Map<String, Object> rawClassifier, String namespace) {
        if (rawClassifier == null || rawClassifier.isEmpty()) {
            return null;
        }
        SortedMap<String, Object> classifier = new TreeMap<>(rawClassifier);
        classifier.put(NAMESPACE, namespace);
        return classifier;
    }


    public DatabaseExistence isAllDatabaseExists(DatabaseDeclarativeConfig databaseConfig, String sourceNamespace) {
        List<Map<String, Object>> classifiers = new ArrayList<>();
        if (SCOPE_VALUE_TENANT.equals(databaseConfig.getClassifier().get(SCOPE))) {
            List<Object> allUniqTenants = getAllUniqTenants(sourceNamespace);
            allUniqTenants.forEach(tenantId -> {
                SortedMap<String, Object> classifier = new TreeMap<>(databaseConfig.getClassifier());
                classifier.put(TENANT_ID, tenantId);
                classifiers.add(classifier);
            });
        } else {
            classifiers.add(databaseConfig.getClassifier());
        }
        List<DatabaseExistence> registries = new ArrayList<>();
        classifiers.forEach(classifier -> logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()
                .getDatabaseByClassifierAndType(classifier, databaseConfig.getType())
                .ifPresent(dbr -> registries.add(new DatabaseExistence(true,
                        dbr.getBgVersion() != null && VERSION_STATE.equals(databaseConfig.getVersioningType())
                                || dbr.getBgVersion() == null && STATIC_STATE.equals(databaseConfig.getVersioningType())))
                ));

        return new DatabaseExistence(classifiers.size() == registries.size(),
                registries.stream().map(DatabaseExistence::isActual).reduce(Boolean::logicalOr).orElse(false));
    }

    @NotNull
    private List<Object> getAllUniqTenants(String namespace) {
        return logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()
                .findAllTenantDatabasesInNamespace(namespace)
                .stream().map(dbr -> dbr.getClassifier().get(TENANT_ID)).distinct().toList();
    }


    private List<AbstractDatabaseProcessObject> createProcessBasedOnConfiguration(@Nullable String version,
                                                                                  @Nullable SortedMap<String, Object> sourceClassifier,
                                                                                  DatabaseDeclarativeConfig newConfiguration,
                                                                                  String creationApproach) {

        return createProcessBasedOnConfiguration(version, sourceClassifier, newConfiguration, creationApproach, false);
    }


    private List<AbstractDatabaseProcessObject> createProcessBasedOnConfiguration(@Nullable String version,
                                                                                  @Nullable SortedMap<String, Object> sourceClassifier,
                                                                                  DatabaseDeclarativeConfig newConfiguration,
                                                                                  String creationApproach,
                                                                                  boolean isNewCreation) {
        List<AbstractDatabaseProcessObject> processObjects;
        if (CLONE_MODE.equals(creationApproach) && !isNewCreation) {
            processObjects = createCloneDatabaseProcess(version, sourceClassifier, newConfiguration);
        } else if (isNewCreation || NEW_MODE.equals(creationApproach)) {
            processObjects = createNewDatabaseProcess(newConfiguration, version,
                    sourceClassifier != null ? (String) sourceClassifier.get(NAMESPACE) : newConfiguration.getNamespace());
        } else {
            String errorMsg = String.format("Unknown instantiation approach=%s for database with classifier=%s",
                    creationApproach, newConfiguration.getClassifier());
            throw new DeclarativeConfigurationValidationException(errorMsg);
        }
        return processObjects;
    }

    private List<AbstractDatabaseProcessObject> createNewDatabaseProcess(DatabaseDeclarativeConfig databaseConfig, String version, String namespace) {

        List<DatabaseDeclarativeConfig> databaseConfigs = new ArrayList<>();
        if (SCOPE_VALUE_SERVICE.equals(databaseConfig.getClassifier().get(SCOPE))) {
            databaseConfigs.add(databaseConfig);
        } else {
            databaseConfigs = getTenantDatabasesByConfig(databaseConfig, namespace);
        }
        List<AbstractDatabaseProcessObject> processObjects = new ArrayList<>();
        for (DatabaseDeclarativeConfig config : databaseConfigs) {
            processObjects.add(new NewDatabaseProcessObject(config, version));
        }

        return processObjects;
    }

    public List<DatabaseDeclarativeConfig> getTenantDatabasesByConfig(DatabaseDeclarativeConfig databaseConfig, String namespace) {
        List<DatabaseDeclarativeConfig> databaseConfigs = new ArrayList<>();
        List<Object> allUniqTenants = getAllUniqTenants(namespace);
        allUniqTenants.forEach(tenant -> {
                    DatabaseDeclarativeConfig databaseDeclarativeConfig = new DatabaseDeclarativeConfig(databaseConfig);
                    databaseDeclarativeConfig.getClassifier().put(TENANT_ID, tenant);
                    databaseConfigs.add(databaseDeclarativeConfig);
                }
        );
        return databaseConfigs;
    }

    @NotNull
    private List<AbstractDatabaseProcessObject> createCloneDatabaseProcess(String version, SortedMap<String, Object> sourceClassifier, DatabaseDeclarativeConfig newConfiguration) {
        if (Boolean.TRUE.equals(newConfiguration.getLazy())) {
            throw new RuntimeException("versioned database not supported in lazy mode");
        }
        log.info("source classifier to clone= {}", sourceClassifier);
        List<SortedMap<String, Object>> classifiersToClone;
        String namespace = (String) sourceClassifier.get(NAMESPACE);
        if (SCOPE_VALUE_SERVICE.equals(sourceClassifier.get(SCOPE))) {
            classifiersToClone = List.of(sourceClassifier);
        } else {
            List<Object> allUniqTenants = getAllUniqTenants(namespace);
            SortedMap<String, Object> tenantClassifier = new TreeMap<>(newConfiguration.getClassifier());
            SortedMap<String, Object> sourceTenantClassifier = new TreeMap<>(sourceClassifier);
            List<Object> tenantsToClone = allUniqTenants.stream().filter(tenant -> {
                tenantClassifier.put(TENANT_ID, tenant);
                return logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().
                        getDatabaseByClassifierAndType(tenantClassifier, newConfiguration.getType()).isEmpty();
            }).filter(tenant -> {
                sourceTenantClassifier.put(TENANT_ID, tenant);
                return logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().
                        getDatabaseByClassifierAndType(sourceTenantClassifier, newConfiguration.getType()).isPresent();
            }).toList();
            classifiersToClone = tenantsToClone.stream().map(tenant -> {
                SortedMap<String, Object> classifierToClone = new TreeMap<>(sourceClassifier);
                classifierToClone.put(TENANT_ID, tenant);
                return classifierToClone;
            }).toList();
        }

        List<AbstractDatabaseProcessObject> processObjects = new ArrayList<>();
        for (SortedMap<String, Object> classifierToClone : classifiersToClone) {
            processObjects.add(new CloneDatabaseProcessObject(newConfiguration, version, classifierToClone, namespace));
        }
        return processObjects;
    }

    @Data
    @AllArgsConstructor
    public static class DatabaseExistence {
        private final boolean exist;
        private final boolean actual;
    }
}
