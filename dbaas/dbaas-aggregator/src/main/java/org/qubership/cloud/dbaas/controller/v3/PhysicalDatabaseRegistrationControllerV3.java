package org.qubership.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.qubership.cloud.dbaas.DbaasApiPath;
import org.qubership.cloud.dbaas.dto.RegisteredPhysicalDatabasesDTO;
import org.qubership.cloud.dbaas.dto.v3.*;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabaseInstruction;
import org.qubership.cloud.dbaas.exceptions.AdapterUnavailableException;
import org.qubership.cloud.dbaas.exceptions.PhysicalDatabaseRegistrationConflictException;
import org.qubership.cloud.dbaas.exceptions.UnregisteredPhysicalDatabaseException;
import org.qubership.cloud.dbaas.service.InstructionService;
import org.qubership.cloud.dbaas.service.PhysicalDatabasesService;
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

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import static org.qubership.cloud.dbaas.Constants.DB_CLIENT;
import static org.qubership.cloud.dbaas.DbaasApiPath.DBAAS_PATH_V3;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static jakarta.ws.rs.core.Response.Status.NOT_ACCEPTABLE;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;

@Slf4j
@Path(DbaasApiPath.PHYSICAL_DATABASES_PATH_V3)
@Tag(name = "Physical databases registration controller",
        description = "Provides API to register new physical databases")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(DB_CLIENT)
public class PhysicalDatabaseRegistrationControllerV3 {

    @Inject
    private PhysicalDatabasesService physicalDatabasesService;

    @Inject
    private InstructionService instructionService;

