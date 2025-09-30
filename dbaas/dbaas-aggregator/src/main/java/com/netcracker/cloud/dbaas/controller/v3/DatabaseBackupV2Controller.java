package com.netcracker.cloud.dbaas.controller.v3;

import com.netcracker.cloud.dbaas.dto.Source;
import com.netcracker.cloud.dbaas.dto.backupV2.*;
import com.netcracker.cloud.dbaas.exceptions.ErrorCodes;
import com.netcracker.cloud.dbaas.exceptions.RequestValidationException;
import com.netcracker.cloud.dbaas.service.DbBackupV2Service;
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

import static com.netcracker.cloud.dbaas.Constants.BACKUP_MANAGER;
import static com.netcracker.cloud.dbaas.DbaasApiPath.BACKUP_PATH_V1;


@Slf4j
@Path(BACKUP_PATH_V1)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Backup & Restore", description = "Backup & Restore operations for DBaaS")
@RolesAllowed(BACKUP_MANAGER)
public class DatabaseBackupV2Controller {

    private final DbBackupV2Service dbBackupV2Service;

    @Inject
    public DatabaseBackupV2Controller(DbBackupV2Service dbBackupV2Service) {
        this.dbBackupV2Service = dbBackupV2Service;
    }

    @Operation(summary = "Initiate database backup",
            description = "Starts an asynchronous backup operation for the specified databases."
                    + " Returns immediately with a backup identifier that can be used to track progress.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Backup operation completed successfully",
                    content = @Content(schema = @Schema(implementation = BackupOperationResponse.class))),
            @APIResponse(responseCode = "202", description = "Backup operation initiated successfully",
                    content = @Content(schema = @Schema(implementation = BackupOperationResponse.class))),
            @APIResponse(responseCode = "400", description = "The request was invalid or cannot be served"),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found"),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server")
    })
    @Path("/operation/backup")
    @POST
    public Response initiateBackup(@RequestBody(description = "Backup request", required = true) @Valid BackupRequest backupRequest, @QueryParam("dryRun") @DefaultValue("false") boolean dryRun) {

        if (dryRun)
            return Response.status(Response.Status.NOT_IMPLEMENTED)
                    .entity("Dry-run mode is not implemented yet")
                    .build();
        return Response.ok(dbBackupV2Service.backup(backupRequest, dryRun)).build();
    }

    @Operation(summary = "Get backup details", description = "Retrieve details about a specific backup operation")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Backup details retrieved successfully",
                    content = @Content(schema = @Schema(implementation = BackupResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found"),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server")
    })
    @Path("/backup/{backupName}")
    @POST
    public Response getBackup(@Parameter(description = "Unique identifier of the backup", required = true) @PathParam("backupName") String backupName) {
        return Response.ok(dbBackupV2Service.getBackup(backupName)).build();
    }

    @Operation(summary = "Delete backup", description = "Delete the backup operation")
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Backup deleted successfully"),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found"),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server")
    })
    @Path("/backup/{backupName}")
    @DELETE
    public Response deleteBackup(@Parameter(description = "Unique identifier of the backup", required = true)
                                 @PathParam("backupName") String backupName,
                                 @QueryParam("force") @DefaultValue("false") boolean force) {
        dbBackupV2Service.deleteBackup(backupName, force);
        return Response.noContent().build();
    }

    @Operation(summary = "Get backup status", description = "Retrieve the current status of a backup operation")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Backup status retrieved successfully",
                    content = @Content(schema = @Schema(implementation = BackupStatusResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found"),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server")
    })
    @Path("/backup/{backupName}/status")
    @GET
    public Response getBackupStatus(@Parameter(description = "Unique identifier of the backup", required = true)
                                    @PathParam("backupName")
                                    @NotBlank String backupName) {
        BackupStatusResponse backupStatusResponse = dbBackupV2Service.getCurrentStatus(backupName);
        return Response.ok(backupStatusResponse).build();
    }

    @Operation(summary = "Get backup metadata", description = "Retrieve metadata about a completed backup")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Backup metadata retrieved successfully",
                    headers = {
                            @Header(
                                    name = "Digest",
                                    description = "Digest header with SHA-256 checksum of the response body",
                                    schema = @Schema(type = SchemaType.STRING, example = "SHA-256=abc123...")
                            )
                    },
                    content = @Content(schema = @Schema(implementation = BackupResponse.class))
            ),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found"),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server")
    })
    @Path("/backup/{backupName}/metadata")
    @GET
    public Response getBackupMetadata(@Parameter(description = "Unique identifier of the backup", required = true)
                                      @PathParam("backupName")
                                      @NotBlank String backupName) {
        BackupResponse response = dbBackupV2Service.getBackup(backupName);
        String digestHeader = DigestUtil.calculateDigest(response);
        return Response.ok(response)
                .header("Digest", digestHeader)
                .build();
    }

    @Operation(summary = "Upload backup metadata", description = "Metadata upload done")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Backup metadata uploaded successfully"),
            @APIResponse(responseCode = "400", description = "The request was invalid or cannot be served"),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server")
    })
    @Path("/operation/uploadMetadata")
    @POST
    public Response uploadMetadata(
            @Parameter(
                    name = "Digest",
                    description = "Digest header in format: sha-256=<base64-hash>",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = SchemaType.STRING, example = "sha-256=nOJRJg..."))
            @HeaderParam("Digest") @NotNull String digestHeader,
            @RequestBody(description = "Backup metadata", required = true) @Valid BackupResponse backupResponse
    ) {
        String calculatedDigest = DigestUtil.calculateDigest(backupResponse);
        if (!calculatedDigest.equals(digestHeader))
            throw new RequestValidationException(ErrorCodes.CORE_DBAAS_7003, "Digest header mismatch.", Source.builder().build());

        dbBackupV2Service.uploadBackupMetadata(backupResponse);
        return Response.ok().build();
    }

    @Operation(summary = "Restore from backup", description = "Initiate a database restore operation from an existing backup." +
            "This operation is asynchronous and returns immediately with a restore identifier that can be used to track progress." +
            "Operation is not idempotent")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Restore operation completed successfully",
                    content = @Content(schema = @Schema(implementation = RestoreResponse.class))),
            @APIResponse(responseCode = "202", description = "Restore operation initiated successfully",
                    content = @Content(schema = @Schema(implementation = RestoreResponse.class))),
            @APIResponse(responseCode = "400", description = "The request was invalid or cannot be served"),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found"),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server")
    })
    @Path("/restore/{backupName}")
    @POST
    public Response restoreBackup(@Parameter(description = "Unique identifier of the backup", required = true)
                                  @PathParam("backupName")
                                  @NotBlank String backupName,
                                  @RequestBody(description = "Restore request", required = true) RestoreRequest restoreRequest) {
        return Response.ok().build();
    }

    @Operation(summary = "Get restore details", description = "Retrieve details about a specific restore operation")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Restore details retrieved successfully",
                    content = @Content(schema = @Schema(implementation = RestoreResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found"),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server")
    })
    @Path("/restore/{restoreName}")
    @GET
    public Response getRestore(@Parameter(description = "Unique identifier of the restore operation", required = true) @PathParam("restoreName") String restoreName) {
        return Response.ok().build();
    }

    @Operation(summary = "Delete restore", description = "Delete a restore operation")
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Restore operation deleted successfully"),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found"),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server")
    })
    @Path("/restore/{restoreName}")
    @DELETE
    public Response deleteRestore(@Parameter(description = "Unique identifier of the restore operation", required = true) @PathParam("restoreName") String restoreName) {
        return Response.noContent().build();
    }

    @Operation(summary = "Get restore status", description = "Retrieve the current status of a restore operation")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Restore status retrieved successfully",
                    content = @Content(schema = @Schema(implementation = RestoreStatusResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found"),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server")
    })
    @Path("/restore/{restoreName}/status")
    @GET
    public Response getRestoreStatus(@Parameter(description = "Unique identifier of the restore operation", required = true) @PathParam("restoreName") String restoreName) {
        return Response.ok().build();
    }

    @Operation(summary = "Retry restore", description = "Retry a failed restore operation")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Restore operation retried successfully",
                    content = @Content(schema = @Schema(implementation = RestoreResponse.class))),
            @APIResponse(responseCode = "202", description = "Restore retry accepted and is being processed",
                    content = @Content(schema = @Schema(implementation = RestoreResponse.class))),
            @APIResponse(responseCode = "401", description = "Authentication is required and has failed or has not been provided"),
            @APIResponse(responseCode = "403", description = "The request was valid, but the server is refusing action"),
            @APIResponse(responseCode = "404", description = "The requested resource could not be found"),
            @APIResponse(responseCode = "500", description = "An unexpected error occurred on the server")
    })
    @Path("/restore/{restoreName}/retry")
    @POST
    public Response retryRestore(@Parameter(description = "Unique identifier of the restore operation", required = true) @PathParam("restoreName") String restoreName) {
        return Response.ok().build();
    }
}
