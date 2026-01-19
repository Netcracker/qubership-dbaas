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
import io.restassured.RestAssured;
import io.restassured.common.mapper.TypeRef;
import io.restassured.config.LogConfig;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(CompositeController.class)
class CompositeControllerTest {

    @InjectSpy
    CompositeNamespaceService compositeService;

    @Inject
    EntityManager entityManager;

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
        compositeService.deleteCompositeStructure("ns-1");
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

        verify(compositeService).saveOrUpdateCompositeStructure(request);
        verify(compositeService).getBaselineByNamespace("ns-1");
        verify(compositeService).getBaselineByNamespace("ns-2");
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

        verifyNoInteractions(compositeService);
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

        verifyNoInteractions(compositeService);
    }

    @Test
    void testSaveOrUpdateComposite_NamespaceConflict() throws JsonProcessingException {
        CompositeStructureDto request = CompositeStructureDto.builder()
                .id("test-id")
                .namespaces(Set.of("ns-1", "ns-2"))
                .build();
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
    void testSaveOrUpdateComposite_WrongModifyIndex() throws JsonProcessingException {
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body((new ObjectMapper()).writeValueAsString(new CompositeStructureDto("ns-1", Set.of("ns-1", "ns-2"), 1L)))
                .when().post()
                .then()
                .statusCode(NO_CONTENT.getStatusCode());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body((new ObjectMapper()).writeValueAsString(new CompositeStructureDto("ns-1", Set.of("ns-1", "ns-2"), 2L)))
                .when().post()
                .then()
                .statusCode(NO_CONTENT.getStatusCode());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body((new ObjectMapper()).writeValueAsString(new CompositeStructureDto("ns-1", Set.of("ns-1", "ns-2"), 1L)))
                .when().post()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", is("Validation error: 'new modify index '1' should be greater than current index '2''"));
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

    @Test
    void saveOrUpdateComposite_concurrent() {
        RestAssured.config = RestAssured.config()
                .logConfig(LogConfig.logConfig().enablePrettyPrinting(false));
        List<CompletableFuture<Void>> futures =
                IntStream.range(0, 100)
                        .mapToObj(i ->
                                CompletableFuture.runAsync(() -> {
                                            CompositeStructureDto request = CompositeStructureDto.builder()
                                                    .id("base")
                                                    .namespaces(Set.of("ns-%d".formatted(i)))
                                                    .modifyIndex(i == 50 ? 1000 : (long) ThreadLocalRandom.current().nextInt(1000))
                                                    .build();
                                            try {
                                                given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .body((new ObjectMapper()).writeValueAsString(request))
                                                        .when().post();
                                            } catch (JsonProcessingException e) {
                                                fail("something went wrong", e);
                                            }
                                        }
                                )
                        ).toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Object singleResult = entityManager.createNativeQuery(
                        "SELECT MAX(modify_index) FROM composite_namespace_modify_indexes"
                )
                .getSingleResult();
        assertEquals(BigDecimal.valueOf(1000), singleResult);
    }
}
