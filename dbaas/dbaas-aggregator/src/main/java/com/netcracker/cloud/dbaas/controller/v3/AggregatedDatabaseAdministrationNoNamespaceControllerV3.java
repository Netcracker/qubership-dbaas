package com.netcracker.cloud.dbaas.controller.v3;

import com.netcracker.cloud.core.error.rest.tmf.TmfErrorResponse;
import com.netcracker.cloud.dbaas.controller.abstact.AbstractController;
import com.netcracker.cloud.dbaas.dto.CleanupMarkedForDropRequest;
import com.netcracker.cloud.dbaas.dto.Source;
import com.netcracker.cloud.dbaas.dto.v3.DatabaseResponseV3ListCP;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.exceptions.ErrorCodes;
import com.netcracker.cloud.dbaas.exceptions.RequestValidationException;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.service.DBaaService;
import com.netcracker.cloud.dbaas.service.DeletionService;
import com.netcracker.cloud.dbaas.service.ResponseHelper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.jboss.resteasy.annotations.Separator;

import java.util.List;
import java.util.Set;

import static com.netcracker.cloud.dbaas.Constants.DB_CLIENT;
import static com.netcracker.cloud.dbaas.Constants.NAMESPACE_CLEANER;
import static com.netcracker.cloud.dbaas.DbaasApiPath.*;

@Slf4j
@Path(DATABASES_WITHOUT_NAMESPACE_PATH_V3)
@Produces(MediaType.APPLICATION_JSON)
public class AggregatedDatabaseAdministrationNoNamespaceControllerV3 extends AbstractController {
    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    DBaaService dBaaService;
    @Inject
    DeletionService deletionService;
    @Inject
    ResponseHelper responseHelper;

    @Inject
    DatabaseDbaasRepository databaseDbaasRepository;

    @Operation(summary = "V3. Get database by name",
            description = "Returns list of databases for the specific logical database name")
    @APIResponse(responseCode = "500", description = "Internal error")
    @APIResponse(responseCode = "200", description = "List of databases with the specific logical database name", content = @Content(schema = @Schema(implementation = DatabaseResponseV3ListCP.class, type = SchemaType.ARRAY)))
    @Path(FIND_BY_NAME_PATH)
    @GET
    @RolesAllowed(DB_CLIENT)
    public Response getDatabasesByName(@PathParam("dbname") String dbname,
                                       @QueryParam(NAMESPACE_PARAMETER) String namespace,
                                       @QueryParam("withDecryptedPassword") @DefaultValue("false") Boolean withDecryptedPassword) {
        log.info("Get databases with name {}. Query params: namespace {}, withDecryptedPassword {}", dbname, namespace, withDecryptedPassword);
        List<DatabaseRegistry> bdRegistries = databaseRegistryDbaasRepository.findAnyLogDbTypeByNameAndOptionalParams(dbname, namespace);
        if (withDecryptedPassword) {
            bdRegistries.forEach(dbRegistry -> dBaaService.decryptPassword(dbRegistry.getDatabase()));
        }
        List<DatabaseResponseV3ListCP> response = responseHelper.toDatabaseResponse(bdRegistries, false);
        return Response.ok(response).build();
    }

    @Operation(summary = "V3. Find logical databases in namespaces",
            description = "Lists logical databases prepared for specified namespaces",
            hidden = true
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Successfully returned logical databases.")})
    @GET
    @RolesAllowed(DB_CLIENT)
    public Response findLogicalDatabasesInNamespaces(@Parameter(description = "List of names of namespaces where logical databases have to be deleted from")
                                                     @QueryParam("namespaces") @Separator(",") Set<String> namespaces,
                                                     @Parameter(description = "Integer value to specify how many logical databases have to be skipped")
                                                     @QueryParam("offset") @DefaultValue("0") Integer offset,
                                                     @Parameter(description = "Integer value to specify how many logical databases have to be returned")
                                                     @QueryParam("limit") @DefaultValue("20") Integer limit) {
        if (CollectionUtils.isEmpty(namespaces)) {
            throw new BadRequestException("Query parameter 'namespaces' must be not empty list of strings");
        }

        if (offset == null || offset < 0) {
            throw new BadRequestException("Query parameter 'offset' must be positive integer value");
        }

        if (limit == null || limit < 1 || limit > 100) {
            throw new BadRequestException("Query parameter 'limit' must be a positive integer value between 1 and 100");
        }

        return Response.ok(databaseDbaasRepository.findByNamespacesWithOffsetBasedPagination(namespaces, offset, limit)).build();
    }

    @Operation(summary = "V3. Clean all logical databases in namespaces",
            description = "Drops all logical databases for the specified namespaces",
            hidden = true
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Logical databases were successfully dropped in specified namespaces", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "403", description = "Dbaas is working in PROD mode. Deleting logical databases is prohibited", content = @Content(schema = @Schema(implementation = String.class)))
    })
    @Path("/deleteall")
    @DELETE
    @RolesAllowed(NAMESPACE_CLEANER)
    @Transactional
    public Response dropAllLogicalDatabasesInNamespaces(@Parameter(description = "Namespaces to clean, operation would drop all logical databases in that cloud project", required = true)
                                                        @QueryParam("namespaces") @Separator(",") Set<String> namespaces) {
        if (CollectionUtils.isEmpty(namespaces)) {
            throw new BadRequestException("Query parameter 'namespaces' must be not empty list of strings");
        }

        log.info("Received request to drop all logical databases in {} namespaces {}", namespaces.size(), namespaces);

        assertNotProdMode();

        var logicalDatabasesAmount = databaseDbaasRepository.countByNamespaces(namespaces);

        if (logicalDatabasesAmount == 0) {

            log.info("Namespaces {} are empty, dropping is not needed", namespaces);

            return Response.ok(String.format("All %s namespaces %s do not contain any logical databases", namespaces.size(), namespaces)).build();
        }

        deletionService.cleanupAllLogicalDatabasesInNamespacesByPortionsAsync(namespaces);

        return Response.ok(String.format("Started async deletion %s logical databases in %s namespaces %s", logicalDatabasesAmount, namespaces.size(), namespaces)).build();
    }

