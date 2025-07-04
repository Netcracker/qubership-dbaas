package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.bluegreen.AbstractDatabaseProcessObject;
import org.qubership.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import org.qubership.cloud.dbaas.dto.bluegreen.NewDatabaseProcessObject;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseDeclarativeConfigRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.*;
import static org.qubership.cloud.dbaas.service.DatabaseConfigurationCreationService.*;
import static org.qubership.cloud.dbaas.service.DatabaseRolesServiceTest.POSTGRESQL_TYPE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseConfigurationCreationServiceTest {

    DBaaService dBaaService;

    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    LogicalDbDbaasRepository logicalDbDbaasRepository;

    private ProcessService processService;


    DatabaseDeclarativeConfigRepository declarativeConfigRepository;

    DatabaseConfigurationCreationService databaseConfigurationCreationService;

    public DatabaseConfigurationCreationServiceTest() {
        dBaaService = Mockito.mock(DBaaService.class);
        logicalDbDbaasRepository = Mockito.mock(LogicalDbDbaasRepository.class);
        databaseRegistryDbaasRepository = Mockito.mock(DatabaseRegistryDbaasRepository.class);
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);

        processService = Mockito.mock(ProcessService.class);
        declarativeConfigRepository = Mockito.mock(DatabaseDeclarativeConfigRepository.class);
        this.databaseConfigurationCreationService = new DatabaseConfigurationCreationService(dBaaService,
                logicalDbDbaasRepository, processService, declarativeConfigRepository);
    }

    @Test
    void createDatabaseProcessObject() {
        DatabaseDeclarativeConfig databaseDeclarativeConfig = new DatabaseDeclarativeConfig();
        databaseDeclarativeConfig.setInstantiationApproach(NEW_MODE);
        databaseDeclarativeConfig.setClassifier(createClassifierConfig(null, "service"));

        boolean cloneToNewDatabase = false;
        String operation = APPLY_CONFIG_OPERATION;
        List<AbstractDatabaseProcessObject> databaseProcessObject = databaseConfigurationCreationService
                .createDatabaseProcessObject(databaseDeclarativeConfig, cloneToNewDatabase,
                        null, Optional.empty(), operation);
        Assertions.assertFalse(databaseProcessObject.isEmpty());
        assertNull(databaseProcessObject.get(0).getVersion());
        assertEquals(databaseDeclarativeConfig, databaseProcessObject.get(0).getConfig());
        assertTrue(databaseProcessObject.get(0) instanceof NewDatabaseProcessObject);
    }

    @Test
    void createDatabaseProcessObjectNoTenantDatabasesExists() {
        DatabaseDeclarativeConfig databaseDeclarativeConfig = new DatabaseDeclarativeConfig();
        databaseDeclarativeConfig.setInstantiationApproach(NEW_MODE);
        databaseDeclarativeConfig.setClassifier(createClassifierConfig(null, "tenant"));


        boolean cloneToNewDatabase = false;
        String operation = APPLY_CONFIG_OPERATION;
        List<AbstractDatabaseProcessObject> databaseProcessObject = databaseConfigurationCreationService
                .createDatabaseProcessObject(databaseDeclarativeConfig, cloneToNewDatabase,
                        null, Optional.empty(), operation);
        Assertions.assertTrue(databaseProcessObject.isEmpty());
    }

    @Test
    void createDatabaseProcessObjectTenantDatabase() {
        DatabaseDeclarativeConfig databaseDeclarativeConfig = new DatabaseDeclarativeConfig();
        databaseDeclarativeConfig.setInstantiationApproach(NEW_MODE);
        databaseDeclarativeConfig.setClassifier(createClassifierConfig(null, "tenant"));
        databaseDeclarativeConfig.setNamespace("namespace");

        DatabaseRegistry tenantDbr1 = new DatabaseRegistry();
        tenantDbr1.setClassifier(new TreeMap<>() {{
            put("tenantId", "1234");
        }});

        DatabaseRegistry tenantDbr2 = new DatabaseRegistry();
        tenantDbr2.setClassifier(new TreeMap<>() {{
            put("tenantId", "4321");
        }});

        when(databaseRegistryDbaasRepository.findAllTenantDatabasesInNamespace("namespace"))
                .thenReturn(List.of(tenantDbr1, tenantDbr2));

        boolean cloneToNewDatabase = false;
        String operation = APPLY_CONFIG_OPERATION;
        List<AbstractDatabaseProcessObject> databaseProcessObject = databaseConfigurationCreationService
                .createDatabaseProcessObject(databaseDeclarativeConfig, cloneToNewDatabase,
                        null, Optional.empty(), operation);
        Assertions.assertEquals(2, databaseProcessObject.size());
        assertNull(databaseProcessObject.get(0).getVersion());
        assertTrue(databaseProcessObject.stream().anyMatch(o -> o.getConfig().getClassifier().get(TENANT_ID).equals("1234")));
        assertTrue(databaseProcessObject.stream().anyMatch(o -> o.getConfig().getClassifier().get(TENANT_ID).equals("4321")));
        assertTrue(databaseProcessObject.get(0) instanceof NewDatabaseProcessObject);
        assertTrue(databaseProcessObject.get(1) instanceof NewDatabaseProcessObject);
    }

    @Test
    void createDatabaseProcessObjectTenantDatabaseClone() {
        DatabaseDeclarativeConfig databaseDeclarativeConfig = new DatabaseDeclarativeConfig();
        databaseDeclarativeConfig.setInstantiationApproach(CLONE_MODE);
        databaseDeclarativeConfig.setClassifier(createClassifierConfig(null, "tenant"));
        databaseDeclarativeConfig.setNamespace("namespace");
        databaseDeclarativeConfig.setType(POSTGRESQL_TYPE);
        SortedMap<String, Object> sourceClassifier = createClassifierConfig("toClone", "tenant");
        SortedMap<String, Object> sourceClassifier1 = createClassifierConfig("toClone", "tenant");
        SortedMap<String, Object> sourceClassifier2 = createClassifierConfig("toClone", "tenant");


        DatabaseRegistry tenantDbr1 = new DatabaseRegistry();
        sourceClassifier1.put("tenantId", "1234");
        tenantDbr1.setClassifier(sourceClassifier1);
        tenantDbr1.setType(POSTGRESQL_TYPE);
        UUID sourceId1 = UUID.randomUUID();
        tenantDbr1.setId(sourceId1);

        DatabaseRegistry tenantDbr2 = new DatabaseRegistry();
        sourceClassifier2.put("tenantId", "4321");
        tenantDbr2.setClassifier(sourceClassifier2);
        tenantDbr2.setType(POSTGRESQL_TYPE);
        UUID sourceId2 = UUID.randomUUID();
        tenantDbr2.setId(sourceId2);


        when(databaseRegistryDbaasRepository.findAllTenantDatabasesInNamespace("namespace"))
                .thenReturn(List.of(tenantDbr1, tenantDbr2));


        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(sourceClassifier1, POSTGRESQL_TYPE))
                .thenReturn(Optional.of(tenantDbr1));
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(sourceClassifier2, POSTGRESQL_TYPE))
                .thenReturn(Optional.of(tenantDbr2));

        boolean cloneToNewDatabase = false;
        String operation = APPLY_CONFIG_OPERATION;
        BgNamespace bgNamespace = new BgNamespace();
        bgNamespace.setVersion("1");
        List<AbstractDatabaseProcessObject> databaseProcessObject = databaseConfigurationCreationService
                .createDatabaseProcessObject(databaseDeclarativeConfig, cloneToNewDatabase,
                        sourceClassifier, Optional.of(bgNamespace), operation);
        Assertions.assertEquals(2, databaseProcessObject.size());
        assertTrue(databaseProcessObject.get(0) instanceof CloneDatabaseProcessObject);
        assertTrue(databaseProcessObject.get(1) instanceof CloneDatabaseProcessObject);
        assertTrue(databaseProcessObject.stream().anyMatch(o -> ((CloneDatabaseProcessObject) o).getSourceClassifier().equals(sourceClassifier1)));
        assertTrue(databaseProcessObject.stream().anyMatch(o -> ((CloneDatabaseProcessObject) o).getSourceClassifier().equals(sourceClassifier2)));
        assertEquals("1", databaseProcessObject.get(0).getVersion());
    }

    @Test
    void createDatabaseProcessObjectCloneMode() {

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);

        DatabaseDeclarativeConfig databaseDeclarativeConfig = new DatabaseDeclarativeConfig();
        databaseDeclarativeConfig.setInstantiationApproach(CLONE_MODE);
        databaseDeclarativeConfig.setType(POSTGRESQL_TYPE);
        databaseDeclarativeConfig.setNamespace("namespace");
        databaseDeclarativeConfig.setClassifier(createClassifierConfig(null, "service"));
        SortedMap<String, Object> sourceClassifier = createClassifierConfig("toClone", "service");

        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(sourceClassifier, POSTGRESQL_TYPE))
                .thenReturn(Optional.of(databaseRegistry));

        boolean cloneToNewDatabase = false;
        String operation = APPLY_CONFIG_OPERATION;
        BgNamespace bgNamespace = new BgNamespace();
        bgNamespace.setVersion("1");
        List<AbstractDatabaseProcessObject> databaseProcessObject = databaseConfigurationCreationService
                .createDatabaseProcessObject(databaseDeclarativeConfig, cloneToNewDatabase,
                        sourceClassifier, Optional.of(bgNamespace), operation);
        Assertions.assertFalse(databaseProcessObject.isEmpty());
        assertEquals(databaseDeclarativeConfig, databaseProcessObject.get(0).getConfig());
        assertTrue(databaseProcessObject.get(0) instanceof CloneDatabaseProcessObject);
        assertEquals("1", databaseProcessObject.get(0).getVersion());
    }

    @Test
    void createDatabaseProcessObjectCloneToNew() {
        DatabaseDeclarativeConfig databaseDeclarativeConfig = new DatabaseDeclarativeConfig();
        databaseDeclarativeConfig.setInstantiationApproach(CLONE_MODE);
        databaseDeclarativeConfig.setClassifier(createClassifierConfig(null, "service"));

        boolean cloneToNewDatabase = true;
        String operation = APPLY_CONFIG_OPERATION;
        List<AbstractDatabaseProcessObject> databaseProcessObject = databaseConfigurationCreationService
                .createDatabaseProcessObject(databaseDeclarativeConfig, cloneToNewDatabase,
                        null, Optional.empty(), operation);
        Assertions.assertFalse(databaseProcessObject.isEmpty());
        assertNull(databaseProcessObject.get(0).getVersion());
        assertEquals(databaseDeclarativeConfig, databaseProcessObject.get(0).getConfig());
        assertTrue(databaseProcessObject.get(0) instanceof NewDatabaseProcessObject);
    }

    @Test
    void createDatabaseProcessObjectApplyConfigCandidate() {

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);

        DatabaseDeclarativeConfig databaseDeclarativeConfig = new DatabaseDeclarativeConfig();
        databaseDeclarativeConfig.setInstantiationApproach(NEW_MODE);
        databaseDeclarativeConfig.setType(POSTGRESQL_TYPE);
        databaseDeclarativeConfig.setNamespace("namespace");
        databaseDeclarativeConfig.setClassifier(createClassifierConfig(null, "service"));
        SortedMap<String, Object> sourceClassifier = createClassifierConfig("toClone", "service");

        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(sourceClassifier, POSTGRESQL_TYPE))
                .thenReturn(Optional.of(databaseRegistry));

        boolean cloneToNewDatabase = false;
        String operation = APPLY_CONFIG_OPERATION;

        BgDomain bgDomain = new BgDomain();

        BgNamespace bgNamespace1 = new BgNamespace();
        bgNamespace1.setState(CANDIDATE_STATE);
        bgNamespace1.setVersion("1");
        bgNamespace1.setBgDomain(bgDomain);

        BgNamespace bgNamespace2 = new BgNamespace();
        bgNamespace2.setState(ACTIVE_STATE);
        bgNamespace2.setVersion("1");
        bgNamespace2.setBgDomain(bgDomain);

        bgDomain.setNamespaces(List.of(bgNamespace1, bgNamespace2));

        List<AbstractDatabaseProcessObject> databaseProcessObject = databaseConfigurationCreationService
                .createDatabaseProcessObject(databaseDeclarativeConfig, cloneToNewDatabase,
                        sourceClassifier, Optional.of(bgNamespace1), operation);
        Assertions.assertFalse(databaseProcessObject.isEmpty());
        assertEquals(databaseDeclarativeConfig, databaseProcessObject.get(0).getConfig());
        assertTrue(databaseProcessObject.get(0) instanceof NewDatabaseProcessObject);
        assertEquals("1", databaseProcessObject.get(0).getVersion());
    }

    @Test
    void commitDatabasesService() {
        String namespace = "test_namespace";
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("microserviceName", "microserviceName");
        classifier.put("scope", "service");
        classifier.put("namespace", "namespace");
        String type = "postgresql";
        databaseConfigurationCreationService.commitDatabases(classifier, type, namespace);
        Mockito.verify(databaseRegistryDbaasRepository).getDatabaseByClassifierAndType(classifier, type);
        Mockito.verify(dBaaService).markVersionedDatabasesAsOrphan(anyList());
        Mockito.verify(dBaaService).dropDatabasesAsync(eq(namespace), anyList());
    }

    @Test
    void commitDatabasesTenant() {
        String namespace = "test_namespace";
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("microserviceName", "microserviceName");
        classifier.put("scope", "tenant");
        classifier.put("tenantId", "123");
        classifier.put("namespace", "namespace");
        String type = "postgresql";
        databaseConfigurationCreationService.commitDatabases(classifier, type, namespace);
        Mockito.verify(databaseRegistryDbaasRepository).findAllTenantDatabasesInNamespace(namespace);
        Mockito.verify(dBaaService).markVersionedDatabasesAsOrphan(anyList());
        Mockito.verify(dBaaService).dropDatabasesAsync(eq(namespace), anyList());
    }

    @Test
    void isAllDatabaseExists() {

        DatabaseDeclarativeConfig databaseDeclarativeConfig = new DatabaseDeclarativeConfig();
        databaseDeclarativeConfig.setInstantiationApproach(NEW_MODE);
        SortedMap<String, Object> sourceClassifier = createClassifierConfig(null, "service");
        databaseDeclarativeConfig.setClassifier(sourceClassifier);
        databaseDeclarativeConfig.setType(POSTGRESQL_TYPE);
        databaseDeclarativeConfig.setVersioningType(STATIC_STATE);

        Database database = new Database();
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setClassifier(sourceClassifier);
        databaseRegistry.setDatabase(database);
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(sourceClassifier, POSTGRESQL_TYPE))
                .thenReturn(Optional.of(databaseRegistry));
        DatabaseExistence exists = databaseConfigurationCreationService.isAllDatabaseExists(databaseDeclarativeConfig, null);
        Assertions.assertTrue(exists.isExist());
        Assertions.assertTrue(exists.isActual());
    }

    @Test
    void isAllTenantDatabaseExists() {
        SortedMap<String, Object> sourceClassifier = createClassifierConfig("toClone", "tenant");

        DatabaseDeclarativeConfig databaseDeclarativeConfig = new DatabaseDeclarativeConfig();
        databaseDeclarativeConfig.setInstantiationApproach(NEW_MODE);
        databaseDeclarativeConfig.setClassifier(sourceClassifier);
        databaseDeclarativeConfig.setNamespace("namespace");
        databaseDeclarativeConfig.setType(POSTGRESQL_TYPE);
        databaseDeclarativeConfig.setVersioningType(STATIC_STATE);


        SortedMap<String, Object> sourceClassifier1 = createClassifierConfig("toClone", "tenant");
        SortedMap<String, Object> sourceClassifier2 = createClassifierConfig("toClone", "tenant");

//        Database database = new Database();

        DatabaseRegistry tenantDbr1 = new DatabaseRegistry();
        sourceClassifier1.put("tenantId", "1234");
        tenantDbr1.setClassifier(sourceClassifier1);
        tenantDbr1.setType(POSTGRESQL_TYPE);
        UUID sourceId1 = UUID.randomUUID();
        tenantDbr1.setId(sourceId1);
        tenantDbr1.setDatabase(new Database());

        DatabaseRegistry tenantDbr2 = new DatabaseRegistry();
        sourceClassifier2.put("tenantId", "4321");
        tenantDbr2.setClassifier(sourceClassifier2);
        tenantDbr2.setType(POSTGRESQL_TYPE);
        UUID sourceId2 = UUID.randomUUID();
        tenantDbr2.setId(sourceId2);
        tenantDbr2.setDatabase(new Database());

        when(databaseRegistryDbaasRepository.findAllTenantDatabasesInNamespace("namespace"))
                .thenReturn(List.of(tenantDbr1, tenantDbr2));


        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(sourceClassifier1, POSTGRESQL_TYPE))
                .thenReturn(Optional.of(tenantDbr1));

        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(sourceClassifier2, POSTGRESQL_TYPE))
                .thenReturn(Optional.of(tenantDbr2));


        DatabaseExistence exists = databaseConfigurationCreationService.isAllDatabaseExists(databaseDeclarativeConfig, "namespace");
        Assertions.assertTrue(exists.isExist());
        Assertions.assertTrue(exists.isActual());
    }


    private SortedMap<String, Object> createClassifierConfig(String testClassifierValue, String scope) {
        SortedMap<String, Object> classifier = new TreeMap<>();
        if (testClassifierValue != null) {
            classifier.put("test-key", testClassifierValue);
        }
        classifier.put("microserviceName", "microserviceName");
        classifier.put("scope", scope);
        classifier.put("namespace", "namespace");
        return classifier;
    }
}