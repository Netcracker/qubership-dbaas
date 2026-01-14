package com.netcracker.it.dbaas.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.netcracker.it.dbaas.entity.*;
import com.netcracker.it.dbaas.helpers.BalancingRulesHelperV3;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;

import static com.netcracker.it.dbaas.entity.DebugRulesDbTypeData.*;
import static com.netcracker.it.dbaas.entity.RuleBody.RuleType.perNamespace;
import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.TEST_MICROSERVICE_NAME;
import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.TEST_NAMESPACE;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
class BalancingRulesV3IT extends AbstractIT {
    private static final String PHYSICAL_DATABASE_IDENTIFIER = "phydbid";
    private static final String PER_NAMESPACE = "perNamespace";
    public final static String ALL = "all";
    public final static UUID label_UUID = UUID.randomUUID();
    public final static String label_ID = label_UUID.toString();

    private final static String TEST_PHYS_DB_ID = "test-db-id";

    private static BalancingRulesHelperV3 balancingRulesHelperV3;

    @BeforeAll
    void setUp() {
        balancingRulesHelperV3 = new BalancingRulesHelperV3(helperV3);
    }

    @BeforeEach
    public void initAndCleanDbs() throws IOException {
        cleanDbs();
    }

    @AfterEach
    public void cleanDbs() throws IOException {
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
    }

