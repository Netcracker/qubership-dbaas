package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.bluegreen.AbstractDatabaseProcessObject;
import org.qubership.cloud.dbaas.dto.declarative.DatabaseDeclaration;
import org.qubership.cloud.dbaas.dto.declarative.DatabaseToDeclarativeCreation;
import org.qubership.cloud.dbaas.entity.pg.BgNamespace;
import org.qubership.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import org.qubership.cloud.dbaas.exceptions.DeclarativeConfigurationValidationException;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BgNamespaceRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseDeclarativeConfigRepository;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.*;
import static org.qubership.cloud.dbaas.service.DatabaseConfigurationCreationService.*;

@ApplicationScoped
@Slf4j
public class DeclarativeDbaasCreationService {


    private final BgNamespaceRepository bgNamespaceRepository;

    private final DatabaseDeclarativeConfigRepository declarativeConfigRepository;

    private final ProcessService processService;

    final DatabaseConfigurationCreationService databaseConfigurationCreationService;

    public DeclarativeDbaasCreationService(
            BgNamespaceRepository bgNamespaceRepository, DatabaseDeclarativeConfigRepository declarativeConfigRepository,
            ProcessService processService, DatabaseConfigurationCreationService databaseConfigurationCreationService) {
        this.bgNamespaceRepository = bgNamespaceRepository;
        this.declarativeConfigRepository = declarativeConfigRepository;
        this.processService = processService;
        this.databaseConfigurationCreationService = databaseConfigurationCreationService;
    }


    @Transactional
    public void deleteDeclarativeConfigurationByNamespace(String namespace) {
        declarativeConfigRepository.deleteByNamespace(namespace);
    }

    @Transactional
    public ArrayList<AbstractDatabaseProcessObject> saveDeclarativeDatabase(String namespace, String serviceName,
                                                                            List<DatabaseDeclaration> declarations) {

        Optional<BgNamespace> bgNamespace = bgNamespaceRepository.findBgNamespaceByNamespace(namespace);

        ArrayList<DatabaseDeclaration> cloneToNewDatabases = new ArrayList<>();

        List<DatabaseToDeclarativeCreation> configsToCreateDatabase = new ArrayList<>();
        for (DatabaseDeclaration databaseDeclaration : declarations) {
            DatabaseDeclarativeConfig databaseConfig = saveNewDatabaseConfig(namespace,
                    serviceName, databaseDeclaration);
            if (Boolean.TRUE.equals(databaseConfig.getLazy())) {
                continue;
            }
            DatabaseExistence allDatabaseExists = databaseConfigurationCreationService.isAllDatabaseExists(databaseConfig, namespace);
            if (allDatabaseExists.isExist() && allDatabaseExists.isActual()) {
                continue;
            }

            if (!allDatabaseExists.isExist()) {
                List<DatabaseDeclaration> list =
                        declarations.stream().filter(c -> c.getInitialInstantiation() != null
                                && c.getInitialInstantiation().getSourceClassifier() != null
                                && c.getInitialInstantiation().getSourceClassifier()
                                .equals(databaseDeclaration.getClassifierConfig().getClassifier())).toList();
                cloneToNewDatabases.addAll(list);
            }

            SortedMap<String, Object> rawSourceClassifier = databaseDeclaration.getInitialInstantiation() != null ?
                    databaseDeclaration.getInitialInstantiation().getSourceClassifier() :
                    null;

            configsToCreateDatabase.add(new DatabaseToDeclarativeCreation(databaseConfig,
                    cloneToNewDatabases.contains(databaseDeclaration), rawSourceClassifier));
        }

        ArrayList<AbstractDatabaseProcessObject> processObjects = new ArrayList<>();
        configsToCreateDatabase.forEach(config -> processObjects.addAll(
                databaseConfigurationCreationService.createDatabaseProcessObject(config.getDatabaseDeclarativeConfig(),
                        config.getCloneToNew(),
                        config.getSourceClassifier(), bgNamespace, APPLY_CONFIG_OPERATION)
        ));
        return processObjects;
    }

    public ProcessInstanceImpl startProcessInstance(String namespace, ArrayList<AbstractDatabaseProcessObject> processObjects) {
        ProcessInstanceImpl process = databaseConfigurationCreationService.createProcessInstance(processObjects,
                APPLY_CONFIG_OPERATION, namespace);

        processService.startProcess(process);
        return process;
    }

    List<DatabaseDeclarativeConfig> findAllByNamespace(String namespace) {
        return declarativeConfigRepository.findAllByNamespace(namespace);
    }

