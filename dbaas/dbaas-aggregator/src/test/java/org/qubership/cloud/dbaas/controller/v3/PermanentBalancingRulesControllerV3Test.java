package org.qubership.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.v3.PermanentPerNamespaceRuleDTO;
import org.qubership.cloud.dbaas.dto.v3.PermanentPerNamespaceRuleDeleteDTO;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.service.BalancingRulesService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.MockitoConfig;
import jakarta.ws.rs.core.MediaType;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.qubership.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(PermanentBalancingRulesControllerV3.class)
public class PermanentBalancingRulesControllerV3Test {
    private final String TEST_NAMESPACE = "test-namespace";

    @InjectMock
    @MockitoConfig(convertScopes = true)
    BalancingRulesService balancingRulesService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testAddPermanentRule() throws JsonProcessingException {
        List<PermanentPerNamespaceRuleDTO> request = createTestPermanentRuleRequest();
        when(balancingRulesService.savePermanentOnNamespace(request)).thenReturn(request);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().put()
                .then()
                .statusCode(OK.getStatusCode());
    }

    @Test
    void testAddPermanentRuleWithConflictRequest() throws JsonProcessingException {
        List<PermanentPerNamespaceRuleDTO> request = new ArrayList<>();
        request.add(new PermanentPerNamespaceRuleDTO("postgresql", "pg-db-1", Collections.singleton("test-namespace-1")));
        request.add(new PermanentPerNamespaceRuleDTO("postgresql", "pg-db-2", Collections.singleton("test-namespace-1")));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().put()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());
    }

    @Test
    void testGetPermanentRulesWithoutNamespace() {
        List<PermanentPerNamespaceRuleDTO> request = createTestPermanentRuleRequest();
        when(balancingRulesService.getPermanentOnNamespaceRule()).thenReturn(request);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get()
                .then()
                .statusCode(OK.getStatusCode());
    }

    @Test
    void testGetPermanentRulesWithNamespace() {
        List<PermanentPerNamespaceRuleDTO> request = createTestPermanentRuleRequest();
        when(balancingRulesService.getPermanentOnNamespaceRule(TEST_NAMESPACE)).thenReturn(request);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .queryParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().get()
                .then()
                .statusCode(OK.getStatusCode());
    }

    @Test
    void testDeletePermanentRules() throws JsonProcessingException {
        List<PermanentPerNamespaceRuleDeleteDTO> request = new ArrayList<>();
        request.add(new PermanentPerNamespaceRuleDeleteDTO("postgresql", Collections.singleton("test-namespace")));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().delete()
                .then()
                .statusCode(OK.getStatusCode());
        verify(balancingRulesService).deletePermanentRules(request);
    }

    private List<PermanentPerNamespaceRuleDTO> createTestPermanentRuleRequest() {
        List<PermanentPerNamespaceRuleDTO> request = new ArrayList<>();
        request.add(new PermanentPerNamespaceRuleDTO("postgresql", "pg-db-1", Collections.singleton("test-namespace-1")));
        request.add(new PermanentPerNamespaceRuleDTO("postgresql", "pg-db-2", Collections.singleton("test-namespace-2")));
        request.add(new PermanentPerNamespaceRuleDTO("mongodb", "pg-db-2", Collections.singleton("test-namespace-2")));
        return request;
    }
}