    @Operation(summary = "Get list of marked for drop databases",
            description = "List of databases with marked for drop state related to requested namespaces " +
                    "(orphan databases are also included)")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of marked for drop databases", content = @Content(schema = @Schema(implementation = DatabaseResponseV3ListCP.class, type = SchemaType.ARRAY))),
            @APIResponse(responseCode = "400", description = "The request was invalid or cannot be served", content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "500", description = "Internal processing error", content = @Content(schema = @Schema(implementation = TmfErrorResponse.class)))
    })
    @Path("/marked-for-drop")
    @POST
    @RolesAllowed(NAMESPACE_CLEANER)
    public Response getMarkedForDropDatabases(@Parameter(description = "List of namespaces by which marked for drop databases is needed to return", required = true)
                                              List<String> namespaces) {
        log.info("Receive request to get marked for drop databases in '{}' namespaces", namespaces);
        if (CollectionUtils.isEmpty(namespaces)) {
            throw new RequestValidationException(ErrorCodes.CORE_DBAAS_4043,
                    ErrorCodes.CORE_DBAAS_4043.getDetail("Should be at least one namespace specified"),
                    Source.builder().parameter("namespaces").build());
        }
        List<DatabaseRegistry> markedForDropRegistries = deletionService.getMarkedForDropRegistries(namespaces);
        List<DatabaseResponseV3ListCP> response = markedForDropRegistries.stream()
                .map(dbr -> new DatabaseResponseV3ListCP(dbr, dbr.getPhysicalDatabaseId())).toList();
        return Response.ok(response).build();
    }

    @Operation(summary = "Cleanup marked for drop databases",
            description = "Housekeeping operation which drops all databases with marked for drop state " +
                    "(orphan databases are also included)")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of marked for drop databases, no cleanup performed", content = @Content(schema = @Schema(implementation = DatabaseResponseV3ListCP.class, type = SchemaType.ARRAY))),
            @APIResponse(responseCode = "202", description = "List of marked for drop databases, async cleanup process is started", content = @Content(schema = @Schema(implementation = DatabaseResponseV3ListCP.class, type = SchemaType.ARRAY))),
            @APIResponse(responseCode = "400", description = "The request is invalid or cannot be served", content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "403", description = "DBaaS is working in PROD mode. Deleting logical databases is prohibited", content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "Internal processing error", content = @Content(schema = @Schema(implementation = TmfErrorResponse.class)))
    })
    @Path("/marked-for-drop")
    @DELETE
    @RolesAllowed(NAMESPACE_CLEANER)
    public Response cleanupMarkedForDropDatabases(CleanupMarkedForDropRequest request) {
        log.info("Receive request to cleanup marked for drop databases in '{}' namespaces with delete={} and force={}",
                request.getNamespaces(), request.getDelete(), request.getForce());
        if (CollectionUtils.isEmpty(request.getNamespaces())) {
            throw new RequestValidationException(ErrorCodes.CORE_DBAAS_4043,
                    ErrorCodes.CORE_DBAAS_4043.getDetail("Should be at least one namespace specified"),
                    Source.builder().parameter("namespaces").build());
        }
        List<String> namespaces = request.getNamespaces();
        assertNotProdMode();
        List<DatabaseRegistry> markedForDropRegistries = deletionService.getMarkedForDropRegistries(namespaces);
        List<DatabaseResponseV3ListCP> response = markedForDropRegistries.stream()
                .map(dbr -> new DatabaseResponseV3ListCP(dbr, dbr.getPhysicalDatabaseId())).toList();
        if (request.getDelete()) {
            log.info("{} databases are going to be deleted", markedForDropRegistries.size());
            namespaces.forEach(namespace -> deletionService.cleanupMarkedForDropRegistriesAsync(namespace, request.getForce()));
            return Response.accepted(response).build();
        } else {
            return Response.ok(response).build();
        }
    }
}