    @Transactional
    public DatabaseDeclarativeConfig saveConfigurationWithNewNamespace(DatabaseDeclarativeConfig
                                                                               declarativeConfig, String namespace) {
        TreeMap<String, Object> sourceClassifier = new TreeMap<>(declarativeConfig.getClassifier());
        sourceClassifier.put(NAMESPACE, namespace);
        Optional<DatabaseDeclarativeConfig> databaseConfigFromDbOpt =
                declarativeConfigRepository.findFirstByClassifierAndType(sourceClassifier, declarativeConfig.getType());
        if (databaseConfigFromDbOpt.isEmpty()) {
            DatabaseDeclarativeConfig newConfig = new DatabaseDeclarativeConfig(declarativeConfig);
            newConfig.getClassifier().put(NAMESPACE, namespace);
            newConfig.setNamespace(namespace);
            declarativeConfigRepository.persist(newConfig);
            return newConfig;
        } else {
            DatabaseDeclarativeConfig databaseConfigFromDb = databaseConfigFromDbOpt.get();
            return writeChanges(declarativeConfig, databaseConfigFromDb);
        }
    }


    @NotNull
    @Transactional
    public DatabaseDeclarativeConfig saveNewDatabaseConfig(String namespace, String serviceName,
                                                           DatabaseDeclaration databaseDeclaration) {
        validateClassifier(serviceName, databaseDeclaration);
        SortedMap<String, Object> targetClassifier =
                convertClassifierConfigToClassifier(databaseDeclaration.getClassifierConfig().getClassifier(), namespace);
        Optional<DatabaseDeclarativeConfig> databaseConfigFromDb =
                declarativeConfigRepository.findFirstByClassifierAndType(targetClassifier, databaseDeclaration.getType());
        DatabaseDeclarativeConfig databaseConfig = new DatabaseDeclarativeConfig(databaseDeclaration, targetClassifier, namespace);
        if (Boolean.TRUE.equals(databaseConfig.getLazy()) && databaseConfig.getInstantiationApproach().equals(CLONE_MODE)) {
            throw new UnsupportedOperationException("lazy creation is prohibited in blue-green mode");
        }
        if (databaseConfigFromDb.isPresent()) {
            writeChanges(databaseConfig, databaseConfigFromDb.get());
        } else {
            saveDeclarativeConfiguration(databaseConfig);
        }
        return databaseConfig;
    }

    private void validateClassifier(String serviceName, DatabaseDeclaration databaseDeclaration) {
        Map<String, Object> targetClassifier = databaseDeclaration.getClassifierConfig().getClassifier();
        Map<String, Object> sourceClassifier = null;
        if (databaseDeclaration.getInitialInstantiation() != null) {
            sourceClassifier = databaseDeclaration.getInitialInstantiation().getSourceClassifier();
        }
        if (!targetClassifier.containsKey(MICROSERVICE_NAME) || !targetClassifier.containsKey(SCOPE)) {
            log.error("Target classifier={} doesn't contain mandatory fields as 'microserviceName' and 'scope'", targetClassifier);
            throw new DeclarativeConfigurationValidationException("Target classifier doesn't contain mandatory fields as 'microserviceName' and 'scope'");
        }
        if (sourceClassifier != null && (!sourceClassifier.containsKey(MICROSERVICE_NAME) || !sourceClassifier.containsKey(SCOPE))) {
            log.error("Source classifier={} doesn't contain mandatory fields as 'microserviceName' and 'scope'", targetClassifier);
            throw new DeclarativeConfigurationValidationException("Source classifier doesn't contain mandatory fields as 'microserviceName' and 'scope'");
        }
        if (!targetClassifier.get(MICROSERVICE_NAME).equals(serviceName) || (sourceClassifier != null && !sourceClassifier.get(MICROSERVICE_NAME).equals(serviceName))) {
            log.error("Target classifier={} or source classifier={} contains microserviceName which is different from service name in path = {}",
                    targetClassifier, sourceClassifier, serviceName);
            throw new DeclarativeConfigurationValidationException("Target classifier or source classifier contains service name which is different from serviceName in request");
        }
    }

    private DatabaseDeclarativeConfig writeChanges(DatabaseDeclarativeConfig newDatabaseConfig, DatabaseDeclarativeConfig databaseConfigFromDb) {
        databaseConfigFromDb.setLazy(newDatabaseConfig.getLazy());
        databaseConfigFromDb.setSettings(newDatabaseConfig.getSettings());
        databaseConfigFromDb.setNamePrefix(newDatabaseConfig.getNamePrefix());
        databaseConfigFromDb.setVersioningType(newDatabaseConfig.getVersioningType());
        databaseConfigFromDb.setVersioningApproach(newDatabaseConfig.getVersioningApproach());
        databaseConfigFromDb.setInstantiationApproach(newDatabaseConfig.getInstantiationApproach());
        return saveDeclarativeConfiguration(databaseConfigFromDb);
    }


    private DatabaseDeclarativeConfig saveDeclarativeConfiguration(DatabaseDeclarativeConfig databaseConfig) {
        log.info("database config = {}", databaseConfig);
        declarativeConfigRepository.persist(databaseConfig);
        return databaseConfig;
    }

    public SortedMap<String, Object> convertClassifierConfigToClassifier
            (Map<String, Object> rawClassifier, String namespace) {
        if (rawClassifier == null || rawClassifier.isEmpty()) {
            return null;
        }
        SortedMap<String, Object> classifier = new TreeMap<>(rawClassifier);
        classifier.put(NAMESPACE, namespace);
        return classifier;
    }

}