    @Test
    @Tag("Smoke")
    void testRuleOnMicroserviceNoConflictOnExistingDB() throws IOException {
        Map.Entry<String, String> label = balancingRulesHelperV3.getUniqLabelsByDbType(POSTGRES_TYPE);
        assumeTrue(label != null, "No unique labels in physical databases");
        helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null);
        try (Response response = createOnMicroserviceRule(
                List.of(TEST_MICROSERVICE_NAME), POSTGRES_TYPE, List.of(new RuleOnMicroservice(label.getKey() + "=" + label.getValue())))) {
            assertEquals(201, response.code());
        }
    }

    @Test
    void testRuleOnMicroserviceNoConflictRecreateRule() throws IOException {
        Map.Entry<String, String> label = balancingRulesHelperV3.getUniqLabelsByDbType(POSTGRES_TYPE);
        assumeTrue(label != null, "No unique labels in physical databases");
        try (Response response = createOnMicroserviceRule(
                List.of(TEST_MICROSERVICE_NAME), POSTGRES_TYPE, List.of(new RuleOnMicroservice(label.getKey() + "=" + label.getValue())))) {
            assertEquals(201, response.code());
        }
        helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null);
        try (Response response2 = createOnMicroserviceRule(
                List.of(TEST_MICROSERVICE_NAME), POSTGRES_TYPE, List.of(new RuleOnMicroservice(label.getKey() + "=" + label.getValue())))) {
            assertEquals(201, response2.code());
        }
    }

    @Test
    void testRuleOnMicroserviceCorrectGeneration() throws IOException {
        List<BalancingRulesHelperV3.DBWithUniqLabel> allUniqLabelsByDbType = balancingRulesHelperV3.getAllDBsWithUniqLabels(ALL_DB_TYPES);
        assumeTrue(allUniqLabelsByDbType.size() >= 2, "Not enough unique labels in physical databases");
        String dbType1 = allUniqLabelsByDbType.getFirst().getDbType();
        String dbType2 = allUniqLabelsByDbType.getLast().getDbType();
        String label1 = allUniqLabelsByDbType.getFirst().getLabel().getKey() + "=" + allUniqLabelsByDbType.getFirst().getLabel().getValue();
        String label2 = allUniqLabelsByDbType.getLast().getLabel().getKey() + "=" + allUniqLabelsByDbType.getLast().getLabel().getValue();
        log.info("Will use {} DB with {} label and {} DB with {} label", dbType1, label1, dbType2, label2);

        OnMicroserviceRuleRequest ruleRequestBody1 = new OnMicroserviceRuleRequest(dbType1, Collections.singletonList(new RuleOnMicroservice(label1)), Collections.singletonList(TEST_MICROSERVICE_NAME));
        OnMicroserviceRuleRequest ruleRequestBody2 = new OnMicroserviceRuleRequest(dbType2, Collections.singletonList(new RuleOnMicroservice(label2)), Collections.singletonList(TEST_MICROSERVICE_NAME));
        try (Response response = helperV3.sendRequest(String.format("api/v3/dbaas/%s/physical_databases/rules/onMicroservices", TEST_NAMESPACE), List.of(ruleRequestBody1, ruleRequestBody2), "PUT")) {
            assertEquals(201, response.code());
        }

        OnMicroserviceRuleRequest ruleRequestBody3 = new OnMicroserviceRuleRequest(dbType1, Collections.singletonList(new RuleOnMicroservice(label1)), Collections.singletonList(TEST_MICROSERVICE_NAME));
        try (Response response = helperV3.sendRequest(String.format("api/v3/dbaas/%s/physical_databases/rules/onMicroservices", TEST_NAMESPACE), List.of(ruleRequestBody3), "PUT")) {
            assertEquals(201, response.code());
            var responseBodyStr = response.body().string();
            var onMicroserviceRules = helperV3.objectMapper.readValue(
                    responseBodyStr, new TypeReference<List<PerMicroserviceRuleDTO>>() {
                    }
            );
            assertEquals(1, onMicroserviceRules.getFirst().getGeneration());
        }

        OnMicroserviceRuleRequest ruleRequestBody4 = new OnMicroserviceRuleRequest(dbType2, Collections.singletonList(new RuleOnMicroservice(label2)), Collections.singletonList(TEST_MICROSERVICE_NAME));
        try (Response response = helperV3.sendRequest(String.format("api/v3/dbaas/%s/physical_databases/rules/onMicroservices", TEST_NAMESPACE), List.of(ruleRequestBody4), "PUT")) {
            assertEquals(201, response.code());
            var responseBodyStr = response.body().string();
            var onMicroserviceRules = helperV3.objectMapper.readValue(
                    responseBodyStr, new TypeReference<List<PerMicroserviceRuleDTO>>() {
                    }
            );
            assertEquals(1, onMicroserviceRules.getFirst().getGeneration());
        }
    }

    @Test
    void testRuleOnMicroserviceNoConflictRecreateRuleMulitMicroservice() throws IOException {
        Map.Entry<String, String> label = balancingRulesHelperV3.getUniqLabelsByDbType(POSTGRES_TYPE);
        assumeTrue(label != null, "No unique labels in physical databases");
        try (Response response = createOnMicroserviceRule(
                Arrays.asList(TEST_MICROSERVICE_NAME, "test-microservice"), POSTGRES_TYPE, List.of(new RuleOnMicroservice(label.getKey() + "=" + label.getValue())))) {
            assertEquals(201, response.code());
        }
        helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null);
        try (Response response2 = createOnMicroserviceRule(
                Arrays.asList(TEST_MICROSERVICE_NAME, "test-microservice"), POSTGRES_TYPE, List.of(new RuleOnMicroservice(label.getKey() + "=" + label.getValue())))) {
            assertEquals(201, response2.code());
        }
    }

    @Test
    void testRuleOnMicroserviceBadRequestMoreThanOneRule() throws IOException {
        Map.Entry<String, String> label = balancingRulesHelperV3.getUniqLabelsByDbType(POSTGRES_TYPE);
        assumeTrue(label != null, "No unique labels in physical databases");
        try (Response response = createOnMicroserviceRule(
                List.of(TEST_MICROSERVICE_NAME), POSTGRES_TYPE, Arrays.asList(new RuleOnMicroservice("new_test=label"), new RuleOnMicroservice(label.getKey() + "=" + label.getValue())))) {
            assertEquals(400, response.code());
        }
    }


    @Test
    void testRuleOnMicroserviceNotFoundLabel() throws IOException {
        try (Response response = createOnMicroserviceRule(
                List.of(TEST_MICROSERVICE_NAME), POSTGRES_TYPE, List.of(new RuleOnMicroservice("new_test=label")))) {
            assertEquals(500, response.code());
        }
    }

    @Test
    void testGetOnMicroservicePhysicalDatabaseBalancingRules() throws IOException {
        var label = balancingRulesHelperV3.getUniqLabelsByDbType(POSTGRES_TYPE);

        assumeTrue(label != null, "No unique labels in physical databases");

        helperV3.createDatabase(
                helperV3.getClusterDbaAuthorization(),
                "dbaas_auto_test_1",
                201, POSTGRES_TYPE,
                null,
                TEST_NAMESPACE,
                false,
                null
        );

        try (var createOnMicroserviceRuleResponse = createOnMicroserviceRule(
                Collections.singletonList(TEST_MICROSERVICE_NAME),
                POSTGRES_TYPE,
                Collections.singletonList(new RuleOnMicroservice(label.getKey() + "=" + label.getValue()))
        )) {
            assertEquals(201, createOnMicroserviceRuleResponse.code());

            var request = helperV3.createRequest(
                    String.format("/api/v3/dbaas/%s/physical_databases/rules/onMicroservices", TEST_NAMESPACE),
                    helperV3.getClusterDbaAuthorization(),
                    null,
                    "GET"
            );
            try (var response = helperV3.executeRequest(request, 200)) {
                var responseBodyStr = response.body().string();
                var onMicroserviceRules = helperV3.objectMapper.readValue(
                        responseBodyStr, new TypeReference<List<PerMicroserviceRuleDTO>>() {
                        }
                );

                assertEquals(1, onMicroserviceRules.size());
            }
        }
    }

    @Test
    public void testRuleOnMicroserviceValidationSuccessful() throws IOException {
        Map.Entry<String, String> label = balancingRulesHelperV3.getUniqLabelsByDbType(POSTGRES_TYPE);
        assumeTrue(label != null, "No unique labels in physical databases");
        Map<String, PhysicalDatabaseRegistrationResponseDTOV3> registeredPhysDb = helperV3.getRegisteredPhysicalDatabases(ALL, helperV3.getClusterDbaAuthorization(), 200).getIdentified();
        Map<String, String> globalDBs = new HashMap<>();
        String identityOfDB = "";
        for (Map.Entry<String, PhysicalDatabaseRegistrationResponseDTOV3> physDB : registeredPhysDb.entrySet()) {
            if (physDB.getValue().isGlobal()) {
                globalDBs.put(physDB.getValue().getType(), physDB.getKey());
            }
            if (isLabelBelongsToCurrentPhysDB(label, physDB)) {
                identityOfDB = physDB.getKey();
            }
        }
        String expectedLabel = label.getKey() + "=" + label.getValue();
        OnMicroserviceRuleRequest ruleRequestBody = new OnMicroserviceRuleRequest(POSTGRES_TYPE, Collections.singletonList(new RuleOnMicroservice(expectedLabel)), Collections.singletonList(TEST_MICROSERVICE_NAME));
        Request request = helperV3.createRequest(String.format("/api/v3/dbaas/%s/physical_databases/rules/onMicroservices/validation", TEST_NAMESPACE), helperV3.getClusterDbaAuthorization(), Collections.singletonList(ruleRequestBody), "PUT");
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
            String body = response.body().string();
            ValidateRulesResponseDto responseDto = new Gson().fromJson(body, new TypeToken<ValidateRulesResponseDto>() {
            }.getType());
            assertNotNull(responseDto.getMapLabelToPhysicalDb().get(expectedLabel));
            assertEquals(identityOfDB, responseDto.getMapLabelToPhysicalDb().get(expectedLabel));
            assertEquals(globalDBs, responseDto.getDefaultPhysicalDatabases());
        }
    }

    @Test
    public void testRuleOnMicroserviceValidationUnexistingLabel() throws IOException {
        String randomLabel = "TestLabel" + label_ID + "_balancing_rule=" + label_ID + "TestLabel";
        OnMicroserviceRuleRequest ruleRequestBody = new OnMicroserviceRuleRequest(POSTGRES_TYPE, Collections.singletonList(new RuleOnMicroservice(randomLabel)), Collections.singletonList(TEST_MICROSERVICE_NAME));
        Request request = helperV3.createRequest(String.format("/api/v3/dbaas/%s/physical_databases/rules/onMicroservices/validation", TEST_NAMESPACE), helperV3.getClusterDbaAuthorization(), Collections.singletonList(ruleRequestBody), "PUT");
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(400, response.code());
            assertEquals("Physical database with label: " + randomLabel + " not registered ", response.body().string());
        }
    }

    @Test
    public void testRuleOnMicroserviceValidationIncorrectLabelFormat() throws IOException {
        String randomLabel = "TestLabel" + label_ID + "_balancing_rule:" + label_ID + "TestLabel";
        OnMicroserviceRuleRequest ruleRequestBody = new OnMicroserviceRuleRequest(POSTGRES_TYPE, Collections.singletonList(new RuleOnMicroservice(randomLabel)), Collections.singletonList(TEST_MICROSERVICE_NAME));
        Request request = helperV3.createRequest(String.format("/api/v3/dbaas/%s/physical_databases/rules/onMicroservices/validation", TEST_NAMESPACE), helperV3.getClusterDbaAuthorization(), Collections.singletonList(ruleRequestBody), "PUT");
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(400, response.code());
            assertEquals("Labels=" + randomLabel + " from request incorrect", response.body().string());
        }
    }

    @Test
    void testAddPermanentRuleOnDatabase() throws IOException {
        Map<String, PhysicalDatabaseRegistrationResponseDTOV3> databases = helperV3.getRegisteredPhysicalDatabases(POSTGRES_TYPE, helperV3.getClusterDbaAuthorization(), 200).getIdentified();
        List<PermanentPerNamespaceRuleDTO> listWithRules = new ArrayList<>();
        Set<String> registeredDbTypes = new HashSet<>();
        for (Map.Entry<String, PhysicalDatabaseRegistrationResponseDTOV3> db : databases.entrySet()) {
            String dbType = db.getValue().getType();
            if (!registeredDbTypes.contains(dbType)) {
                listWithRules.add(new PermanentPerNamespaceRuleDTO(dbType, db.getKey(), List.of(TEST_NAMESPACE)));
                registeredDbTypes.add(dbType);
            }
        }
        Request request = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), listWithRules, "PUT");
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null);

        cleanUpPermanentRules(TEST_NAMESPACE);
    }

    @Test
    void testAddPermanentRulesConflictInRequest() throws IOException {
        List<PermanentPerNamespaceRuleDTO> listWithRules = new ArrayList<>();
        listWithRules.add(new PermanentPerNamespaceRuleDTO(POSTGRES_TYPE, TEST_PHYS_DB_ID, List.of(TEST_NAMESPACE)));
        listWithRules.add(new PermanentPerNamespaceRuleDTO(POSTGRES_TYPE, TEST_PHYS_DB_ID + "_new", List.of(TEST_NAMESPACE)));

        Request request = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), listWithRules, "PUT");
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(400, response.code());
        }
    }

    @Test
    void testAddRulesForOneNamespace() throws IOException {
        List<PermanentPerNamespaceRuleDTO> listWithRules = new ArrayList<>();
        listWithRules.add(new PermanentPerNamespaceRuleDTO(POSTGRES_TYPE, TEST_PHYS_DB_ID, List.of(TEST_NAMESPACE)));

        // send first request with rule {dbType: postgresql, physDbId: test-db-id, namespace: test-namespace}
        Request request = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), listWithRules, "PUT");
        Response response = okHttpClient.newCall(request).execute();
        assertEquals(200, response.code());

        List<PermanentPerNamespaceRuleDTO> secondListWithRules = new ArrayList<>();
        String updatedPhysDbId = TEST_PHYS_DB_ID + "_2";
        PermanentPerNamespaceRuleDTO expectedRule = new PermanentPerNamespaceRuleDTO(POSTGRES_TYPE, updatedPhysDbId, List.of(TEST_NAMESPACE));
        secondListWithRules.add(expectedRule);

        // send second request with rule {dbType: postgresql, physDbId: test-db-id_2, namespace: test-namespace}
        Request secondRequest = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), secondListWithRules, "PUT");
        Response secondResponse = okHttpClient.newCall(secondRequest).execute();
        assertEquals(200, secondResponse.code());

        Request getRulesRequest = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), null, "GET");
        Response getRulesResponse = okHttpClient.newCall(getRulesRequest).execute();
        assertEquals(200, getRulesResponse.code());
        String body = getRulesResponse.body().string();
        Type responseType = new TypeToken<ArrayList<PermanentPerNamespaceRuleDTO>>() {
        }.getType();
        List<PermanentPerNamespaceRuleDTO> responseDto = new Gson().fromJson(body, responseType);
        assertTrue(responseDto.contains(expectedRule));
        PermanentPerNamespaceRuleDTO actualRule = responseDto.stream().filter(rule -> rule.getPhysicalDatabaseId().equals(expectedRule.getPhysicalDatabaseId())).findFirst().get();
        assertEquals(1, actualRule.getNamespaces().size());
        assertEquals(TEST_NAMESPACE, actualRule.getNamespaces().getFirst());
        assertEquals(updatedPhysDbId, actualRule.getPhysicalDatabaseId());

        cleanUpPermanentRules(TEST_NAMESPACE);
    }

    @Test
    @Tag("Smoke")
    void testGetRulesForNamespace() throws IOException {
        List<PermanentPerNamespaceRuleDTO> listWithRules = new ArrayList<>();
        listWithRules.add(new PermanentPerNamespaceRuleDTO(POSTGRES_TYPE, TEST_PHYS_DB_ID, List.of(TEST_NAMESPACE)));
        listWithRules.add(new PermanentPerNamespaceRuleDTO(POSTGRES_TYPE, TEST_PHYS_DB_ID + "_2", List.of(TEST_NAMESPACE + "_2")));

        Request request = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), listWithRules, "PUT");
        Response response = okHttpClient.newCall(request).execute();
        assertEquals(200, response.code());

        Request getRulesRequest = helperV3.createRequest(String.format("/api/v3/dbaas/balancing/rules/permanent?namespace=%s", TEST_NAMESPACE), helperV3.getClusterDbaAuthorization(), null, "GET");
        Response getRulesResponse = okHttpClient.newCall(getRulesRequest).execute();
        assertEquals(200, getRulesResponse.code());
        String body = getRulesResponse.body().string();
        Type responseType = new TypeToken<ArrayList<PermanentPerNamespaceRuleDTO>>() {
        }.getType();
        List<PermanentPerNamespaceRuleDTO> responseDto = new Gson().fromJson(body, responseType);
        assertEquals(1, responseDto.size());
        PermanentPerNamespaceRuleDTO actualRule = responseDto.getFirst();
        assertEquals(1, actualRule.getNamespaces().size());
        assertEquals(TEST_NAMESPACE, actualRule.getNamespaces().getFirst());
        assertEquals(TEST_PHYS_DB_ID, actualRule.getPhysicalDatabaseId());

        cleanUpPermanentRules(TEST_NAMESPACE);
        cleanUpPermanentRules(TEST_NAMESPACE + "_2");
    }

    @Test
    void testGetAllRules() throws IOException {
        List<PermanentPerNamespaceRuleDTO> listWithRules = new ArrayList<>();
        PermanentPerNamespaceRuleDTO firstRule = new PermanentPerNamespaceRuleDTO(POSTGRES_TYPE, TEST_PHYS_DB_ID, List.of(TEST_NAMESPACE));
        listWithRules.add(firstRule);
        PermanentPerNamespaceRuleDTO secondRule = new PermanentPerNamespaceRuleDTO(POSTGRES_TYPE, TEST_PHYS_DB_ID + "_2", List.of(TEST_NAMESPACE + "_2"));
        listWithRules.add(secondRule);

        Request request = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), listWithRules, "PUT");
        Response response = okHttpClient.newCall(request).execute();
        assertEquals(200, response.code());

        Request getRulesRequest = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), null, "GET");
        Response getRulesResponse = okHttpClient.newCall(getRulesRequest).execute();
        assertEquals(200, getRulesResponse.code());
        String body = getRulesResponse.body().string();
        Type responseType = new TypeToken<ArrayList<PermanentPerNamespaceRuleDTO>>() {
        }.getType();
        List<PermanentPerNamespaceRuleDTO> responseDto = new Gson().fromJson(body, responseType);
        assertTrue(responseDto.size() >= 2);
        assertTrue(responseDto.containsAll(listWithRules));

        cleanUpPermanentRules(TEST_NAMESPACE);
        cleanUpPermanentRules(TEST_NAMESPACE + "_2");
    }

    @Test
    void testDeleteAllPermanentRulesForNamespace() throws IOException {
        List<PermanentPerNamespaceRuleDTO> listWithRules = new ArrayList<>();
        PermanentPerNamespaceRuleDTO firstRule = new PermanentPerNamespaceRuleDTO(POSTGRES_TYPE, TEST_PHYS_DB_ID, List.of(TEST_NAMESPACE));
        listWithRules.add(firstRule);
        PermanentPerNamespaceRuleDTO secondRule = new PermanentPerNamespaceRuleDTO(MONGODB_TYPE, TEST_PHYS_DB_ID, List.of(TEST_NAMESPACE));
        listWithRules.add(secondRule);

        Request request = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), listWithRules, "PUT");
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
        }

        List<PermanentPerNamespaceRuleDeleteDTO> deleteRuleRequest = new ArrayList<>();
        deleteRuleRequest.add(new PermanentPerNamespaceRuleDeleteDTO("", List.of(TEST_NAMESPACE)));
        Request cleanupRequest = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), deleteRuleRequest, "DELETE");
        try (Response cleanupResponse = okHttpClient.newCall(cleanupRequest).execute()) {
            assertEquals(200, cleanupResponse.code());
        }

        Request getRulesRequest = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), null, "GET");
        try (Response getRulesResponse = okHttpClient.newCall(getRulesRequest).execute()) {
            assertEquals(200, getRulesResponse.code());
            String body = getRulesResponse.body().string();
            Type responseType = new TypeToken<ArrayList<PermanentPerNamespaceRuleDTO>>() {
            }.getType();
            List<PermanentPerNamespaceRuleDTO> responseDto = new Gson().fromJson(body, responseType);
            assertFalse(responseDto.contains(firstRule));
            assertFalse(responseDto.contains(secondRule));
        }
    }

    @Test
    void testDeletePermanentRulesForNamespaceAndDbType() throws IOException {
        List<PermanentPerNamespaceRuleDTO> listWithRules = new ArrayList<>();
        PermanentPerNamespaceRuleDTO pgRule = new PermanentPerNamespaceRuleDTO(POSTGRES_TYPE, TEST_PHYS_DB_ID, List.of(TEST_NAMESPACE));
        listWithRules.add(pgRule);
        PermanentPerNamespaceRuleDTO mgRule = new PermanentPerNamespaceRuleDTO(MONGODB_TYPE, TEST_PHYS_DB_ID, List.of(TEST_NAMESPACE));
        listWithRules.add(mgRule);

        Request request = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), listWithRules, "PUT");
        Response response = okHttpClient.newCall(request).execute();
        assertEquals(200, response.code());

        List<PermanentPerNamespaceRuleDeleteDTO> deleteRuleRequest = new ArrayList<>();
        deleteRuleRequest.add(new PermanentPerNamespaceRuleDeleteDTO(POSTGRES_TYPE, List.of(TEST_NAMESPACE)));
        Request cleanupRequest = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), deleteRuleRequest, "DELETE");
        Response cleanupResponse = okHttpClient.newCall(cleanupRequest).execute();
        assertEquals(200, cleanupResponse.code());

        Request getRulesRequest = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), null, "GET");
        Response getRulesResponse = okHttpClient.newCall(getRulesRequest).execute();
        assertEquals(200, getRulesResponse.code());
        String body = getRulesResponse.body().string();
        Type responseType = new TypeToken<ArrayList<PermanentPerNamespaceRuleDTO>>() {
        }.getType();
        List<PermanentPerNamespaceRuleDTO> responseDto = new Gson().fromJson(body, responseType);
        assertTrue(responseDto.contains(mgRule));
        assertFalse(responseDto.contains(pgRule));

        cleanUpPermanentRules(TEST_NAMESPACE);
    }

    @Test
    void testDebugBalancingRulesUseRequestRule() {
        Map.Entry<String, String> label = balancingRulesHelperV3.getUniqLabelsByDbType(POSTGRES_TYPE);
        assumeTrue(label != null, "No unique labels in physical databases");

        DebugRulesRequest debugRulesRequest = new DebugRulesRequest();
        debugRulesRequest.setRules(Collections.singletonList(new OnMicroserviceRuleRequest(POSTGRES_TYPE, Collections.singletonList(new RuleOnMicroservice(label.getKey() + "=" + label.getValue())), Collections.singletonList(TEST_MICROSERVICE_NAME))));
        debugRulesRequest.setMicroservices(Collections.singletonList(TEST_MICROSERVICE_NAME));

        Request request = helperV3.createRequest(String.format("api/v3/dbaas/%s/physical_databases/rules/debug", TEST_NAMESPACE), helperV3.getClusterDbaAuthorization(), debugRulesRequest, "POST");
        Map result = helperV3.executeRequest(request, Map.class, 200);
        Map<String, Map<String, DebugRulesDbTypeData>> debugResponseData = helperV3.objectMapper.convertValue(result, new TypeReference<>() {
        });
        Map<String, DebugRulesDbTypeData> testMicroserviceData = debugResponseData.get(TEST_MICROSERVICE_NAME);
        assertNotNull(testMicroserviceData);
        DebugRulesDbTypeData pgDebugData = testMicroserviceData.get(POSTGRES_TYPE);
        assertNotNull(pgDebugData);
        assertEquals(MICROSERVICE_RULE_INFO, pgDebugData.getAppliedRuleInfo());
        assertEquals(label.getValue(), pgDebugData.getLabels().get(label.getKey()));
    }

    @Test
    void testDebugBalancingRulesUseStoredRule() throws IOException {
        Map.Entry<String, String> label = balancingRulesHelperV3.getUniqLabelsByDbType(POSTGRES_TYPE);
        assumeTrue(label != null, "No unique labels in physical databases");
        try (Response response = createOnMicroserviceRule(
                List.of(TEST_MICROSERVICE_NAME), POSTGRES_TYPE, List.of(new RuleOnMicroservice(label.getKey() + "=" + label.getValue())))) {
            assertEquals(201, response.code());
        }

        // We must have existing database to ensure that all rules will be cleaned up in after test namespace cleanup
        helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null);
        DebugRulesRequest debugRulesRequest = new DebugRulesRequest();
        debugRulesRequest.setRules(Collections.emptyList());
        debugRulesRequest.setMicroservices(Collections.singletonList(TEST_MICROSERVICE_NAME));
        Request request = helperV3.createRequest(String.format("api/v3/dbaas/%s/physical_databases/rules/debug", TEST_NAMESPACE), helperV3.getClusterDbaAuthorization(), debugRulesRequest, "POST");
        Map result = helperV3.executeRequest(request, Map.class, 200);
        Map<String, Map<String, DebugRulesDbTypeData>> debugResponseData = helperV3.objectMapper.convertValue(result, new TypeReference<>() {
        });
        Map<String, DebugRulesDbTypeData> testMicroserviceData = debugResponseData.get(TEST_MICROSERVICE_NAME);
        assertNotNull(testMicroserviceData);
        DebugRulesDbTypeData pgDebugData = testMicroserviceData.get(POSTGRES_TYPE);
        assertNotNull(pgDebugData);
        assertEquals(MICROSERVICE_RULE_INFO, pgDebugData.getAppliedRuleInfo());
        assertEquals(label.getValue(), pgDebugData.getLabels().get(label.getKey()));
    }

    @Test
    void testDebugBalancingRulesUseNamespaceRule() throws IOException {
        Map<String, PhysicalDatabaseRegistrationResponseDTOV3> databases = helperV3.getRegisteredPhysicalDatabases(POSTGRES_TYPE, helperV3.getClusterDbaAuthorization(), 200).getIdentified();
        Optional<String> physDbIdOptional = databases.keySet().stream().findFirst();
        assumeTrue(physDbIdOptional.isPresent());
        String physDbId = physDbIdOptional.get();
        RuleRegistrationRequest ruleRegistrationRequest = new RuleRegistrationRequest(0L, POSTGRES_TYPE, new RuleBody(perNamespace, Map.of(PER_NAMESPACE, Map.of(PHYSICAL_DATABASE_IDENTIFIER, physDbId))));

        Request request = helperV3.createRequest(String.format("api/v3/dbaas/%s/physical_databases/balancing/rules/%s", TEST_NAMESPACE, "test-rule"), helperV3.getClusterDbaAuthorization(), ruleRegistrationRequest, "PUT");
        helperV3.executeRequest(request, null, 201);
        // We must have existing database to ensure that all rules will be cleaned up in after test namespace cleanup
        helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null);

        DebugRulesRequest debugRulesRequest = new DebugRulesRequest();
        debugRulesRequest.setRules(Collections.emptyList());
        debugRulesRequest.setMicroservices(Collections.singletonList(TEST_MICROSERVICE_NAME));
        request = helperV3.createRequest(String.format("api/v3/dbaas/%s/physical_databases/rules/debug", TEST_NAMESPACE), helperV3.getClusterDbaAuthorization(), debugRulesRequest, "POST");
        Map result = helperV3.executeRequest(request, Map.class, 200);
        Map<String, Map<String, DebugRulesDbTypeData>> debugResponseData = helperV3.objectMapper.convertValue(result, new TypeReference<>() {
        });
        Map<String, DebugRulesDbTypeData> testMicroserviceData = debugResponseData.get(TEST_MICROSERVICE_NAME);
        assertNotNull(testMicroserviceData);
        DebugRulesDbTypeData pgDebugData = testMicroserviceData.get(POSTGRES_TYPE);
        assertNotNull(pgDebugData);
        assertEquals(NAMESPACE_RULE_INFO, pgDebugData.getAppliedRuleInfo());
        assertEquals(physDbId, pgDebugData.getPhysicalDbIdentifier());
    }

    @Test
    void testDebugBalancingRulesUseDefaultRule() {
        boolean isDefaultDatabaseDisabled = helperV3.isDefaultDatabaseDisabled(pod);

        DebugRulesRequest debugRulesRequest = new DebugRulesRequest();
        debugRulesRequest.setRules(Collections.emptyList());
        debugRulesRequest.setMicroservices(Collections.singletonList(TEST_MICROSERVICE_NAME));
        Request request = helperV3.createRequest(String.format("api/v3/dbaas/%s/physical_databases/rules/debug", TEST_NAMESPACE), helperV3.getClusterDbaAuthorization(), debugRulesRequest, "POST");
        Map result = helperV3.executeRequest(request, Map.class, 200);
        Map<String, Map<String, DebugRulesDbTypeData>> debugResponseData = helperV3.objectMapper.convertValue(result, new TypeReference<>() {
        });
        Map<String, DebugRulesDbTypeData> testMicroserviceData = debugResponseData.get(TEST_MICROSERVICE_NAME);
        assertNotNull(testMicroserviceData);
        DebugRulesDbTypeData pgDebugData = testMicroserviceData.get(POSTGRES_TYPE);
        assertNotNull(pgDebugData);
        if (isDefaultDatabaseDisabled) {
            assertEquals(NO_SUITABLE_DATABASE_RULE_INFO, pgDebugData.getAppliedRuleInfo());
        } else {
            assertEquals(DEFAULT_DATABASE_RULE_INFO, pgDebugData.getAppliedRuleInfo());
        }
    }

    public Response createOnMicroserviceRule(List<String> microservices, String dbType, List<RuleOnMicroservice> rules) throws IOException {
        OnMicroserviceRuleRequest ruleRequestBody = new OnMicroserviceRuleRequest(dbType, rules, microservices);
        log.info("Create on microservice rule={}", ruleRequestBody);
        Request request = helperV3.createRequest(String.format("api/v3/dbaas/%s/physical_databases/rules/onMicroservices", TEST_NAMESPACE), helperV3.getClusterDbaAuthorization(), Collections.singletonList(ruleRequestBody), "PUT");
        return okHttpClient.newCall(request).execute();
    }

    public static void createPerMicroserviceRule(String namespace, String microserviceName, String microserviceRuleLabel, int expectedCode) throws IOException {
        OnMicroserviceRuleRequest ruleRequestBody = new OnMicroserviceRuleRequest(POSTGRES_TYPE, Collections.singletonList(new RuleOnMicroservice(microserviceRuleLabel)), Collections.singletonList(microserviceName));
        log.info("Create on microservice rule={}", ruleRequestBody);
        try (Response response = helperV3.sendRequest(String.format("api/v3/dbaas/%s/physical_databases/rules/onMicroservices", namespace), Collections.singletonList(ruleRequestBody), "PUT")) {
            assertEquals(expectedCode, response.code());
        }
    }

    public static void createPerNamespaceRule(String namespace, int expectedCode) throws IOException {
        Map<String, PhysicalDatabaseRegistrationResponseDTOV3> databases = helperV3.getRegisteredPhysicalDatabases(POSTGRES_TYPE, helperV3.getClusterDbaAuthorization(), 200).getIdentified();
        Optional<String> physDbIdOptional = databases.keySet().stream().findFirst();
        assumeTrue(physDbIdOptional.isPresent());
        String physDbId = physDbIdOptional.get();
        RuleRegistrationRequest ruleRegistrationRequest = new RuleRegistrationRequest(0L, POSTGRES_TYPE, new RuleBody(perNamespace, Map.of(PER_NAMESPACE, Map.of(PHYSICAL_DATABASE_IDENTIFIER, physDbId))));
        try (Response response = helperV3.sendRequest(String.format("api/v3/dbaas/%s/physical_databases/balancing/rules/%s", namespace, "test-rule"), ruleRegistrationRequest, "PUT")) {
            assertEquals(expectedCode, response.code());
        }
    }

    private boolean isLabelBelongsToCurrentPhysDB(Map.Entry<String, String> label, Map.Entry<String, PhysicalDatabaseRegistrationResponseDTOV3> physDB) {
        if (physDB.getValue().getLabels() == null) {
            return false;
        }

        return physDB.getValue().getLabels().containsKey(label.getKey())
                && physDB.getValue().getLabels().get(label.getKey()).equals(label.getValue());
    }

    private void cleanUpPermanentRules(String namespace) throws IOException {
        List<PermanentPerNamespaceRuleDeleteDTO> deleteRuleRequest = new ArrayList<>();
        deleteRuleRequest.add(new PermanentPerNamespaceRuleDeleteDTO("", List.of(namespace)));
        Request cleanupRequest = helperV3.createRequest("/api/v3/dbaas/balancing/rules/permanent", helperV3.getClusterDbaAuthorization(), deleteRuleRequest, "DELETE");
        try (Response cleanupResponse = okHttpClient.newCall(cleanupRequest).execute()) {
            assertEquals(200, cleanupResponse.code());
        }
    }
}