    @Operation(summary = "V3. Register new physical database",
            description = "Creates new physical database and returns path to it with physical database identifier.")
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Database created", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "200", description = "Updated existing database", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "202", description = "Adapter should continue to create roles for new portions", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "409", description = "Database could not be registered as physical database already " +
                    "exists with another adapter id or the same adapter already exists and it is used with other physical database"),
            @APIResponse(responseCode = "502", description = "Adapter is not available during handshake process"),
            @APIResponse(responseCode = "400", description = "Adapter already running")})
    @Path("/{phydbid}")
    @PUT
    @Transactional
    public Response register(@Parameter(description = "Type of database. Example: MongoDB, PostgreSQL, elasticsearch, etc.", required = true)
                             @PathParam("type") String type,
                             @Parameter(description = "Physical database identifier. The value belongs to the specific database cluster", required = true)
                             @PathParam("phydbid") String phydbid,
                             @Parameter(description = "Parameters for registering physical database.", required = true)
                             PhysicalDatabaseRegistryRequestV3 parameters)
            throws PhysicalDatabaseRegistrationConflictException, AdapterUnavailableException, JsonProcessingException {
        Optional<PhysicalDatabase> foundDatabase = physicalDatabasesService.foundPhysicalDatabase(phydbid, type, parameters);
        if (foundDatabase.isPresent()) {
            PhysicalDatabase physicalDatabase = foundDatabase.get();
            Boolean rolesDifferent = instructionService.isRolesDifferent(parameters.getMetadata().getSupportedRoles(), physicalDatabase.getRoles());
            if (rolesDifferent && parameters.getMetadata().getFeatures().get("multiusers")) {
                List<Database> logicalDatabases = instructionService.getLogicalDatabasesForMigration(phydbid,
                        type,
                        parameters.getMetadata().getSupportedRoles());
                if (!logicalDatabases.isEmpty()) {
                    if (parameters.getStatus().equals("running") && parameters.getMetadata().getApiVersion().equals("v2")) {
                        PhysicalDatabaseInstruction existingInstruction = instructionService.findInstructionByPhyDbId(physicalDatabase.getPhysicalDatabaseIdentifier());
                        if (existingInstruction == null) {
                            log.info("Database with set instruction does not exist, new one should be created");
                            Instruction instruction = instructionService.buildInstructionForAdditionalRoles(logicalDatabases);
                            log.info("Sending instruction for create users for roles = {}", parameters.getMetadata().getSupportedRoles());
                            return Response.accepted(instructionService.saveInstruction(physicalDatabase, instruction, parameters)).build();
                        } else {
                            Instruction instruction = instructionService.findPortion(String.valueOf(existingInstruction.getId()));
                            if (instruction == null) {
                                log.warn("Instruction is null, Creating new instruction");
                                Instruction instructionCreated = recreateInstruction(existingInstruction, logicalDatabases);
                                log.info("Sending new instruction for create users for roles = {}", parameters.getMetadata().getSupportedRoles());
                                return Response.accepted().entity(instructionService.saveInstruction(physicalDatabase, instructionCreated, parameters)).build();
                            }
                            log.info("Got instruction with portion = {}", instruction);
                            return Response.accepted(Map.of("instruction", instruction)).build();
                        }
                    }
                    log.warn("Adapter status run or adapter version is not equals v2");
                    return Response.status(BAD_REQUEST).entity("Adapter status run or adapter version is not equals v2").build();
                }
            }
            if (physicalDatabasesService.isDbActual(parameters, physicalDatabase)) {
                log.info("Adapter '{}' update is not required", physicalDatabase.getPhysicalDatabaseIdentifier());
            } else {
                physicalDatabasesService.writeChanges(phydbid, parameters, physicalDatabase);
            }
            return Response.ok().build();

        }
        physicalDatabasesService.physicalDatabaseRegistration(phydbid, type, parameters);
        return Response.created(URI.create(DBAAS_PATH_V3 + "/" + type + "/physical_databases/" + phydbid)).build();
    }

    @Operation(summary = "V3. Change default physical database",
            description = "Moves the 'global' flag to the specified existing physical database")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Updated existing physical database"),
            @APIResponse(responseCode = "404", description = "Specified physical database does not registered")})
    @Path("/{phydbid}/global")
    @PUT
    public Response makeGlobal(@Parameter(description = "Type of database. Example: MongoDB, PostgreSQL, elasticsearch, etc.", required = true)
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
    @Transactional
    public Response instruction(@Parameter(description = "Type of database. Example: MongoDB, PostgreSQL, elasticsearch, etc.", required = true)
                                @PathParam("type") String type,
                                @Parameter(description = "Physical database identifier. The value belongs to the specific database cluster", required = true)
                                @PathParam("phydbid") String phydbid,
                                @Parameter(description = "Instruction Id")
                                @PathParam("instructionid") String instructionid,
                                @Parameter(description = "Parameters for registering physical database.", required = true)
                                InstructionRequestV3 parameters)
            throws PhysicalDatabaseRegistrationConflictException, AdapterUnavailableException, JsonProcessingException {
        Instruction currentInstruction = instructionService.findInstructionById(instructionid);
        log.info("current instruction = {}", currentInstruction);
        if (currentInstruction == null) {
            log.error("Instruction with Id = {} not found", instructionid);
            return Response.status(NOT_FOUND).entity(String.format("Instruction with Id = %s not found", instructionid)).build();
        }
        if (parameters.getSuccess() != null) {
            try {
                instructionService.saveConnectionPropertiesAfterRolesRegistration(parameters.getSuccess());
                List<UUID> dbIdList = new ArrayList<>();
                for (SuccessRegistrationV3 successRegistration : parameters.getSuccess()) {
                    dbIdList.add(successRegistration.getId());
                }
                List<AdditionalRoles> finalrolesToset = currentInstruction.getAdditionalRoles().stream()
                        .filter(role -> !dbIdList.contains(role.getId()))
                        .collect(Collectors.toList());
                instructionService.updateInstructionWithContext(currentInstruction, finalrolesToset);
            } catch (Exception e) {
                instructionService.deleteInstruction(instructionid);
                return Response.status(INTERNAL_SERVER_ERROR).entity("An error occurred during the migration procedure: " + e.getMessage()).build();
            }
        }
        if (parameters.getFailure() != null) {
            instructionService.deleteInstruction(instructionid);
            return Response.status(INTERNAL_SERVER_ERROR).entity("An error has occurred:" + parameters.getFailure().getMessage()).build();
        }
        List<AdditionalRoles> response = instructionService.findNextAdditionalRoles(instructionid);
        if (response == null) {
            log.info("All logical databases processed in instruction with id = {}", instructionid);
            instructionService.completeMigrationProcedure(phydbid, instructionid, currentInstruction);
            return Response.ok().build();
        }
        return Response.accepted(response).build();
    }


    @Operation(summary = "V3. List registered physical databases",
            description = "Returns the list of registered physical databases by database type")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Registered physical databases by specific type were found.", content = @Content(schema = @Schema(implementation = RegisteredPhysicalDatabasesDTO.class))),
            @APIResponse(responseCode = "404", description = "Registered physical databases by specific type were not found.")})
    @GET
    public Response getRegisteredDatabases(@Parameter(description = "Type of database, for example: MongoDB, PostgreSQL, elasticsearch, etc. or all - to list all registered physical databases", required = true)
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
            @APIResponse(responseCode = "404", description = "Physical database with specific type and id was not found.")})
    @Path("/{phydbid}")
    @DELETE
    @Transactional
    public Response deletePhysicalDatabase(@Parameter(description = "Type of database, for example: MongoDB, PostgreSQL, elasticsearch, etc.", required = true)
                                           @PathParam("type") String type,
                                           @Parameter(description = "Physical database identifier. The value belongs to the specific database cluster", required = true)
                                           @PathParam("phydbid") String phydbid) {
        PhysicalDatabase databaseForDeletion = physicalDatabasesService.getByPhysicalDatabaseIdentifier(phydbid);
        if (databaseForDeletion == null) {
            log.error("No physical database of {} type  with phydbid {} is registered", type, phydbid);
            return Response.status(NOT_FOUND).entity("No physical database of type " + type + " with id " + phydbid + " is registered").build();
        }
        if (databaseForDeletion.isGlobal()) {
            log.error("Can't delete db {}. This database is global", phydbid);
            return Response.status(NOT_ACCEPTABLE).entity("Can't delete db " + phydbid + ". This database is global").build();
        }
        boolean isConnectedDbsExist = physicalDatabasesService.checkContainsConnectedLogicalDb(databaseForDeletion);
        if (isConnectedDbsExist) {
            log.error("Can't delete db {}. Connected logical databases still exist", phydbid);
            return Response.status(NOT_ACCEPTABLE).entity("Can't delete db " + phydbid + ". Connected logical databases still exist").build();
        }
        physicalDatabasesService.dropDatabase(databaseForDeletion);
        return Response.ok().build();
    }

    private Instruction recreateInstruction(PhysicalDatabaseInstruction existingInstruction, List<Database> logicalDatabases) {
        instructionService.deleteInstruction(String.valueOf(existingInstruction.getId()));
        Instruction instructionCreated = instructionService.buildInstructionForAdditionalRoles(logicalDatabases);
        return instructionCreated;
    }
}