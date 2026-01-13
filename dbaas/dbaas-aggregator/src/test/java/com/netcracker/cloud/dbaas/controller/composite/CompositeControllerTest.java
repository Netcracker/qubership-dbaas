package com.netcracker.cloud.dbaas.controller.composite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.dto.composite.CompositeStructureDto;
import com.netcracker.cloud.dbaas.entity.pg.composite.CompositeStructure;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.service.composite.CompositeNamespaceService;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.common.mapper.TypeRef;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(CompositeController.class)
class CompositeControllerTest {

    @InjectSpy
    CompositeNamespaceService compositeServiceMock;

    @Test
    void testGetAllCompositeStructures_Success() {
        CompositeStructure expected = new CompositeStructure("ns-1", Set.of("ns-1", "ns-2"));
        when(compositeServiceMock.getAllCompositeStructures())
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
        when(compositeServiceMock.getAllCompositeStructures()).thenReturn(Collections.emptyList());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get()
                .then()
                .statusCode(OK.getStatusCode())
                .body(is("[]"));

        verify(compositeServiceMock).getAllCompositeStructures();
    }

    @Test
    void testGetAllCompositeStructures_InternalServerError() {
        when(compositeServiceMock.getAllCompositeStructures()).thenThrow(new RuntimeException("Internal Server Error"));

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
        when(compositeServiceMock.getCompositeStructure("test-id")).thenReturn(Optional.of(expected));

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
        when(compositeServiceMock.getCompositeStructure("non-existent-id")).thenReturn(Optional.empty());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get("/non-existent-id")
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    void testGetCompositeById_InternalServerError() {
        when(compositeServiceMock.getCompositeStructure("error-id")).thenThrow(new RuntimeException("Internal Server Error"));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get("/error-id")
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .body("reason", is("Unexpected exception"))
                .body("message", is("Internal Server Error"));
    }

    @Test
    void testSaveOrUpdateComposite_Success() throws JsonProcessingException {
        compositeServiceMock.deleteCompositeStructure("ns-1");
        CompositeStructureDto request = CompositeStructureDto.builder()
                .id("ns-1")
                .namespaces(Set.of("ns-1", "ns-2"))
                .build();
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body((new ObjectMapper()).writeValueAsString(request))
                .when().post()
                .then()
                .statusCode(NO_CONTENT.getStatusCode());

        verify(compositeServiceMock).saveOrUpdateCompositeStructure(request);
        verify(compositeServiceMock).getBaselineByNamespace("ns-1");
        verify(compositeServiceMock).getBaselineByNamespace("ns-2");
    }

    @Test
    void testSaveOrUpdateComposite_IdBlank() throws JsonProcessingException {
        CompositeStructureDto request = CompositeStructureDto.builder()
                .id("")
                .namespaces(Set.of("ns-1", "ns-2"))
                .build();

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body((new ObjectMapper()).writeValueAsString(request))
                .when().post()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", is("Validation error: 'id field can't be empty'"));

        verifyNoInteractions(compositeServiceMock);
    }

    @Test
    void testSaveOrUpdateComposite_NamespacesEmpty() throws JsonProcessingException {
        CompositeStructureDto request = CompositeStructureDto.builder()
                .id("test-id")
                .namespaces(Collections.emptySet())
                .build();

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body((new ObjectMapper()).writeValueAsString(request))
                .when().post()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", is("Validation error: 'namespace field can't be empty'"));

        verifyNoInteractions(compositeServiceMock);
    }

    @Test
    void testSaveOrUpdateComposite_NamespaceConflict() throws JsonProcessingException {
        CompositeStructureDto request = CompositeStructureDto.builder()
                .id("test-id")
                .namespaces(Set.of("ns-1", "ns-2"))
                .build();
        when(compositeServiceMock.getBaselineByNamespace("ns-2")).thenReturn(Optional.of("existing-id"));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body((new ObjectMapper()).writeValueAsString(request))
                .when().post()
                .then()
                .statusCode(CONFLICT.getStatusCode())
                .body("message", is("Validation error: 'can't save or update composite structure because ns-2 namespace is registered in another composite'"));

        verify(compositeServiceMock, never()).saveOrUpdateCompositeStructure(request);
    }

    @Test
    void testSaveOrUpdateComposite_WrongModifyIndex() throws JsonProcessingException {
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body((new ObjectMapper()).writeValueAsString(new CompositeStructureDto("ns-1", Set.of("ns-1", "ns-2"), BigDecimal.ONE)))
                .when().post()
                .then()
                .statusCode(NO_CONTENT.getStatusCode());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body((new ObjectMapper()).writeValueAsString(new CompositeStructureDto("ns-1", Set.of("ns-1", "ns-2"), BigDecimal.TWO)))
                .when().post()
                .then()
                .statusCode(NO_CONTENT.getStatusCode());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body((new ObjectMapper()).writeValueAsString(new CompositeStructureDto("ns-1", Set.of("ns-1", "ns-2"), BigDecimal.ONE)))
                .when().post()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", is("Validation error: 'new modify index '1' should be greater than current index '2''"));
    }

    @Test
    void testDeleteCompositeById_Success() {
        when(compositeServiceMock.getCompositeStructure("test-id"))
                .thenReturn(Optional.of(new CompositeStructure("test-id", Set.of("test-id", "ns-1"))));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().delete("/test-id/delete")
                .then()
                .statusCode(NO_CONTENT.getStatusCode());


        verify(compositeServiceMock).getCompositeStructure("test-id");
        verify(compositeServiceMock).deleteCompositeStructure("test-id");
    }

    @Test
    void testDeleteCompositeById_NotFound() {
        when(compositeServiceMock.getCompositeStructure("non-existent-id")).thenReturn(Optional.empty());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().delete("/non-existent-id/delete")
                .then()
                .statusCode(NOT_FOUND.getStatusCode());

        verify(compositeServiceMock).getCompositeStructure("non-existent-id");
        verifyNoMoreInteractions(compositeServiceMock);
    }

    @Test
    void testDeleteCompositeById_InternalServerError() {
        when(compositeServiceMock.getCompositeStructure("error-id"))
                .thenThrow(new RuntimeException("Internal Server Error"));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().delete("/error-id/delete")
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .body("message", is("Internal Server Error"));
    }
}
