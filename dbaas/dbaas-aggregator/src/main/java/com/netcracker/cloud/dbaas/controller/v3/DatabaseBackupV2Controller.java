package com.netcracker.cloud.dbaas.controller.v3;

import com.netcracker.cloud.core.error.rest.tmf.TmfErrorResponse;
import com.netcracker.cloud.dbaas.dto.Source;
import com.netcracker.cloud.dbaas.dto.backupV2.*;
import com.netcracker.cloud.dbaas.enums.BackupStatus;
import com.netcracker.cloud.dbaas.enums.RestoreStatus;
import com.netcracker.cloud.dbaas.exceptions.ForbiddenDeleteOperationException;
import com.netcracker.cloud.dbaas.exceptions.IntegrityViolationException;
import com.netcracker.cloud.dbaas.service.DbBackupV2Service;
import com.netcracker.cloud.dbaas.service.DbaaSHelper;
import com.netcracker.cloud.dbaas.utils.DigestUtil;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.annotations.Separator;

import java.util.Set;

import static com.netcracker.cloud.dbaas.Constants.BACKUP_MANAGER;
import static com.netcracker.cloud.dbaas.DbaasApiPath.BACKUP_PATH_V1;


@Slf4j
@Path(BACKUP_PATH_V1)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Backup & Restore", description = "Backup & Restore operations for DBaaS")
@RolesAllowed(BACKUP_MANAGER)
public class DatabaseBackupV2Controller {

    private final DbBackupV2Service dbBackupV2Service;
    private final DbaaSHelper dbaaSHelper;

    @Inject
    public DatabaseBackupV2Controller(DbBackupV2Service dbBackupV2Service, DbaaSHelper dbaaSHelper) {
        this.dbBackupV2Service = dbBackupV2Service;
        this.dbaaSHelper = dbaaSHelper;
    }

