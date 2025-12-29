package com.netcracker.cloud.dbaas.controller.composite;

import com.netcracker.cloud.dbaas.dto.Source;
import com.netcracker.cloud.dbaas.dto.composite.CompositeStructureDto;
import com.netcracker.cloud.dbaas.entity.pg.composite.CompositeStructure;
import com.netcracker.cloud.dbaas.exceptions.NamespaceCompositeValidationException;
import com.netcracker.cloud.dbaas.service.composite.CompositeNamespaceService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static com.netcracker.cloud.dbaas.Constants.DB_CLIENT;
import static com.netcracker.cloud.dbaas.controller.error.Utils.createTmfErrorResponse;
import static jakarta.ws.rs.core.Response.Status.CONFLICT;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;

@Slf4j
@Path("/api/composite/v1/structures")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(DB_CLIENT)
public class CompositeController {

    private final AtomicLong lastAppliedIndex = new AtomicLong(0);
    private final CompositeNamespaceService compositeService;

    public CompositeController(CompositeNamespaceService compositeService) {
        this.compositeService = compositeService;
    }

    @Operation(
            summary = "Save or update composite",
            description = "Create a new composite structure or update an existing one."
    )
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Composite is saved"),
            @APIResponse(responseCode = "400", description = "Validation error"),
            @APIResponse(responseCode = "409", description = "Conflict error. Namespace in request body is already " +
                    "associated with another composite structure"),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })

    @POST
    @Transactional
    public Response saveOrUpdateComposite(CompositeStructureDto compositeRequest) {
        log.info("Received request to save or update composite {}", compositeRequest);

        if (StringUtils.isBlank(compositeRequest.getId())) {
            throw new NamespaceCompositeValidationException(Source.builder().pointer("/id").build(), "id field can't be empty");
        }
        if (CollectionUtils.isEmpty(compositeRequest.getNamespaces())) {
            throw new NamespaceCompositeValidationException(Source.builder().pointer("/namespaces").build(), "namespace field can't be empty");
        }
        for (String namespace : compositeRequest.getNamespaces()) {
            Optional<String> baseline = compositeService.getBaselineByNamespace(namespace);
            if (baseline.isPresent() && !baseline.get().equals(compositeRequest.getId())) {
                throw new NamespaceCompositeValidationException(Source.builder().build(),
                        "can't save or update composite structure because %s namespace is registered in another composite".formatted(namespace),
                        409);
            }
        }

        long currentIndex;
        do {
            currentIndex = lastAppliedIndex.get();
            if (compositeRequest.getIndex() < currentIndex) {
                throw new NamespaceCompositeValidationException(
                        Source.builder().build(),
                        "New composite version %d is less that last applied version %d".formatted(compositeRequest.getIndex(), currentIndex),
                        CONFLICT.getStatusCode()
                );
            }
            compositeService.saveOrUpdateCompositeStructure(compositeRequest);
        } while (!lastAppliedIndex.compareAndSet(currentIndex, compositeRequest.getIndex()));

        return Response.noContent().build();
    }

    @Operation(
            summary = "Get all composite structures",
            description = "Retrieve all composite structures that are registered in DBaaS."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Successful operation"),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    @GET
    public Response getAllCompositeStructures() {
        log.info("Received request to get all composite structures");
        List<CompositeStructure> compositeStructures = compositeService.getAllCompositeStructures();
        List<CompositeStructureDto> compositeStructureResponse = compositeStructures.stream()
                .map(compositeStructure -> new CompositeStructureDto(compositeStructure.getBaseline(), compositeStructure.getNamespaces()))
                .toList();
        return Response.ok(compositeStructureResponse).build();
    }

    @Operation(
            summary = "Get composite structure by composite id",
            description = "Retrieve a composite structure by its Id. Composite id by design is baseline or baseline " +
                    "origin if namespace in blue-green"
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Composite is found"),
            @APIResponse(responseCode = "404", description = "Composite not found"),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    @GET
    @Path("/{compositeId}")
    public Response getCompositeById(@PathParam("compositeId") String compositeId) {
        log.info("Received request to get composite with id {}", compositeId);
        Optional<CompositeStructure> composite = compositeService.getCompositeStructure(compositeId);
        if (composite.isEmpty()) {
            return getNotFoundTmfErrorResponse(compositeId);
        }
        return Response.ok(new CompositeStructureDto(composite.get().getBaseline(), composite.get().getNamespaces())).build();
    }

    @NotNull
    private static Response getNotFoundTmfErrorResponse(String compositeId) {
        return createTmfErrorResponse(
                new NamespaceCompositeValidationException(Source.builder().build(),
                        "composite structure with id='%s' does not exist".formatted(compositeId)),
                NOT_FOUND,
                null
        );
    }

    @Operation(
            summary = "Delete composite by composite id",
            description = "Delete a composite structure by its ID."
    )
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Composite is deleted successfully"),
            @APIResponse(responseCode = "404", description = "Composite not found"),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    @DELETE
    @Path("/{compositeId}/delete")
    @Transactional
    public Response deleteCompositeById(@PathParam("compositeId") String compositeId) {
        log.info("Received request to delete composite with id {}", compositeId);
        Optional<CompositeStructure> compositeStructure = compositeService.getCompositeStructure(compositeId);
        if (compositeStructure.isEmpty()) {
            return getNotFoundTmfErrorResponse(compositeId);
        }
        compositeService.deleteCompositeStructure(compositeId);
        return Response.noContent().build();
    }
}
