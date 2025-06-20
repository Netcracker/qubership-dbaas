package org.qubership.cloud.dbaas.controller.v3;

import org.qubership.cloud.dbaas.DbaasApiPath;
import org.qubership.cloud.dbaas.dto.v3.DatabaseResponseV3ListCP;
import org.qubership.cloud.dbaas.dto.v3.DebugLogicalDatabaseV3;
import org.qubership.cloud.dbaas.dto.v3.DumpResponseV3;
import org.qubership.cloud.dbaas.dto.v3.GhostDatabasesResponse;
import org.qubership.cloud.dbaas.dto.v3.LostDatabasesResponse;
import org.qubership.cloud.dbaas.dto.v3.OverallStatusResponse;
import org.qubership.cloud.dbaas.exceptions.ForbiddenDeleteOperationException;
import org.qubership.cloud.dbaas.service.DbaaSHelper;
import org.qubership.cloud.dbaas.service.DebugService;
import cz.jirutka.rsql.parser.RSQLParserException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static org.qubership.cloud.dbaas.Constants.DB_CLIENT;
import static org.qubership.cloud.dbaas.DbaasApiPath.GET_OVERALL_STATUS_PATH;
import static org.qubership.cloud.dbaas.DbaasApiPath.FIND_GHOST_DB_PATH;
import static org.qubership.cloud.dbaas.DbaasApiPath.FIND_LOST_DB_PATH;

@Slf4j
@Path(DbaasApiPath.DEBUG_INTERNAL_PATH_V3)
@Tag(name = "Controller for debug operations")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(DB_CLIENT)
public class DebugControllerV3 {

    public static final String DUMP_ZIP_CONTENT_DISPOSITION_HEADER_VALUE = "attachment; filename=\"dbaas_dump.zip\"";

    @Inject
    DebugService debugService;
    @Inject
    DbaaSHelper dbaaSHelper;

