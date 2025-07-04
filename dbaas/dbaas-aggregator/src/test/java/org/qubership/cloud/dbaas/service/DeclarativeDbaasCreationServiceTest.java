package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.declarative.DatabaseDeclaration;
import org.qubership.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import org.qubership.cloud.dbaas.exceptions.DeclarativeConfigurationValidationException;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BgNamespaceRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseDeclarativeConfigRepository;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.APPLY_CONFIG_OPERATION;
import static org.qubership.cloud.dbaas.service.DatabaseConfigurationCreationService.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeclarativeDbaasCreationServiceTest {

    public static final String ORIGIN_NAMESPACE = "origin-namespace";
    public static final String PEER_NAMESPACE = "peer-namespace";
    public static final String TEST_MICROSERVICE_NAME = "test-service";

    @Mock
    BgNamespaceRepository bgNamespaceRepository;
    @Mock
    DatabaseDeclarativeConfigRepository declarativeConfigRepository;
    @Mock
    DatabaseConfigurationCreationService databaseConfigurationCreationService;

    @InjectMocks
    DeclarativeDbaasCreationService declarativeDbaasCreationService;


    @Test
    void saveDeclarativeDatabaseAlreadyExist() {
        DatabaseDeclaration databaseDeclaration = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        ArrayList<DatabaseDeclaration> declarations = new ArrayList<>();
        declarations.add(databaseDeclaration);
        when(databaseConfigurationCreationService.isAllDatabaseExists(any(), eq(ORIGIN_NAMESPACE))).thenReturn(new DatabaseExistence(true, true));
        declarativeDbaasCreationService.saveDeclarativeDatabase(ORIGIN_NAMESPACE, TEST_MICROSERVICE_NAME, declarations);
        Mockito.verify(databaseConfigurationCreationService, Mockito.times(0))
                .createDatabaseProcessObject(any(), any(), any(), any(), any());
    }

    @Test
    void saveDeclarativeDatabaseLazy() {
        DatabaseDeclaration databaseDeclaration = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        databaseDeclaration.setLazy(Boolean.TRUE);
        ArrayList<DatabaseDeclaration> declarations = new ArrayList<>();
        declarations.add(databaseDeclaration);
        declarativeDbaasCreationService.saveDeclarativeDatabase(ORIGIN_NAMESPACE, TEST_MICROSERVICE_NAME, declarations);
        Mockito.verify(databaseConfigurationCreationService, Mockito.times(0))
                .createDatabaseProcessObject(any(), any(), any(), any(), any());
        verify(databaseConfigurationCreationService, times(0)).isAllDatabaseExists(any(), eq(ORIGIN_NAMESPACE));
    }

    @Test
    void saveDeclarativeDatabase() {
        DatabaseDeclaration databaseDeclaration = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        ArrayList<DatabaseDeclaration> declarations = new ArrayList<>();
        declarations.add(databaseDeclaration);
        when(databaseConfigurationCreationService.isAllDatabaseExists(any(), any())).thenReturn(new DatabaseExistence(false, false));
        declarativeDbaasCreationService.saveDeclarativeDatabase(ORIGIN_NAMESPACE, TEST_MICROSERVICE_NAME, declarations);
        verify(databaseConfigurationCreationService, times(1))
                .createDatabaseProcessObject(
                        argThat(m -> m.getClassifier().get("microserviceName").equals(TEST_MICROSERVICE_NAME)),
                        eq(false),
                        any(),
                        any(),
                        eq(APPLY_CONFIG_OPERATION)
                );
    }

    @Test
    void saveDeclarativeDatabaseCloneToNew() {
        DatabaseDeclaration databaseDeclaration = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        DatabaseDeclaration databaseDeclaration2 = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        databaseDeclaration2.getClassifierConfig().getClassifier().put("logicalDb", "config");
        DatabaseDeclaration.InitialInstantiation initialInstantiation = new DatabaseDeclaration.InitialInstantiation();
        initialInstantiation.setSourceClassifier(databaseDeclaration.getClassifierConfig().getClassifier());
        databaseDeclaration2.setInitialInstantiation(initialInstantiation);
        ArrayList<DatabaseDeclaration> declarations = new ArrayList<>();
        declarations.add(databaseDeclaration);
        declarations.add(databaseDeclaration2);
        when(databaseConfigurationCreationService.isAllDatabaseExists(any(), any())).thenReturn(new DatabaseExistence(false, false));
        declarativeDbaasCreationService.saveDeclarativeDatabase(ORIGIN_NAMESPACE, TEST_MICROSERVICE_NAME, declarations);
        verify(databaseConfigurationCreationService, times(1))
                .createDatabaseProcessObject(
                        argThat(m -> m.getClassifier().get("microserviceName").equals(TEST_MICROSERVICE_NAME)),
                        eq(false),
                        any(),
                        any(),
                        eq(APPLY_CONFIG_OPERATION)
                );
        verify(databaseConfigurationCreationService, times(1))
                .createDatabaseProcessObject(
                        argThat(m -> m.getClassifier().get("microserviceName").equals(TEST_MICROSERVICE_NAME)),
                        eq(true),
                        any(),
                        any(),
                        eq(APPLY_CONFIG_OPERATION)
                );
    }

    @Test
    void finalAllByNamespace() {
        declarativeDbaasCreationService.findAllByNamespace(ORIGIN_NAMESPACE);
        verify(declarativeConfigRepository, times(1)).findAllByNamespace(ORIGIN_NAMESPACE);
    }


    @Test
    void saveConfigurationWithNewNamespaceDeclarativeIsEmpty() {
        DatabaseDeclarativeConfig databaseDeclarativeConfig = createDatabaseDeclarativeConfig(TEST_MICROSERVICE_NAME, ORIGIN_NAMESPACE);
        declarativeDbaasCreationService.saveConfigurationWithNewNamespace(databaseDeclarativeConfig, PEER_NAMESPACE);
        verify(declarativeConfigRepository, times(1))
                .persist(Mockito.<DatabaseDeclarativeConfig>argThat(o -> o.getNamespace().equals(PEER_NAMESPACE)
                        && o.getClassifier().get("namespace").equals(PEER_NAMESPACE)
                        && o.getId() == null
                ));

    }

    @Test
    void saveConfigurationWithNewNamespaceDeclarativeIsPresent() {
        DatabaseDeclarativeConfig existedDatabaseDeclarativeConfig = createDatabaseDeclarativeConfig(TEST_MICROSERVICE_NAME, PEER_NAMESPACE);
        UUID uuid = UUID.randomUUID();
        existedDatabaseDeclarativeConfig.setId(uuid);
        SortedMap<String, Object> sourceClassifier = existedDatabaseDeclarativeConfig.getClassifier();
        when(declarativeConfigRepository.findFirstByClassifierAndType(sourceClassifier, existedDatabaseDeclarativeConfig.getType()))
                .thenReturn(Optional.of(existedDatabaseDeclarativeConfig));
        DatabaseDeclarativeConfig databaseDeclarativeConfig = createDatabaseDeclarativeConfig(TEST_MICROSERVICE_NAME, ORIGIN_NAMESPACE);
        declarativeDbaasCreationService.saveConfigurationWithNewNamespace(databaseDeclarativeConfig, PEER_NAMESPACE);
        verify(declarativeConfigRepository, times(1))
                .persist(Mockito.<DatabaseDeclarativeConfig>argThat(o -> o.getNamespace().equals(PEER_NAMESPACE)
                        && o.getClassifier().get("namespace").equals(PEER_NAMESPACE)
                        && o.getId().equals(uuid)
                ));
    }

    @Test
    void saveNewDatabaseConfigUnsupportedOperation() {
        DatabaseDeclaration databaseDeclaration = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        DatabaseDeclaration.InitialInstantiation initialInstantiation = new DatabaseDeclaration.InitialInstantiation();
        initialInstantiation.setApproach("clone");
        databaseDeclaration.setInitialInstantiation(initialInstantiation);
        databaseDeclaration.setLazy(Boolean.TRUE);
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> declarativeDbaasCreationService.saveNewDatabaseConfig(ORIGIN_NAMESPACE, TEST_MICROSERVICE_NAME, databaseDeclaration));
    }

    @Test
    void saveNewDatabaseConfig() {
        DatabaseDeclaration databaseDeclaration = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        declarativeDbaasCreationService.saveNewDatabaseConfig(ORIGIN_NAMESPACE, TEST_MICROSERVICE_NAME, databaseDeclaration);
        verify(declarativeConfigRepository, times(1)).persist(Mockito.<DatabaseDeclarativeConfig>argThat(
                declarative ->
                        declarative.getNamespace().equals(ORIGIN_NAMESPACE)
                                && declarative.getClassifier().get("namespace").equals(ORIGIN_NAMESPACE)
                                && declarative.getId() == null

                )
        );
    }

    @Test
    void saveNewDatabaseConfigAlreadyExist() {
        DatabaseDeclaration databaseDeclaration = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        UUID uuid = UUID.randomUUID();
        DatabaseDeclarativeConfig databaseDeclarativeConfig = createDatabaseDeclarativeConfig(TEST_MICROSERVICE_NAME, ORIGIN_NAMESPACE);
        databaseDeclarativeConfig.setId(uuid);
        databaseDeclaration.getClassifierConfig().getClassifier().put("namespace", ORIGIN_NAMESPACE);
        when(declarativeConfigRepository.findFirstByClassifierAndType(databaseDeclaration.getClassifierConfig().getClassifier(), databaseDeclaration.getType()))
                .thenReturn(Optional.of(databaseDeclarativeConfig));
        declarativeDbaasCreationService.saveNewDatabaseConfig(ORIGIN_NAMESPACE, TEST_MICROSERVICE_NAME, databaseDeclaration);
        verify(declarativeConfigRepository, times(1)).persist(Mockito.<DatabaseDeclarativeConfig>argThat(
                declarative ->
                        declarative.getNamespace().equals(ORIGIN_NAMESPACE)
                                && declarative.getClassifier().get("namespace").equals(ORIGIN_NAMESPACE)
                                && declarative.getId() == uuid

                )
        );
    }


    @Test
    void saveNewDatabaseConfigValidateClassifier() {
        DatabaseDeclaration databaseDeclaration = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        declarativeDbaasCreationService.saveNewDatabaseConfig(ORIGIN_NAMESPACE, TEST_MICROSERVICE_NAME, databaseDeclaration);
        verify(declarativeConfigRepository, times(1)).persist(any(DatabaseDeclarativeConfig.class));
    }

    @Test
    void saveNewDatabaseConfigValidateClassifierIncorrectTargetClassifierScope() {
        DatabaseDeclaration databaseDeclaration = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        databaseDeclaration.getClassifierConfig().getClassifier().remove("scope");
        Assertions.assertThrows(DeclarativeConfigurationValidationException.class,
                () -> declarativeDbaasCreationService.saveNewDatabaseConfig(ORIGIN_NAMESPACE, TEST_MICROSERVICE_NAME, databaseDeclaration));
    }

    @Test
    void saveNewDatabaseConfigValidateClassifierIncorrectTargetClassifierMicroservice() {
        DatabaseDeclaration databaseDeclaration = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        databaseDeclaration.getClassifierConfig().getClassifier().remove("microserviceName");
        Assertions.assertThrows(DeclarativeConfigurationValidationException.class,
                () -> declarativeDbaasCreationService.saveNewDatabaseConfig(ORIGIN_NAMESPACE, TEST_MICROSERVICE_NAME, databaseDeclaration));
    }

    @Test
    void saveNewDatabaseConfigValidateClassifierIncorrectSourceScope() {
        DatabaseDeclaration databaseDeclaration = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        DatabaseDeclaration.InitialInstantiation initialInstantiation = new DatabaseDeclaration.InitialInstantiation();
        initialInstantiation.setApproach("clone");
        SortedMap<String, Object> sourceClassifier = new TreeMap<>();
        sourceClassifier.put("microserviceName", TEST_MICROSERVICE_NAME);
        initialInstantiation.setSourceClassifier(sourceClassifier);
        databaseDeclaration.setInitialInstantiation(initialInstantiation);
        Assertions.assertThrows(DeclarativeConfigurationValidationException.class,
                () -> declarativeDbaasCreationService.saveNewDatabaseConfig(ORIGIN_NAMESPACE, TEST_MICROSERVICE_NAME, databaseDeclaration));
    }

    @Test
    void saveNewDatabaseConfigValidateClassifierIncorrectSourceClassifierMicroservice() {
        DatabaseDeclaration databaseDeclaration = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        DatabaseDeclaration.InitialInstantiation initialInstantiation = new DatabaseDeclaration.InitialInstantiation();
        initialInstantiation.setApproach("clone");
        SortedMap<String, Object> sourceClassifier = new TreeMap<>();
        sourceClassifier.put("scope", "service");
        initialInstantiation.setSourceClassifier(sourceClassifier);
        databaseDeclaration.setInitialInstantiation(initialInstantiation);
        Assertions.assertThrows(DeclarativeConfigurationValidationException.class,
                () -> declarativeDbaasCreationService.saveNewDatabaseConfig(ORIGIN_NAMESPACE, TEST_MICROSERVICE_NAME, databaseDeclaration));
    }

    @Test
    void saveNewDatabaseConfigValidateClassifierDifferentServiceName() {
        DatabaseDeclaration databaseDeclaration = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        Assertions.assertThrows(DeclarativeConfigurationValidationException.class,
                () -> declarativeDbaasCreationService.saveNewDatabaseConfig(ORIGIN_NAMESPACE, "differentServiceName", databaseDeclaration));
    }

    @Test
    void saveNewDatabaseConfigValidateClassifierSourceDifferentServiceName() {
        DatabaseDeclaration databaseDeclaration = createDeclarativeDatabaseCreationConfiguration(TEST_MICROSERVICE_NAME);
        DatabaseDeclaration.InitialInstantiation initialInstantiation = new DatabaseDeclaration.InitialInstantiation();
        initialInstantiation.setApproach("clone");
        SortedMap<String, Object> sourceClassifier = new TreeMap<>();
        sourceClassifier.put("scope", "service");
        sourceClassifier.put("microserviceName", TEST_MICROSERVICE_NAME);
        initialInstantiation.setSourceClassifier(sourceClassifier);
        databaseDeclaration.setInitialInstantiation(initialInstantiation);
        Assertions.assertThrows(DeclarativeConfigurationValidationException.class,
                () -> declarativeDbaasCreationService.saveNewDatabaseConfig(ORIGIN_NAMESPACE, "differentServiceName", databaseDeclaration));
    }


    @Test
    void convertClassifierConfigToClassifier() {
        Map<String, Object> rawClassifier = new HashMap<>();
        rawClassifier.put("microserviceName", TEST_MICROSERVICE_NAME);
        rawClassifier.put("scope", "service");
        SortedMap<String, Object> resultClassifier = declarativeDbaasCreationService.convertClassifierConfigToClassifier(rawClassifier, ORIGIN_NAMESPACE);
        rawClassifier.put("namespace", ORIGIN_NAMESPACE);
        assertEquals(rawClassifier, resultClassifier);

    }

    @Test
    void convertClassifierConfigToClassifierIsEmpty() {
        Map<String, Object> rawClassifier = new HashMap<>();
        Assertions.assertNull(declarativeDbaasCreationService.convertClassifierConfigToClassifier(rawClassifier, ORIGIN_NAMESPACE));
    }

    @Test
    void convertClassifierConfigToClassifierIsNull() {
        Assertions.assertNull(declarativeDbaasCreationService.convertClassifierConfigToClassifier(null, ORIGIN_NAMESPACE));
    }

    private DatabaseDeclaration createDeclarativeDatabaseCreationConfiguration(String microserviceName) {
        DatabaseDeclaration result = new DatabaseDeclaration();
        result.setType("postgresql");

        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("scope", "service");
        classifier.put("microserviceName", microserviceName);

        result.setClassifierConfig(new DatabaseDeclaration.ClassifierConfig(classifier));
        return result;
    }

    private DatabaseDeclarativeConfig createDatabaseDeclarativeConfig(String microserviceName, String namespace) {
        DatabaseDeclarativeConfig result = new DatabaseDeclarativeConfig();
        result.setType("postgresql");

        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("scope", "service");
        classifier.put("microserviceName", microserviceName);
        classifier.put("namespace", namespace);
        result.setNamespace(namespace);
        result.setClassifier(classifier);
        return result;
    }
}