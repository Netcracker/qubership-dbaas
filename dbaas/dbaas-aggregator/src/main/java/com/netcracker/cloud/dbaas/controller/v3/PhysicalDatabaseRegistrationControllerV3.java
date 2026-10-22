package com.netcracker.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.netcracker.cloud.dbaas.DbaasApiPath;
import com.netcracker.cloud.dbaas.dto.RegisteredPhysicalDatabasesDTO;
import com.netcracker.cloud.dbaas.dto.v3.InstructionRequestV3;
import com.netcracker.cloud.dbaas.dto.v3.PhysicalDatabaseRegistryRequestV3;
import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabase;
import com.netcracker.cloud.dbaas.exceptions.AdapterUnavailableException;
import com.netcracker.cloud.dbaas.exceptions.PhysicalDatabaseRegistrationConflictException;
import com.netcracker.cloud.dbaas.exceptions.UnregisteredPhysicalDatabaseException;
import com.netcracker.cloud.dbaas.service.PhysicalDatabasesService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

import static com.netcracker.cloud.dbaas.Constants.DB_CLIENT;
import static jakarta.ws.rs.core.Response.Status.*;

@Slf4j
@Path(DbaasApiPath.PHYSICAL_DATABASES_PATH_V3)
@Tag(name = "Physical databases registration controller",
        description = "Provides API to register new physical databases")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(DB_CLIENT)
public class PhysicalDatabaseRegistrationControllerV3 {

    @Inject
    private PhysicalDatabasesService physicalDatabasesService;