    @Operation(summary = "V3. Get Dump",
        description = """
            Retrieves a dump of DbaaS database information, including logical databases, physical databases, declarative configurations, BG domains and balancing rules.
            By default, response body is returned as compressed zip file with json file inside.
            However, it is possible to get response body in JSON format instead of file.""")
    @APIResponses({
        @APIResponse(responseCode = "500", description = "An error occurred during getting dump"),
        @APIResponse(responseCode = "200", description = "Successfully retrieved dump",
            content = @Content(schema = @Schema(implementation = DumpResponseV3.class))
        )
    })
    @Path("/dump")
    @GET
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM})
    public Response getDumpOfDbaasDatabaseInformation(@HeaderParam(HttpHeaders.ACCEPT) String acceptHeaderValue) {
        log.info("Received request to get dump of DbaaS database information. Accept header with value: {}", acceptHeaderValue);

        var dump = debugService.loadDumpV3();

        if (MediaType.APPLICATION_JSON.equals(acceptHeaderValue)) {
            return Response.ok(dump)
                .type(MediaType.APPLICATION_JSON)
                .build();
        }

        StreamingOutput streamingOutput = debugService.getStreamingOutputSerializingDumpToZippedJsonFile(dump);

        return Response.ok(streamingOutput)
            .type(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, DUMP_ZIP_CONTENT_DISPOSITION_HEADER_VALUE)
            .build();
    }

    @Operation(summary = "V3. Get lost databases",
            description = "Returns list of lost databases (databases that registered in DBaaS, but not exists in adapter)")
    @APIResponse(responseCode = "500", description = "Internal error")
    @APIResponse(responseCode = "200", description = "List of lost databases", content = @Content(schema = @Schema(implementation = DatabaseResponseV3ListCP.class, type = SchemaType.ARRAY)))
    @Path(FIND_LOST_DB_PATH)
    @GET
    @RolesAllowed(DB_CLIENT)
    public Response getLostDatabases() {
        log.info("Getting lost databases");
        List<LostDatabasesResponse> lostDatabases = debugService.findLostDatabases();
        return Response.ok(lostDatabases).build();
    }

    @Operation(summary = "V3. Get ghost databases",
            description = "Returns list of ghost databases (databases that exists in adapter, but not registered in DBaaS)")
    @APIResponse(responseCode = "500", description = "Internal error")
    @APIResponse(responseCode = "200", description = "List of ghost databases", content = @Content(schema = @Schema(implementation = GhostDatabasesResponse.class, type = SchemaType.ARRAY)))
    @Path(FIND_GHOST_DB_PATH)
    @GET
    @RolesAllowed(DB_CLIENT)
    public Response getGhostDatabases() {
        log.info("Getting ghost databases");
        List<GhostDatabasesResponse> ghostDatabases = debugService.findGhostDatabases();
        return Response.ok(ghostDatabases).build();
    }

    @Operation(summary = "V3. Get overall status",
            description = "Get DBaaS overall status")
    @APIResponses({
            @APIResponse(responseCode = "500", description = "An error occurred during getting overall status"),
            @APIResponse(responseCode = "200", description = "Successfully retrieved status",
                    content = @Content(schema = @Schema(implementation = OverallStatusResponse.class))
            )
    })
    @Path(GET_OVERALL_STATUS_PATH)
    @GET
    @Produces({MediaType.APPLICATION_JSON})
    public Response getStatus() {
        log.info("Received request to get DBaaS overall status");
        OverallStatusResponse overallStatus = debugService.getOverallStatus();
        return Response.ok(overallStatus).build();
    }

    @Operation(summary = "V3. Debug Get Logical Databases",
        description = """
            Retrieves Logical Database instances in near-tabular form.
            Operation supports filters in 'filter' query parameter in style of RESTful Service Query Language (RSQL).""")
    @APIResponses({
        @APIResponse(responseCode = "500", description = "An error occurred during getting response"),
        @APIResponse(responseCode = "400", description = "Incorrect RSQL query in 'filter' query parameter"),
        @APIResponse(responseCode = "200", description = "Successfully retrieved list of debug logical databases",
            content = @Content(schema = @Schema(implementation = DebugLogicalDatabaseV3.class, type = SchemaType.ARRAY))
        )
    })
    @Path("/databases")
    @GET
    public Response debugGetLogicalDatabases(
            @Parameter(description = "This parameter specifies custom RESTful Service Query Language (RSQL) query to apply filtering.")
            @QueryParam("filter") String filterQueryParamValue) {
        try {
            log.info("Received request to Debug Get Logical Databases. Accept filter query parameter with value: {}", filterQueryParamValue);

            var searchResponse = debugService.findDebugLogicalDatabases(filterQueryParamValue);

            return Response.ok(searchResponse).build();
        } catch (RSQLParserException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("Invalid RSQL query passed in 'filter' query parameter: " + e.getMessage())
                .build();
        }
    }

    @Operation(summary = "V3. Find all registered namespaces",
        description = """
            Find all registered namespaces for logical databases, namespace backups, per namespace rules, \
            per microservice rules, database declarative configurations, bluegreen namespaces and composite namespaces""",
        hidden = true
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Successfully found list of all registered namespaces", content = @Content(schema = @Schema(implementation = String.class, type = SchemaType.ARRAY))),
        @APIResponse(responseCode = "403", description = "Dbaas is working in PROD mode. Finding all registered namespaces is prohibited", content = @Content(schema = @Schema(implementation = String.class, type = SchemaType.ARRAY)))
    })
    @Path("/namespaces")
    @GET
    public Response findAllRegisteredNamespaces() {
        if (dbaaSHelper.isProductionMode()) {
            throw new ForbiddenDeleteOperationException();
        }

        log.info("Received request to Find All Registered Namespaces");

        var namespaces = debugService.findAllRegisteredNamespaces();

        return Response.ok(namespaces).build();
    }
}