    @Operation(summary = "Initiate database backup",
            description = "Starts an asynchronous backup operation for the specified databases."
                    + " Returns immediately with a backup name that can be used to track progress.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Backup operation completed successfully",
                    content = @Content(schema = @Schema(implementation = BackupResponse.class))),
            @APIResponse(responseCode = "202", description = "Backup operation initiated successfully",
                    content = @Content(schema = @Schema(implementation = BackupResponse.class))),
            @APIResponse(responseCode = "400", description = "The request was invalid or cannot be served",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "409", description = "The request could not be completed due to a conflict with the current state of the resource",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "422", description = "The request was accepted, but the server couldn't process due to incompatible resource",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class)))
    })
    @Path("/backup")
    @POST
    public Response initiateBackup(@RequestBody(description = "Backup request") @Valid BackupRequest backupRequest,
                                   @QueryParam("dryRun") @DefaultValue("false") boolean dryRun) {
        log.info("Request to start backup with backup request {}, with dryRun mode {}", backupRequest, dryRun);
        BackupResponse response = dbBackupV2Service.backup(backupRequest, dryRun);
        BackupStatus status = response.getStatus();
        if (status == BackupStatus.COMPLETED || status == BackupStatus.FAILED)
            return Response.ok(response).build();
        return Response.accepted(response).build();
    }

    @Operation(summary = "Get backup details", description = "Retrieve details about a specific backup operation")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Backup details retrieved successfully",
                    content = @Content(schema = @Schema(implementation = BackupResponse.class))),
            @APIResponse(responseCode = "400", description = "The request was invalid or cannot be served",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class)))
    })
    @Path("/backup/{backupName}")
    @GET
    public Response getBackup(@Parameter(description = "Unique name of the backup", required = true)
                              @PathParam("backupName")
                              @NotBlank String backupName) {
        log.info("Request to get backup {}", backupName);
        return Response.ok(dbBackupV2Service.getBackup(backupName)).build();
    }

    @Operation(summary = "Delete backup", description = "Delete the backup operation")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Backup delete initialized successfully"),
            @APIResponse(responseCode = "204", description = "Backup deleted successfully"),
            @APIResponse(responseCode = "400", description = "The request was invalid or cannot be served",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "422", description = "The request was accepted, but the server couldn't process due to incompatible resource",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class)))
    })
    @Path("/backup/{backupName}")
    @DELETE
    public Response deleteBackup(@Parameter(description = "Unique name of the backup", required = true)
                                 @PathParam("backupName") @NotBlank String backupName,
                                 @QueryParam("force") @DefaultValue("false") boolean force) {
        log.info("Request to delete backup {} with flag force {}", backupName, force);
        dbBackupV2Service.deleteBackup(backupName, force);
        if (force)
            return Response.accepted().build();
        return Response.noContent().build();
    }

    @Operation(summary = "Get backup status", description = "Retrieve the current status of a backup operation")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Backup status retrieved successfully",
                    content = @Content(schema = @Schema(implementation = BackupStatusResponse.class))),
            @APIResponse(responseCode = "400", description = "The request was invalid or cannot be served",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class)))
    })
    @Path("/backup/{backupName}/status")
    @GET
    public Response getBackupStatus(@Parameter(description = "Unique name of the backup", required = true)
                                    @PathParam("backupName")
                                    @NotBlank String backupName) {
        log.info("Request to get backup status {}", backupName);
        return Response.ok(dbBackupV2Service.getCurrentStatus(backupName)).build();
    }

    @Operation(summary = "Get backup metadata", description = "Retrieve metadata about a completed backup")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Backup metadata retrieved successfully",
                    headers = {
                            @Header(
                                    name = "Digest",
                                    description = "Digest header with SHA-256 checksum of the response body",
                                    schema = @Schema(
                                            type = SchemaType.STRING,
                                            examples = {
                                                    "SHA-256=abc123..."
                                            })
                            )
                    },
                    content = @Content(schema = @Schema(implementation = BackupResponse.class))
            ),
            @APIResponse(responseCode = "400", description = "The request was invalid or cannot be served",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "422", description = "The request was accepted, but the server couldn't process due to incompatible resource",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class)))
    })
    @Path("/backup/{backupName}/metadata")
    @GET
    public Response getBackupMetadata(@Parameter(description = "Unique name of the backup", required = true)
                                      @PathParam("backupName")
                                      @NotBlank String backupName) {
        log.info("Request to get backup metadata {}", backupName);
        BackupResponse response = dbBackupV2Service.getBackupMetadata(backupName);
        String digestHeader = DigestUtil.calculateDigest(response);
        return Response.ok(response)
                .header("Digest", digestHeader)
                .build();
    }

    @Operation(summary = "Upload backup metadata", description = "Upload backup metadata")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Backup metadata uploaded successfully"),
            @APIResponse(responseCode = "400", description = "The request was invalid or cannot be served",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "409", description = "The request could not be completed due to a conflict with the current state of the resource",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class)))
    })
    @Path("/operation/uploadMetadata")
    @POST
    public Response uploadMetadata(
            @Parameter(
                    name = "Digest",
                    description = "Digest header in format: SHA-256=<base64-hash>",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(
                            type = SchemaType.STRING,
                            examples = {
                                    "SHA-256=nOJRJg..."
                            }))
            @HeaderParam("Digest") @NotNull String digestHeader,
            @RequestBody(description = "Backup metadata") @Valid BackupResponse backupResponse
    ) {
        log.info("Request to upload backup metadata {}", backupResponse);
        log.debug("Backup digest {}", digestHeader);
        String calculatedDigest = DigestUtil.calculateDigest(backupResponse);
        if (!calculatedDigest.equals(digestHeader))
            throw new IntegrityViolationException(
                    String.format("expected digest %s but got %s", calculatedDigest, digestHeader),
                    Source.builder().build());
        backupResponse.setDigest(calculatedDigest);
        dbBackupV2Service.uploadBackupMetadata(backupResponse);
        return Response.ok().build();
    }

    @Operation(summary = "Restore from backup", description = "Initiate a database restore operation from an existing backup." +
            "This operation is asynchronous and returns immediately with a restore name that can be used to track progress." +
            "Operation is not idempotent")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Restore operation initiated successfully",
                    content = @Content(schema = @Schema(implementation = RestoreResponse.class))),
            @APIResponse(responseCode = "400", description = "The request was invalid or cannot be served",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "409", description = "The request could not be completed due to a conflict with the current state of the resource",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "422", description = "The request was accepted, but the server couldn't process due to incompatible resource",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class)))
    })
    @Path("/backup/{backupName}/restore")
    @POST
    public Response restoreBackup(@Parameter(description = "Unique name of the backup", required = true)
                                  @PathParam("backupName") @NotBlank String backupName,
                                  @RequestBody(description = "Restore request")
                                  @Valid RestoreRequest restoreRequest,
                                  @QueryParam("dryRun") @DefaultValue("false") boolean dryRun) {
        log.info("Request to restore backup {}, restore request {}, dryRun mode {}", backupName, restoreRequest, dryRun);
        return restore(backupName, restoreRequest, dryRun, false);
    }

    @Operation(
            summary = "Restore from backup with parallel execution allowed",
            description = "Only for internal usage",
            hidden = true
    )
    @Path("/backup/{backupName}/restore/allowParallel")
    @POST
    public Response restoreBackupAllowParallel(@Parameter(description = "Unique name of the backup", required = true)
                                               @PathParam("backupName") @NotBlank String backupName,
                                               @RequestBody(description = "Restore request")
                                               @Valid RestoreRequest restoreRequest,
                                               @QueryParam("dryRun") @DefaultValue("false") boolean dryRun) {
        log.info("Request to restore backup with parallel execution allowed," +
                " backup name {}, restore request {}, dryRun mode {}", backupName, restoreRequest, dryRun);
        if (dbaaSHelper.isProductionMode()) {
            throw new ForbiddenDeleteOperationException();
        }
        return restore(backupName, restoreRequest, dryRun, true);
    }

    private Response restore(String backupName, RestoreRequest restoreRequest, boolean dryRun, boolean allowParallel) {
        RestoreResponse response = dbBackupV2Service.restore(backupName, restoreRequest, dryRun, allowParallel);
        RestoreStatus status = response.getStatus();
        if (status == RestoreStatus.COMPLETED || status == RestoreStatus.FAILED)
            return Response.ok(response).build();
        return Response.accepted(response).build();
    }

    @Operation(summary = "Get restore details", description = "Retrieve details about a specific restore operation")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Restore details retrieved successfully",
                    content = @Content(schema = @Schema(implementation = RestoreResponse.class))),
            @APIResponse(responseCode = "400", description = "The request was invalid or cannot be served",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class)))
    })
    @Path("/restore/{restoreName}")
    @GET
    public Response getRestore(@Parameter(description = "Unique name of the restore operation", required = true)
                               @PathParam("restoreName")
                               @NotBlank String restoreName) {
        log.info("Request to get restore {}", restoreName);
        return Response.ok(dbBackupV2Service.getRestore(restoreName)).build();
    }

    @Operation(summary = "Delete restore", description = "Delete a restore operation")
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Restore operation deleted successfully"),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "422", description = "The request was accepted, but the server couldn't process due to incompatible resource",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class)))
    })
    @Path("/restore/{restoreName}")
    @DELETE
    public Response deleteRestore(@Parameter(description = "Unique name of the restore operation", required = true)
                                  @PathParam("restoreName")
                                  @NotBlank String restoreName) {
        log.info("Request to delete restore {}", restoreName);
        dbBackupV2Service.deleteRestore(restoreName);
        return Response.noContent().build();
    }

    @Operation(summary = "Get restore status", description = "Retrieve the current status of a restore operation")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Restore status retrieved successfully",
                    content = @Content(schema = @Schema(implementation = RestoreStatusResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class)))
    })
    @Path("/restore/{restoreName}/status")
    @GET
    public Response getRestoreStatus(@Parameter(description = "Unique name of the restore operation", required = true)
                                     @PathParam("restoreName")
                                     @NotBlank String restoreName) {
        log.info("Request to get restore status {}", restoreName);
        return Response.ok(dbBackupV2Service.getRestoreStatus(restoreName)).build();
    }

    @Operation(summary = "Retry restore", description = "Retry a failed restore operation")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Restore retry accepted and is being processed",
                    content = @Content(schema = @Schema(implementation = RestoreResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "409", description = "The request could not be completed due to a conflict with the current state of the resource",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "422", description = "The request was accepted, but the server couldn't process due to incompatible resource",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server",
                    content = @Content(schema = @Schema(implementation = TmfErrorResponse.class)))
    })
    @Path("/restore/{restoreName}/retry")
    @POST
    public Response retryRestore(@Parameter(description = "Unique name of the restore operation", required = true)
                                 @PathParam("restoreName")
                                 @NotBlank String restoreName) {
        log.info("Request to retry restore {}", restoreName);
        return Response.accepted(dbBackupV2Service.retryRestore(restoreName, false)).build();
    }

    @Operation(
            summary = "Retry restore with parallel execution allowed",
            description = "Only for internal usage",
            hidden = true
    )
    @Path("/restore/{restoreName}/retry/allowParallel")
    @POST
    public Response retryRestoreAllowParallel(@Parameter(description = "Unique name of the restore operation", required = true)
                                              @PathParam("restoreName")
                                              @NotBlank String restoreName) {
        log.info("Request to retry restore with parallel execution alllowed, restore name {}", restoreName);
        if (dbaaSHelper.isProductionMode()) {
            throw new ForbiddenDeleteOperationException();
        }
        return Response.accepted(dbBackupV2Service.retryRestore(restoreName, true)).build();
    }

    @Operation(summary = "Remove backup",
            description = "Deleting a backup entirely from DB by the specified backup name. Only for internal usage.",
            hidden = true
    )
    @Path("/backup/{backupName}/forceDelete")
    @DELETE
    public Response deleteBackupFromDb(@Parameter(description = "Unique name of the backup operation", required = true)
                                       @PathParam("backupName")
                                       @NotBlank String backupName
    ) {
        log.info("Request to delete backup from db, backup name {}", backupName);
        if (dbaaSHelper.isProductionMode()) {
            throw new ForbiddenDeleteOperationException();
        }
        dbBackupV2Service.deleteBackupFromDb(backupName);
        return Response.noContent().build();
    }

    @Operation(summary = "Remove all backup by names",
            description = "Find by names all backup and delete entirely ",
            hidden = true
    )
    @DELETE
    @Path("/backup/deleteAll")
    public Response deleteAllBackupByNames(@QueryParam("backupNames")
                                                   @NotNull @Separator(",") Set<String> backupNames) {
        log.info("Request to delete backups by names={}", backupNames);
        if (dbaaSHelper.isProductionMode()) {
            throw new ForbiddenDeleteOperationException();
        }
        dbBackupV2Service.deleteAllBackupByBackupNames(backupNames);
        return Response.noContent().build();
    }

    @Operation(summary = "Remove all restore by names",
            description = "Find by names all restores and delete entirely ",
            hidden = true
    )
    @DELETE
    @Path("/restore/deleteAll")
    public Response deleteAllRestoreByNames(@QueryParam("restoreNames")
                                               @NotNull @Separator(",") Set<String> restoreNames) {
        log.info("Request to delete restore by names={}", restoreNames);
        if (dbaaSHelper.isProductionMode()) {
            throw new ForbiddenDeleteOperationException();
        }
        dbBackupV2Service.deleteAllRestoreByRestoreNames(restoreNames);
        return Response.noContent().build();
    }

    @Operation(summary = "Get all backup names",
            description = "Find all backup names",
            hidden = true
    )
    @GET
    @Path("/backup/getAllBackupNames")
    public Response getAllBackupNames() {
        log.info("Request to get all backup names");
        if (dbaaSHelper.isProductionMode()) {
            throw new ForbiddenDeleteOperationException();
        }
        return Response.ok(dbBackupV2Service.getAllBackupNames()).build();
    }

    @Operation(summary = "Get all restore names",
            description = "Find all restore names",
            hidden = true
    )
    @GET
    @Path("/restore/getAllRestoreNames")
    public Response getAllRestoreNames() {
        log.info("Request to get all restore names");
        if (dbaaSHelper.isProductionMode()) {
            throw new ForbiddenDeleteOperationException();
        }
        return Response.ok(dbBackupV2Service.getAllRestoresNames()).build();
    }
}