    @Operation(summary = "V3. Register new physical database",
            description = "Creates new physical database and returns path to it with physical database identifier.")
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Database created", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "200", description = "Updated existing database", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "202", description = "Adapter should continue to create roles for new portions", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "409", description = "Database could not be registered as physical database already " +
                    "exists with another adapter id or the same adapter already exists and it is used with other physical database"),
            @APIResponse(responseCode = "502", description = "Adapter is not available during handshake process"),
            @APIResponse(responseCode = "400", description = "Adapter already running or request validation failed"),
            @APIResponse(responseCode = "500", description = "Unexpected server error. " +
                    "A known cause is a request without a required field: `adapterAddress`, `httpBasicCredentials`, `metadata`, " +
                    "or `metadata.features.multiusers` when an already registered adapter reports a different set of roles. " +
                    "The physical database is left unchanged. Add the missing field and send the request again."),
    })
    @Path("/{phydbid}")
    @PUT
    public Response register(@Parameter(description = "Type of database. Example: MongoDB, PostgreSQL, opensearch, etc.", required = true)
                             @PathParam("type") String type,
                             @Parameter(description = "Physical database identifier. The value belongs to the specific database cluster", required = true)
                             @PathParam("phydbid") String phydbid,
                             @Parameter(description = "Enable logical database role migration. Uses only for declarative physical database registration")
                             @QueryParam("internalMigration") boolean internalMigration,
                             @Parameter(description = "Parameters for registering physical database.", required = true)
                             PhysicalDatabaseRegistryRequestV3 parameters)
            throws PhysicalDatabaseRegistrationConflictException, AdapterUnavailableException, JsonProcessingException {
        log.debug("Starting registration of new adapter type={}, physicalDatabaseId={}, internalMigration={}, parameters={}", type, phydbid, internalMigration, parameters);
        return physicalDatabasesService.registerPhysicalDatabase(parameters, type, phydbid, internalMigration);
    }


    @Operation(summary = "V3. Change default physical database",
            description = "Moves the 'global' flag to the specified existing physical database")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Updated existing physical database"),
            @APIResponse(responseCode = "404", description = "Specified physical database does not registered")})
    @Path("/{phydbid}/global")
    @PUT
    public Response makeGlobal(@Parameter(description = "Type of database. Example: MongoDB, PostgreSQL, opensearch, etc.", required = true)
                               @PathParam("type") String type,
                               @Parameter(description = "Physical database identifier. The value belongs to the specific database cluster", required = true)
                               @PathParam("phydbid") String phydbid) {
        PhysicalDatabase foundDatabase = physicalDatabasesService.getByPhysicalDatabaseIdentifier(phydbid);
        if (foundDatabase == null) {
            UnregisteredPhysicalDatabaseException exception = new UnregisteredPhysicalDatabaseException(
                    String.format("No physical database of '%s' type with '%s' id is registered to make it global", type, phydbid));
            exception.setStatus(NOT_FOUND.getStatusCode());
            throw exception;
        }
        physicalDatabasesService.makeGlobal(foundDatabase);
        return Response.ok().build();
    }

    @Operation(summary = "V3. Continuation of the migration procedure for physical database registration.",
            description = "Saving new roles for logical databases and continuing or completing the migration procedure.",
            hidden = true)
    @APIResponses({
            @APIResponse(responseCode = "500", description = "An error occurred during the migration procedure"),
            @APIResponse(responseCode = "200", description = "Migration procedure completed successfully", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "202", description = "Adapter should continue to create roles for new portions.", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "404", description = "Wrong instruction Id")})
    @Path("/{phydbid}/instruction/{instructionid}/additional-roles")
    @POST
    public Response instruction(@Parameter(description = "Type of database. Example: MongoDB, PostgreSQL, opensearch, etc.", required = true)
                                @PathParam("type") String type,
                                @Parameter(description = "Physical database identifier. The value belongs to the specific database cluster", required = true)
                                @PathParam("phydbid") String phydbid,
                                @Parameter(description = "Instruction Id")
                                @PathParam("instructionid") UUID instructionid,
                                @Parameter(description = "Parameters for registering physical database.", required = true)
                                InstructionRequestV3 parameters)
            throws PhysicalDatabaseRegistrationConflictException, AdapterUnavailableException {
        return physicalDatabasesService.handleInstruction(instructionid, parameters);
    }


    @Operation(summary = "V3. List registered physical databases",
            description = "Returns the list of registered physical databases by database type")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Registered physical databases by specific type were found.", content = @Content(schema = @Schema(implementation = RegisteredPhysicalDatabasesDTO.class))),
            @APIResponse(responseCode = "404", description = "Registered physical databases by specific type were not found.")})
    @GET
    public Response getRegisteredDatabases(@Parameter(description = "Type of database, for example: MongoDB, PostgreSQL, opensearch, etc. or all - to list all registered physical databases", required = true)
                                           @PathParam("type") String type) {
        List<PhysicalDatabase> databases;
        if ("all".equals(type)) {
            databases = physicalDatabasesService.getAllRegisteredDatabases();
        } else {
            databases = physicalDatabasesService.getRegisteredDatabases(type);
        }
        if (databases.isEmpty()) {
            final UnregisteredPhysicalDatabaseException exception = new UnregisteredPhysicalDatabaseException(
                    String.format("No physical database of '%s' type is registered", type));
            exception.setStatus(NOT_FOUND.getStatusCode());
            throw exception;
        }
        RegisteredPhysicalDatabasesDTO result = physicalDatabasesService.presentPhysicalDatabases(databases);
        return Response.ok(result).build();
    }

    @Operation(summary = "V3. Delete physical database",
            description = "Deletes physical database by database type and physical database id")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Successfully deleted physical databases."),
            @APIResponse(responseCode = "406", description = "Database is marked as default or there are connected logical databases."),
            @APIResponse(responseCode = "404", description = "Physical database with specific type and id was not found."),
            @APIResponse(responseCode = "400", description = "The request was invalid or cannot be served")
    })
    @Path("/{phydbid}")
    @DELETE
    @Transactional
    public Response deletePhysicalDatabase(@Parameter(description = "Type of database, for example: MongoDB, PostgreSQL, opensearch, etc.", required = true)
                                           @PathParam("type") String type,
                                           @Parameter(description = "Physical database identifier. The value belongs to the specific database cluster", required = true)
                                           @PathParam("phydbid") String phydbid) {
        PhysicalDatabase databaseForDeletion = physicalDatabasesService.getByPhysicalDatabaseIdentifier(phydbid);
        if (databaseForDeletion == null) {
            String msg = String.format("No physical database of %s type with phydbid %s is registered", type, phydbid);
            log.error(msg);
            return Response.status(NOT_FOUND).entity(msg).build();
        }
        if (!type.equals(databaseForDeletion.getType())) {
            String msg = String.format("Requested type %s does not match actual type %s of found physical database", type, databaseForDeletion.getType());
            log.error(msg);
            return Response.status(BAD_REQUEST).entity(msg).build();
        }
        List<PhysicalDatabase> registeredDatabasesSameType = physicalDatabasesService.getRegisteredDatabases(type);
        if (databaseForDeletion.isGlobal() && registeredDatabasesSameType.size() > 1) {
            String msg = String.format("Can't delete physical db %s because it's global. Please move the global flag to another physical database before deletion", phydbid);
            log.error(msg);
            return Response.status(NOT_ACCEPTABLE).entity(msg).build();
        }
        boolean isConnectedDbsExist = physicalDatabasesService.checkContainsConnectedLogicalDb(databaseForDeletion);
        if (isConnectedDbsExist) {
            String msg = String.format("Can't delete db %s. Connected logical databases still exist", phydbid);
            log.error(msg);
            return Response.status(NOT_ACCEPTABLE).entity(msg).build();
        }
        physicalDatabasesService.dropDatabase(databaseForDeletion);
        return Response.ok().build();
    }


}
