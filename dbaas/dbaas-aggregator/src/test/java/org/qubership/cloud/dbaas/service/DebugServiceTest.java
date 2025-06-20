package org.qubership.cloud.dbaas.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.RuleType;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.*;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.entity.pg.rule.PerMicroserviceRule;
import org.qubership.cloud.dbaas.entity.pg.rule.PerNamespaceRule;
import org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus;
import org.qubership.cloud.dbaas.monitoring.indicators.AggregatedHealthResponse;
import org.qubership.cloud.dbaas.monitoring.indicators.HealthStatus;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.*;
import org.qubership.cloud.dbaas.mapper.DebugLogicalDatabaseMapper;
import org.qubership.cloud.dbaas.entity.dto.DebugLogicalDatabasePersistenceDto;
import org.qubership.cloud.dbaas.repositories.queries.DebugLogicalDatabaseQueries;
import org.qubership.cloud.dbaas.test.data.provider.debug.DebugLogicalDatabaseTestDataProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.core.StreamingOutput;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus.HEALTH_CHECK_STATUS_UP;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DebugServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final DebugLogicalDatabaseTestDataProvider testDataProvider =
        new DebugLogicalDatabaseTestDataProvider();

    @Mock
    private BalancingRulesRepository namespaceRulesRepository;
    @Mock
    private BalanceRulesRepositoryPerMicroservice microserviceRulesRepository;
    @Mock
    private PhysicalDatabasesRepository physicalDatabasesRepository;
    @Mock
    private DatabasesRepository databasesRepository;
    @Mock
    private DatabaseDeclarativeConfigRepository databaseDeclarativeConfigRepository;
    @Mock
    private BgDomainRepository bgDomainRepository;
    @Mock
    private PhysicalDatabasesService physicalDatabasesService;
    @Mock
    private ResponseHelper responseHelper;
    @Mock
    private LogicalDbDbaasRepository logicalDbDbaasRepository;
    @Mock
    private DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Mock
    private HealthService healthService;
    @Spy
    private DebugLogicalDatabaseMapper debugLogicalDatabaseMapper =
        Mappers.getMapper(DebugLogicalDatabaseMapper.class);

    @InjectMocks
    private DebugService debugService;

    @Test
    void testLoadDumpV3() throws JsonProcessingException {
        var physicalDatabases = List.of(getGlobalPhysicalDatabase(), getNotGlobalPhysicalDatabase());
        var logicalDatabases = List.of(getLogicalDatabase());
        var declarativeConfigs = List.of(getDeclarativeConfig());
        var blueGreenDomains = List.of(getBlueGreenDomain());
        var perMicroserviceRules = List.of(getPerMicroserviceRule());

        var perNamespaceRuleWithNamespaceRuleType = getPerNamespaceRuleWithNamespaceRuleType();
        var perNamespaceRuleWithPermanentRuleType = getPerNamespaceRuleWithPermanentRuleType();

        Mockito.doReturn(physicalDatabases).when(physicalDatabasesRepository).listAll();
        Mockito.doReturn(logicalDatabases).when(databasesRepository).listAll();
        Mockito.doReturn(declarativeConfigs).when(databaseDeclarativeConfigRepository).listAll();
        Mockito.doReturn(blueGreenDomains).when(bgDomainRepository).listAll();
        Mockito.doReturn(List.of(perNamespaceRuleWithNamespaceRuleType, perNamespaceRuleWithPermanentRuleType)).when(namespaceRulesRepository).listAll();
        Mockito.doReturn(perMicroserviceRules).when(microserviceRulesRepository).listAll();

        var actualDumpResponse = debugService.loadDumpV3();

        Mockito.verify(physicalDatabasesRepository).listAll();
        Mockito.verify(databasesRepository).listAll();
        Mockito.verify(databaseDeclarativeConfigRepository).listAll();
        Mockito.verify(bgDomainRepository).listAll();
        Mockito.verify(namespaceRulesRepository).listAll();
        Mockito.verify(microserviceRulesRepository).listAll();

        Mockito.verifyNoMoreInteractions(physicalDatabasesRepository, databasesRepository,
            databaseDeclarativeConfigRepository, bgDomainRepository, namespaceRulesRepository,
            microserviceRulesRepository
        );

        var defaultRules = List.of(getDefaultRule());
        var expectedDumpResponse = new DumpResponseV3(
            new DumpRulesV3(
                defaultRules,
                List.of(perNamespaceRuleWithNamespaceRuleType),
                perMicroserviceRules,
                List.of(perNamespaceRuleWithPermanentRuleType)
            ), logicalDatabases, physicalDatabases, declarativeConfigs, blueGreenDomains
        );

        var actualDumpResponseStr = objectMapper.writeValueAsString(actualDumpResponse);
        var expectedDumpResponseStr = objectMapper.writeValueAsString(expectedDumpResponse);

        Assertions.assertEquals(expectedDumpResponseStr, actualDumpResponseStr);
    }

    @Test
    void testGetStreamingOutputSerializingDumpToZippedJsonFile() throws Exception {
        DumpResponseV3 expectedDumpResponse = new DumpResponseV3(
            new DumpRulesV3(List.of(), List.of(), List.of(), List.of()),
            List.of(), List.of(), List.of(), List.of()
        );

        StreamingOutput streamingOutput = debugService.getStreamingOutputSerializingDumpToZippedJsonFile(expectedDumpResponse);

        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            streamingOutput.write(byteArrayOutputStream);

            try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
                ZipInputStream zipInputStream = new ZipInputStream(byteArrayInputStream)) {
                ZipEntry zipEntry = zipInputStream.getNextEntry();

                Assertions.assertNotNull(zipEntry,
                    "Zip file must contains single zip entry with name: " + DebugService.DUMP_JSON_FILENAME
                );
                Assertions.assertEquals(DebugService.DUMP_JSON_FILENAME, zipEntry.getName(),
                    "Single zip entry must have name: " + DebugService.DUMP_JSON_FILENAME
                );

                String dumpJsonString = IOUtils.toString(zipInputStream, StandardCharsets.UTF_8);

                DumpResponseV3 actualDumpResponse = objectMapper.readValue(dumpJsonString, DumpResponseV3.class);

                Assertions.assertEquals(expectedDumpResponse, actualDumpResponse);
                Assertions.assertNull(zipInputStream.getNextEntry(),
                    "Zip file must contain only one zip entry"
                );
            }
        }

    }

    @Test
    void testGetOverallStatus() {
        when(healthService.getHealth()).thenReturn(new AggregatedHealthResponse(HealthStatus.UP, Collections.emptyMap()));
        DbaasAdapter adapter = mock(DbaasAdapter.class);
        when(adapter.isDisabled()).thenReturn(false);
        when(adapter.identifier()).thenReturn("adapter");

        when(adapter.getDatabases()).thenReturn(Set.of("db1", "db2"));
        when(adapter.getAdapterHealth()).thenReturn(new AdapterHealthStatus(HEALTH_CHECK_STATUS_UP));
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));
        String type = "postgresql";
        DatabaseRegistry registeredDb1 = createDatabase(type, "adapter", "username", "db1");
        DatabaseRegistry registeredDb2 = createDatabase(type, "adapter", "username", "db2");
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.findAllInternalDatabases()).thenReturn(List.of(registeredDb1, registeredDb2));
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setPhysicalDatabaseIdentifier("test-id");
        when(physicalDatabasesService.getByAdapterId("adapter")).thenReturn(physicalDatabase);

        OverallStatusResponse overallStatusResponse = debugService.getOverallStatus();
        Assertions.assertEquals(Integer.valueOf(2), overallStatusResponse.getOverallLogicalDbNumber());
        Assertions.assertEquals("UP", overallStatusResponse.getOverallHealthStatus());
        Assertions.assertEquals(1, overallStatusResponse.getPhysicalDatabaseInfoList().size());
        Assertions.assertEquals("2", overallStatusResponse.getPhysicalDatabaseInfoList().get(0).getLogicalDbNumber());
    }

    private DatabaseRegistry createDatabase(String type, String adapterId, String username, String dbName) {
        Database database = new Database();
        database.setId(UUID.randomUUID());
        ArrayList<Map<String, Object>> connectionProperties = new ArrayList<>(List.of(new HashMap<String, Object>() {{
            put("username", username);
            put(ROLE, Role.ADMIN.toString());
        }}));
        database.setConnectionProperties(connectionProperties);
        Map<String, String> classifier = new HashMap<>();
        classifier.put("microserviceName", "test-name");

        database.setClassifier(new TreeMap<>(classifier));
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setTimeDbCreation(new Date());
        databaseRegistry.setNamespace("test-namespace");
        databaseRegistry.setClassifier(new TreeMap<>(classifier));
        databaseRegistry.setType(type);
        database.setDatabaseRegistry(Arrays.asList(databaseRegistry));
        databaseRegistry.setDatabase(database);
        database.setName(dbName);
        database.setAdapterId(adapterId);
        database.setSettings(new HashMap<String, Object>() {{
            put("setting-one", "value-one");
        }});
        database.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        database.setResources(new LinkedList<>(Arrays.asList(new DbResource("username", username),
                new DbResource("database", dbName))));
        database.setPhysicalDatabaseId("test-id");
        return databaseRegistry;
    }


    @Test
    void testFindDebugLogicalDatabasesWithNullFilterRsqlQuery() throws JsonProcessingException {
        var actualNativeSqlQuery = doTestFindDebugLogicalDatabasesWithFilterRsqlQueryAndReturnConstructedNativeSqlQuery(
            null
        );

        Assertions.assertEquals(
            DebugLogicalDatabaseQueries.FIND_DEBUG_LOGICAL_DATABASES,
            actualNativeSqlQuery
        );
    }

    @Test
    void testFindDebugLogicalDatabasesWithFilledFilterRsqlQuery() throws JsonProcessingException {
        var filterRsqlQuery = """
            namespace==dbaas-autotests;microservice==dbaas-declarative-service;tenantId==ce22b065-1e61-4076-99b1-e397b6da741b;
            logicalDbName==configs;bgVersion==2;type!=clickhouse;roles=in=("ro","rw");name==dbaas-test-service_dbaas-autotests_175112571071124;
            physicalDbId==postgresql-dev:postgres;physicalDbAdapterUrl==http://dbaas-postgres-adapter.postgresql-dev:8080
            """.lines().collect(Collectors.joining());

        var actualNativeSqlQuery = doTestFindDebugLogicalDatabasesWithFilterRsqlQueryAndReturnConstructedNativeSqlQuery(
            filterRsqlQuery
        );

        var expectedFilterNativeSqlQuery = """
            \\(logical_database.classifier->>'namespace' = :param_namespace_value_\\d+ AND logical_database.classifier->>'microserviceName' = :param_microservice_value_\\d+ AND logical_database.classifier->>'tenantId' = :param_tenantId_value_\\d+ AND \\(logical_database.classifier->'custom_keys'->>'logicalDBName' = :param_logicalDbName_value_\\d+
                OR logical_database.classifier->'custom_keys'->>'logicalDbName' = :param_logicalDbName_value_\\d+
                OR logical_database.classifier->'custom_keys'->>'logicalDbId' = :param_logicalDbName_value_\\d+
                OR logical_database.classifier->'customKeys'->>'logicalDBName' = :param_logicalDbName_value_\\d+
                OR logical_database.classifier->'customKeys'->>'logicalDbName' = :param_logicalDbName_value_\\d+
                OR logical_database.classifier->'customKeys'->>'logicalDbId' = :param_logicalDbName_value_\\d+
            \\)
             AND logical_database.bgversion = :param_bgVersion_value_\\d+ AND logical_database.type IS DISTINCT FROM :param_type_value_\\d+ AND EXISTS \\(
                SELECT 1
                FROM jsonb_array_elements\\(logical_database.connection_properties::jsonb\\) AS conn_props_jsonb
                WHERE conn_props_jsonb->>'role' IN :param_roles_value_\\d+
            \\)
             AND logical_database.name = :param_name_value_\\d+ AND logical_database.physical_database_id = :param_physicalDbId_value_\\d+ AND external_adapter_registration.address = :param_physicalDbAdapterUrl_value_\\d+\\)""";

        Assertions.assertTrue(actualNativeSqlQuery.matches(
            DebugLogicalDatabaseQueries.FIND_DEBUG_LOGICAL_DATABASES + DebugService.WHERE_CLAUSE + expectedFilterNativeSqlQuery
        ));
    }

    protected String doTestFindDebugLogicalDatabasesWithFilterRsqlQueryAndReturnConstructedNativeSqlQuery(String filterRsqlQuery) throws JsonProcessingException {
        var expectedDebugLogicalDatabases = testDataProvider.getExpectedDebugLogicalDatabases();
        var actualDebugLogicalDatabasePersistenceDtos = testDataProvider.getActualDebugLogicalDatabasePersistenceDtos();

        var mockedEntityManager = Mockito.mock(EntityManager.class);
        var mockedNativeQuery = Mockito.mock(Query.class);

        Mockito.doReturn(mockedEntityManager)
            .when(databasesRepository).getEntityManager();

        Mockito.doReturn(mockedNativeQuery)
            .when(mockedEntityManager).createNativeQuery(
                Mockito.anyString(), Mockito.eq(DebugLogicalDatabasePersistenceDto.class)
            );

        Mockito.doReturn(actualDebugLogicalDatabasePersistenceDtos)
            .when(mockedNativeQuery).getResultList();

        var actualDebugLogicalDatabases = debugService.findDebugLogicalDatabases(filterRsqlQuery);

        var queryArgumentCaptor = ArgumentCaptor.forClass(String.class);

        Mockito.verify(databasesRepository).getEntityManager();

        Mockito.verify(mockedEntityManager).createNativeQuery(
            queryArgumentCaptor.capture(), Mockito.eq(DebugLogicalDatabasePersistenceDto.class)
        );

        Mockito.verify(mockedNativeQuery).getResultList();

        Mockito.verify(debugLogicalDatabaseMapper)
            .convertDebugLogicalDatabases(actualDebugLogicalDatabasePersistenceDtos);

        Mockito.verifyNoMoreInteractions(mockedEntityManager);

        Assertions.assertEquals(
            objectMapper.valueToTree(expectedDebugLogicalDatabases),
            objectMapper.valueToTree(actualDebugLogicalDatabases)
        );

        return queryArgumentCaptor.getValue();
    }

    protected PhysicalDatabase getGlobalPhysicalDatabase() {
        var adapter = new ExternalAdapterRegistrationEntry();
        adapter.setAddress("http://postgres-db-1.postgres-dev:8080");

        var physicalDatabase = new PhysicalDatabase();

        physicalDatabase.setId("physicalDatabaseId1");
        physicalDatabase.setGlobal(true);
        physicalDatabase.setAdapter(adapter);

        return physicalDatabase;
    }

    protected PhysicalDatabase getNotGlobalPhysicalDatabase() {
        var adapter = new ExternalAdapterRegistrationEntry();
        adapter.setAddress("http://postgres-db-2.postgres-dev:8080");

        var physicalDatabase = new PhysicalDatabase();

        physicalDatabase.setId("physicalDatabaseId2");
        physicalDatabase.setGlobal(false);
        physicalDatabase.setAdapter(adapter);

        return physicalDatabase;
    }

    protected Database getLogicalDatabase() {
        var database = new Database();

        database.setId(UUID.fromString("8811590d-6f2b-4e56-bca1-edd1c6c548eb"));
        database.setName("logicalDatabaseName1");
        database.setAdapterId("logicalDatabaseAdapterId1");

        return database;
    }

    protected DatabaseDeclarativeConfig getDeclarativeConfig() {
        var declarativeConfig = new DatabaseDeclarativeConfig();

        declarativeConfig.setId(UUID.fromString("ce3f40ce-7fc9-46ee-809d-21365e5a60ae"));
        declarativeConfig.setType("declarativeConfigType1");

        return declarativeConfig;
    }

    protected BgDomain getBlueGreenDomain() {
        var blueGreenDomain = new BgDomain();

        blueGreenDomain.setId(UUID.fromString("cadc8fe9-def3-4f83-a4a8-f442296722b8"));
        blueGreenDomain.setControllerNamespace("controllerNamespace1");

        return blueGreenDomain;
    }

    protected PerNamespaceRule getPerNamespaceRuleWithNamespaceRuleType() {
        var perNamespaceRule = new PerNamespaceRule();

        perNamespaceRule.setName("perNamespaceRuleName1");
        perNamespaceRule.setRuleType(RuleType.NAMESPACE);
        perNamespaceRule.setOrder(1L);

        return perNamespaceRule;
    }

    protected PerNamespaceRule getPerNamespaceRuleWithPermanentRuleType() {
        var perNamespaceRule = new PerNamespaceRule();

        perNamespaceRule.setName("perNamespaceRuleName2");
        perNamespaceRule.setRuleType(RuleType.PERMANENT);
        perNamespaceRule.setOrder(2L);

        return perNamespaceRule;
    }

    protected PerMicroserviceRule getPerMicroserviceRule() {
        var microserviceRule = new PerMicroserviceRule();

        microserviceRule.setId(UUID.fromString("6f2def31-0a73-4aa5-9286-ea1c58f0e1e2"));
        microserviceRule.setType("microserviceRuleType1");
        microserviceRule.setGeneration(1);

        return microserviceRule;
    }

    protected DumpDefaultRuleV3 getDefaultRule() {
        var defaultRule = new DumpDefaultRuleV3();

        defaultRule.setPhysicalDatabaseId("physicalDatabaseId1");
        defaultRule.setAddress("http://postgres-db-1.postgres-dev:8080");

        return defaultRule;
    }

    @Test
    void testFindLostDatabases() {
        DbaasAdapter adapter = mock(DbaasAdapter.class);
        when(adapter.isDisabled()).thenReturn(false);
        when(adapter.identifier()).thenReturn("adapter");

        when(adapter.getDatabases()).thenReturn(Set.of("db1", "db2"));
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));
        String type = "postgresql";
        DatabaseRegistry registeredDb1 = createDatabase(type, "adapter", "username", "db1");
        DatabaseRegistry registeredDb3 = createDatabase(type, "adapter", "username", "db3");
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.findAllInternalDatabases()).thenReturn(List.of(registeredDb1, registeredDb3));
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setPhysicalDatabaseIdentifier("test-id");
        when(physicalDatabasesService.getByAdapterId("adapter")).thenReturn(physicalDatabase);
        when(responseHelper.toDatabaseResponse(List.of(registeredDb3), false))
                .thenReturn(convertDatabaseToDatabaseListCp(List.of(registeredDb3)));

        List<LostDatabasesResponse> lostDatabases = debugService.findLostDatabases();
        assertEquals(1, lostDatabases.size());
        assertEquals("db3", lostDatabases.get(0).getDatabases().get(0).getName());
    }

    @Test
    void testFindLostDatabasesCatchEx() {
        DbaasAdapter adapter = mock(DbaasAdapter.class);
        when(adapter.isDisabled()).thenReturn(false);
        when(adapter.identifier()).thenReturn("adapter");

        when(adapter.getDatabases()).thenThrow(RuntimeException.class);
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));
        String type = "postgresql";
        DatabaseRegistry registeredDb1 = createDatabase(type, "adapter", "username", "db1");
        DatabaseRegistry registeredDb3 = createDatabase(type, "adapter", "username", "db3");
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.findAllInternalDatabases()).thenReturn(List.of(registeredDb1, registeredDb3));
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setPhysicalDatabaseIdentifier("test-id");
        when(physicalDatabasesService.getByAdapterId("adapter")).thenReturn(physicalDatabase);

        assertDoesNotThrow(() -> debugService.findLostDatabases());
    }

    @Test
    void testFindGhostDatabases() {
        DbaasAdapter adapter = mock(DbaasAdapter.class);
        when(adapter.isDisabled()).thenReturn(false);
        when(adapter.identifier()).thenReturn("adapter");
        when(adapter.getDatabases()).thenReturn(Set.of("db1", "db2", "dbGhost"));

        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));
        String type = "postgresql";
        DatabaseRegistry registeredDb1 = createDatabase(type, "adapter", "username", "db1");
        DatabaseRegistry registeredDb2 = createDatabase(type, "adapter", "username", "db2");

        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setPhysicalDatabaseIdentifier("physical_db_1");
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.findAllInternalDatabases()).thenReturn(List.of(registeredDb1, registeredDb2));
        when(physicalDatabasesService.getByAdapterId("adapter")).thenReturn(physicalDatabase);

        List<GhostDatabasesResponse> ghostDatabases = debugService.findGhostDatabases();
        assertEquals(1, ghostDatabases.size());
        assertEquals("physical_db_1", ghostDatabases.get(0).getPhysicalDatabaseId());
        assertEquals("dbGhost", ghostDatabases.get(0).getDbNames().get(0));
    }

    @Test
    void testFindLostDatabasesWithNoLostDatabases() {
        DbaasAdapter adapter = mock(DbaasAdapter.class);
        when(adapter.isDisabled()).thenReturn(false);
        when(adapter.identifier()).thenReturn("adapter");
        when(adapter.getDatabases()).thenReturn(Set.of("db1", "db2"));
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));

        String type = "postgresql";
        DatabaseRegistry registeredDb1 = createDatabase(type, "adapter", "username", "db1");
        DatabaseRegistry registeredDb2 = createDatabase(type, "adapter", "username", "db2");

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.findAllInternalDatabases()).thenReturn(List.of(registeredDb1, registeredDb2));
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setPhysicalDatabaseIdentifier("test-id");
        when(physicalDatabasesService.getByAdapterId("adapter")).thenReturn(physicalDatabase);
        when(responseHelper.toDatabaseResponse(Collections.emptyList(), false))
                .thenReturn(Collections.emptyList());


        List<LostDatabasesResponse> lostDatabases = debugService.findLostDatabases();
        assertEquals(0, lostDatabases.get(0).getDatabases().size());
    }


    @Test
    void testFindGhostDatabasesWithNoGhostDatabases() {
        DbaasAdapter adapter = mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));
        when(adapter.isDisabled()).thenReturn(false);
        when(adapter.identifier()).thenReturn("adapter");

        when(adapter.getDatabases()).thenReturn(Set.of("db1", "db2"));
        String type = "postgresql";
        DatabaseRegistry registeredDb1 = createDatabase(type, "adapter", "username", "db1");
        DatabaseRegistry registeredDb2 = createDatabase(type, "adapter", "username", "db2");

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.findAllInternalDatabases()).thenReturn(List.of(registeredDb1, registeredDb2));
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setPhysicalDatabaseIdentifier("test-id");
        when(physicalDatabasesService.getByAdapterId("adapter")).thenReturn(physicalDatabase);


        List<GhostDatabasesResponse> ghostDatabases = debugService.findGhostDatabases();
        assertEquals(0, ghostDatabases.get(0).getDbNames().size());
    }

    @Test
    void testFindGhostDatabasesCatchEx() {
        DbaasAdapter adapter = mock(DbaasAdapter.class);
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(adapter));
        when(adapter.isDisabled()).thenReturn(false);
        when(adapter.identifier()).thenReturn("adapter");

        when(adapter.getDatabases()).thenThrow(RuntimeException.class);
        String type = "postgresql";
        DatabaseRegistry registeredDb1 = createDatabase(type, "adapter", "username", "db1");
        DatabaseRegistry registeredDb2 = createDatabase(type, "adapter", "username", "db2");

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.findAllInternalDatabases()).thenReturn(List.of(registeredDb1, registeredDb2));
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setPhysicalDatabaseIdentifier("test-id");
        when(physicalDatabasesService.getByAdapterId("adapter")).thenReturn(physicalDatabase);
        assertDoesNotThrow(() -> debugService.findLostDatabases());
    }

    private List<DatabaseResponseV3ListCP> convertDatabaseToDatabaseListCp(List<DatabaseRegistry> databases) {
        List<DatabaseResponseV3ListCP> databaseResponseV3ListCPList = new ArrayList<>();
        for (DatabaseRegistry database : databases) {
            DatabaseResponseV3ListCP databaseResponseV3ListCP = new DatabaseResponseV3ListCP();
            databaseResponseV3ListCP.setName(database.getName());
            databaseResponseV3ListCPList.add(databaseResponseV3ListCP);
        }
        return databaseResponseV3ListCPList;
    }
}
