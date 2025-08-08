package org.qubership.cloud.dbaas.controller.composite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.composite.CompositeStructureDto;
import org.qubership.cloud.dbaas.entity.pg.composite.CompositeStructure;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.service.composite.CompositeNamespaceService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.common.mapper.TypeRef;
import jakarta.ws.rs.core.MediaType;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(CompositeController.class)
class CompositeControllerTest {

    @InjectMock
    CompositeNamespaceService compositeService;

    @Test
    void testGetAllCompositeStructures_Success() {
        CompositeStructure expected = new CompositeStructure("ns-1", Set.of("ns-1", "ns-2"));
        when(compositeService.getAllCompositeStructures())
                .thenReturn(List.of(expected));

        List<CompositeStructureDto> allCompositeStructures = given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get()
                .then()
                .statusCode(OK.getStatusCode())
                .extract().as(new TypeRef<>() {
                });

        assertNotNull(allCompositeStructures);
        assertEquals(1, allCompositeStructures.size());
        assertEquals("ns-1", allCompositeStructures.get(0).getId());
        assertEquals(Set.of("ns-1", "ns-2"), allCompositeStructures.get(0).getNamespaces());
    }

    @Test
    void testGetAllCompositeStructures_EmptyList() {
        when(compositeService.getAllCompositeStructures()).thenReturn(Collections.emptyList());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get()
                .then()
                .statusCode(OK.getStatusCode())
                .body(is("[]"));

        verify(compositeService).getAllCompositeStructures();
        verifyNoMoreInteractions(compositeService);

    }

    @Test
    void testGetAllCompositeStructures_InternalServerError() {
        when(compositeService.getAllCompositeStructures()).thenThrow(new RuntimeException("Internal Server Error"));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get()
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .body("reason", is("Unexpected exception"))
                .body("message", is("Internal Server Error"));
    }

    @Test
    void testGetCompositeById_Success() {
        CompositeStructure expected = new CompositeStructure("test-id", Set.of("ns-1", "ns-2"));
        when(compositeService.getCompositeStructure("test-id")).thenReturn(Optional.of(expected));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get("/test-id")
                .then()
                .statusCode(OK.getStatusCode())
                .body("id", is("test-id"))
                .body("namespaces", hasSize(2))
                .body("namespaces", containsInAnyOrder("ns-2", "ns-1"));
    }

    @Test
    void testGetCompositeById_NotFound() {
        when(compositeService.getCompositeStructure("non-existent-id")).thenReturn(Optional.empty());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get("/non-existent-id")
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    void testGetCompositeById_InternalServerError() {
        when(compositeService.getCompositeStructure("error-id")).thenThrow(new RuntimeException("Internal Server Error"));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get("/error-id")
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .body("reason", is("Unexpected exception"))
                .body("message", is("Internal Server Error"));
    }

    @Test
    void testSaveOrUpdateComposite_Success() throws JsonProcessingException {
        CompositeStructureDto request = new CompositeStructureDto("test-id", Set.of("ns-1", "ns-2"));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body((new ObjectMapper()).writeValueAsString(request))
                .when().post()
                .then()
                .statusCode(NO_CONTENT.getStatusCode());

        verify(compositeService).saveOrUpdateCompositeStructure(request);
        verify(compositeService).getBaselineByNamespace("ns-1");
        verify(compositeService).getBaselineByNamespace("ns-2");
        verifyNoMoreInteractions(compositeService);
    }

    @Test
    void testSaveOrUpdateComposite_IdBlank() throws JsonProcessingException {
        CompositeStructureDto request = new CompositeStructureDto("", Set.of("ns-1", "ns-2"));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body((new ObjectMapper()).writeValueAsString(request))
                .when().post()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", is("Validation error: 'id field can't be empty'"));

        verifyNoInteractions(compositeService);
    }

    @Test
    void testSaveOrUpdateComposite_NamespacesEmpty() throws JsonProcessingException {
        CompositeStructureDto request = new CompositeStructureDto("test-id", Collections.emptySet());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body((new ObjectMapper()).writeValueAsString(request))
                .when().post()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", is("Validation error: 'namespace field can't be empty'"));

        verifyNoInteractions(compositeService);
    }

    @Test
    void testSaveOrUpdateComposite_NamespaceConflict() throws JsonProcessingException {
        CompositeStructureDto request = new CompositeStructureDto("test-id", Set.of("ns-1", "ns-2"));
        when(compositeService.getBaselineByNamespace("ns-2")).thenReturn(Optional.of("existing-id"));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body((new ObjectMapper()).writeValueAsString(request))
                .when().post()
                .then()
                .statusCode(CONFLICT.getStatusCode())
                .body("message", is("Validation error: 'can't save or update composite structure because ns-2 namespace is registered in another composite'"));

        verify(compositeService, never()).saveOrUpdateCompositeStructure(request);
    }


    @Test
    void testDeleteCompositeById_Success() {
        when(compositeService.getCompositeStructure("test-id"))
                .thenReturn(Optional.of(new CompositeStructure("test-id", Set.of("test-id", "ns-1"))));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().delete("/test-id/delete")
                .then()
                .statusCode(NO_CONTENT.getStatusCode());

        verify(compositeService).getCompositeStructure("test-id");
        verify(compositeService).deleteCompositeStructure("test-id");
        verifyNoMoreInteractions(compositeService);
    }

    @Test
    void testDeleteCompositeById_NotFound() {
        when(compositeService.getCompositeStructure("non-existent-id")).thenReturn(Optional.empty());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().delete("/non-existent-id/delete")
                .then()
                .statusCode(NOT_FOUND.getStatusCode());

        verify(compositeService).getCompositeStructure("non-existent-id");
        verifyNoMoreInteractions(compositeService);
    }

    @Test
    void testDeleteCompositeById_InternalServerError() {
        when(compositeService.getCompositeStructure("error-id"))
                .thenThrow(new RuntimeException("Internal Server Error"));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().delete("/error-id/delete")
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .body("message", is("Internal Server Error"));
    }
}