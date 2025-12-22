package com.netcracker.it.dbaas.helpers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.it.dbaas.entity.*;
import com.netcracker.it.dbaas.entity.config.DatabaseDeclaration;
import com.netcracker.it.dbaas.entity.config.DeclarativePayload;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.util.*;

import static com.netcracker.it.dbaas.entity.RuleBody.RuleType.perNamespace;
import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
@AllArgsConstructor
public class DbaasDebugHelperV3 {

    private static final String TEST_NAMESPACE_CANDIDATE = "candidate-test-namespace";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final DbaasHelperV3 helperV3;
    private final DeclarativeConfigHelper declarativeHelper;
    private final BGHelper bgHelper;
    private final BalancingRulesHelperV3 balancingRulesHelperV3;

    public void generateTestData() throws IOException, InterruptedException {
        generateLogicalDatabase();
        generateDatabaseDeclarativeConfig();
        generateBlueGreenDomain();
        generateNamespaceRule();
        generateMicroserviceRule();
        generatePermanentRule();
    }

    public void deleteTestData() throws IOException {
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), DbaasHelperV3.TEST_NAMESPACE);
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE_CANDIDATE);

        bgHelper.destroyDomain(new BgNamespaceRequest(DbaasHelperV3.TEST_NAMESPACE, TEST_NAMESPACE_CANDIDATE)).close();
        balancingRulesHelperV3.deletePermanentRules(DbaasHelperV3.TEST_NAMESPACE);
    }

    protected void generateLogicalDatabase() throws IOException {
        helperV3.createDatabase(
                helperV3.getClusterDbaAuthorization(),
                "dbaas_auto_test",
                201,
                "postgresql",
                null,
                DbaasHelperV3.TEST_NAMESPACE,
                false,
                null
        );
    }

    protected void generateDatabaseDeclarativeConfig() throws IOException {
        var classifierBuilder = new ClassifierBuilder()
                .test("dbaas_auto_test")
                .ns(DbaasHelperV3.TEST_NAMESPACE)
                .ms(TEST_DECLARATIVE_MICROSERVICE_NAME)
                .build();

        var databaseCreateRequestV3 = DatabaseCreateRequestV3.builder(classifierBuilder, "postgresql")
                .namePrefix("dbaas_autotests")
                .originService(TEST_DECLARATIVE_MICROSERVICE_NAME)
                .build();

        helperV3.createDatabase(
                String.format(DATABASES_V3, DbaasHelperV3.TEST_NAMESPACE),
                databaseCreateRequestV3,
                201
        );

        List<DeclarativePayload> declarations = new ArrayList<>();
        declarations.add(new DatabaseDeclaration.DeclarativeDBConfigBuilder()
                .classifier(new ClassifierBuilder().customKeys("logicalDBName", "configs").ms(TEST_DECLARATIVE_MICROSERVICE_NAME))
                .initClone(new ClassifierBuilder().test("dbaas_auto_test").ms(TEST_DECLARATIVE_MICROSERVICE_NAME))
                .build().asPayload(TEST_NAMESPACE, TEST_DECLARATIVE_MICROSERVICE_NAME));
        declarativeHelper.applyDeclarativeConfigs(declarations);
    }

    protected void generateBlueGreenDomain() throws IOException {
        var classifierBuilder = new ClassifierBuilder()
                .test("toClone")
                .ns(DbaasHelperV3.TEST_NAMESPACE)
                .build();

        var databaseCreateRequestV3 = DatabaseCreateRequestV3.builder(classifierBuilder, "postgresql")
                .originService(TEST_MICROSERVICE_NAME)
                .build();

        helperV3.createDatabase(
                String.format(DATABASES_V3, DbaasHelperV3.TEST_NAMESPACE),
                databaseCreateRequestV3,
                201
        );

        try (Response initResponse = bgHelper.initDomain(DbaasHelperV3.TEST_NAMESPACE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }
    }

    protected void generateNamespaceRule() throws IOException {
        Map<String, PhysicalDatabaseRegistrationResponseDTOV3> databases =
                helperV3.getRegisteredPhysicalDatabases("postgresql", helperV3.getClusterDbaAuthorization(), 200)
                        .getIdentified();

        Optional<String> physDbIdOptional = databases.keySet().stream().findFirst();

        assumeTrue(physDbIdOptional.isPresent());

        var physDbId = physDbIdOptional.get();
        var ruleRegistrationRequest = new RuleRegistrationRequest(
                0L,
                "postgresql",
                new RuleBody(perNamespace, Map.of("perNamespace", Map.of("phydbid", physDbId)))
        );

        try (Response response = helperV3.sendRequest(
                String.format("api/v3/dbaas/%s/physical_databases/balancing/rules/%s", DbaasHelperV3.TEST_NAMESPACE, "test-rule"),
                ruleRegistrationRequest,
                "PUT"
        )) {
            assertEquals(201, response.code());
        }
    }

    protected void generateMicroserviceRule() throws IOException {
        Map.Entry<String, String> label = balancingRulesHelperV3.getUniqLabelsByDbType("postgresql");

        assumeTrue(label != null, "No unique labels in physical databases");

        OnMicroserviceRuleRequest ruleRequestBody = new OnMicroserviceRuleRequest(
                "postgresql",
                Collections.singletonList(new RuleOnMicroservice(label.getKey() + "=" + label.getValue())),
                Collections.singletonList(TEST_MICROSERVICE_NAME)
        );

        log.info("Create on microservice rule={}", ruleRequestBody);

        Request request = helperV3.createRequest(
                String.format("api/v3/dbaas/%s/physical_databases/rules/onMicroservices", DbaasHelperV3.TEST_NAMESPACE),
                helperV3.getClusterDbaAuthorization(),
                Collections.singletonList(ruleRequestBody),
                "PUT"
        );

        Response response = helperV3.executeRequest(request, 201);
        response.close();
    }

    protected void generatePermanentRule() throws IOException {
        Map<String, PhysicalDatabaseRegistrationResponseDTOV3> databases =
                helperV3.getRegisteredPhysicalDatabases("postgresql", helperV3.getClusterDbaAuthorization(), 200)
                        .getIdentified();

        List<PermanentPerNamespaceRuleDTO> listWithRules = new ArrayList<>();
        Set<String> registeredDbTypes = new HashSet<>();

        for (Map.Entry<String, PhysicalDatabaseRegistrationResponseDTOV3> db : databases.entrySet()) {
            String dbType = db.getValue().getType();

            if (!registeredDbTypes.contains(dbType)) {
                listWithRules.add(new PermanentPerNamespaceRuleDTO(dbType, db.getKey(), List.of(DbaasHelperV3.TEST_NAMESPACE)));
                registeredDbTypes.add(dbType);
            }
        }
        Request request = helperV3.createRequest(
                "/api/v3/dbaas/balancing/rules/permanent",
                helperV3.getClusterDbaAuthorization(),
                listWithRules,
                "PUT"
        );
        Response response = helperV3.executeRequest(request, 200);
        response.close();
    }
}
