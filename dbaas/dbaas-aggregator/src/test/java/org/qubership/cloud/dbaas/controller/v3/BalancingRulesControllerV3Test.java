package org.qubership.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.OnMicroserviceRuleRequest;
import org.qubership.cloud.dbaas.dto.RuleOnMicroservice;
import org.qubership.cloud.dbaas.dto.RuleRegistrationRequest;
import org.qubership.cloud.dbaas.exceptions.BalancingRuleConflictException;
import org.qubership.cloud.dbaas.exceptions.OnMicroserviceBalancingRuleException;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.service.BalancingRulesService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.MockitoConfig;
import jakarta.ws.rs.core.MediaType;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.qubership.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.CONFLICT;
import static jakarta.ws.rs.core.Response.Status.CREATED;
import static jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(BalancingRulesControllerV3.class)
class BalancingRulesControllerV3Test {

    private final String TEST_NAMESPACE = "test-namespace";
    private final String TEST_RULE_NAME = "test-rule-name";

    private final String TEST_TYPE = "test-type";

    @InjectMock
    @MockitoConfig(convertScopes = true)
    BalancingRulesService balancingRulesService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testAddRule() throws JsonProcessingException {
        final RuleRegistrationRequest ruleRegistrationRequest = getRuleRegistrationRequestSample();
        when(balancingRulesService.saveOnNamespace(TEST_RULE_NAME, TEST_NAMESPACE, ruleRegistrationRequest)).thenReturn(true);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(ruleRegistrationRequest))
                .when().put("/balancing/rules/{ruleName}", TEST_RULE_NAME)
                .then()
                .statusCode(OK.getStatusCode());

        when(balancingRulesService.saveOnNamespace(TEST_RULE_NAME, TEST_NAMESPACE, ruleRegistrationRequest)).thenReturn(false);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(ruleRegistrationRequest))
                .when().put("/balancing/rules/{ruleName}", TEST_RULE_NAME)
                .then()
                .statusCode(CREATED.getStatusCode());

        when(balancingRulesService.saveOnNamespace(TEST_RULE_NAME, TEST_NAMESPACE, ruleRegistrationRequest))
                .thenThrow(new BalancingRuleConflictException(TEST_TYPE, 0L, TEST_RULE_NAME));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(ruleRegistrationRequest))
                .when().put("/balancing/rules/{ruleName}", TEST_RULE_NAME)
                .then()
                .statusCode(CONFLICT.getStatusCode());
    }

    @Test
    void testOnMicroserviceRule() throws JsonProcessingException {
        List<OnMicroserviceRuleRequest> request = createOnMicroserviceRuleTestRequest();
        when(balancingRulesService.addRuleOnMicroservice(request, TEST_NAMESPACE)).thenReturn(Collections.emptyList());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().put("/rules/onMicroservices")
                .then()
                .statusCode(CREATED.getStatusCode());

        when(balancingRulesService.addRuleOnMicroservice(request, TEST_NAMESPACE)).thenThrow(new OnMicroserviceBalancingRuleException("error"));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().put("/rules/onMicroservices")
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode());
    }

    @Test
    void testGetOnMicroservicePhysicalDatabaseBalancingRules() {
        doReturn(Collections.emptyList()).when(balancingRulesService)
            .getOnMicroserviceBalancingRules(TEST_NAMESPACE);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
            .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
            .contentType(MediaType.APPLICATION_JSON)
            .when().get("/rules/onMicroservices")
            .then()
            .statusCode(OK.getStatusCode())
            .contentType(MediaType.APPLICATION_JSON)
            .body("$", Matchers.isA(List.class));
    }

    private RuleRegistrationRequest getRuleRegistrationRequestSample() {
        final RuleRegistrationRequest ruleRegistrationRequest = new RuleRegistrationRequest();
        ruleRegistrationRequest.setType(TEST_TYPE);
        return ruleRegistrationRequest;
    }

    private List<OnMicroserviceRuleRequest> createOnMicroserviceRuleTestRequest() {
        OnMicroserviceRuleRequest request = new OnMicroserviceRuleRequest();
        request.setMicroservices(List.of("test-service"));
        request.setType(TEST_TYPE);
        RuleOnMicroservice rule = new RuleOnMicroservice();
        rule.setLabel("core_balancing_rule=core");
        request.setRules(List.of(rule));
        return List.of(request);
    }
}
