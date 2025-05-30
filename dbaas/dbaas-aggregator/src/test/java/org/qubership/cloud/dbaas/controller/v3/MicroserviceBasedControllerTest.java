package org.qubership.cloud.dbaas.controller.v3;


import org.qubership.cloud.dbaas.dto.role.ServiceRole;
import org.qubership.cloud.dbaas.entity.pg.role.DatabaseRole;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.service.DatabaseRolesService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.qubership.cloud.dbaas.DbaasApiPath.ACCESS_GRANTS_SUBPATH_V3;
import static org.qubership.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(MicroserviceBasedController.class)
@Slf4j
class MicroserviceBasedControllerTest {

    private static final String TEST_NAMESPACE = "test-namespace";
    private static final String MICROSERVICE_NAME = "test-service";

    @InjectMock
    DatabaseRolesService databaseRolesService;

    @Test
    void getAccessGrantsWhenNotFoundTest() {
        Mockito.when(databaseRolesService
                .getAccessGrants(TEST_NAMESPACE, MICROSERVICE_NAME)).thenReturn(Optional.empty());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().get(ACCESS_GRANTS_SUBPATH_V3, MICROSERVICE_NAME)
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    void getAccessGrantsTest() {
        DatabaseRole databaseRole = new DatabaseRole();
        ServiceRole serviceRole = new ServiceRole();
        serviceRole.setName("service-with-grants");
        serviceRole.setRoles(List.of("admin"));
        List<ServiceRole> serviceRoles = List.of(serviceRole);
        databaseRole.setServices(serviceRoles);
        databaseRole.setDisableGlobalPermissions(false);
        Mockito.when(databaseRolesService
                .getAccessGrants(TEST_NAMESPACE, MICROSERVICE_NAME)).thenReturn(Optional.of(databaseRole));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().get(ACCESS_GRANTS_SUBPATH_V3, MICROSERVICE_NAME)
                .then()
                .statusCode(OK.getStatusCode())
                .body("services[0].name", is("service-with-grants"))
                .body("services[0].roles[0]", is("admin"));
    }
}
