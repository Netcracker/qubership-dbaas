package com.netcracker.cloud.dbaas.rest;

import com.netcracker.cloud.dbaas.dto.*;
import com.netcracker.cloud.dbaas.dto.v3.CreatedDatabaseV3;
import com.netcracker.cloud.dbaas.dto.v3.GetOrCreateUserAdapterRequest;
import com.netcracker.cloud.dbaas.dto.v3.UserEnsureRequestV3;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.BackupAdapterRequest;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.LogicalBackupAdapterResponse;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.LogicalRestoreAdapterResponse;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.RestoreAdapterRequest;
import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import com.netcracker.cloud.dbaas.entity.pg.backup.TrackedAction;
import com.netcracker.cloud.dbaas.monitoring.AdapterHealthStatus;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vertx.core.impl.NoStackTraceTimeoutException;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.faulttolerance.Retry;

import java.net.SocketTimeoutException;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

@OpenAPIDefinition(info = @Info(
        title = "DBaaS Adapter REST Client V2",
        version = "2.3",
        description = "REST API of Database as a Service Database Adapter component. DBaaS Adapter should adapt " +
                "database specific administration API to REST API supported by DBaaS Aggregator."),
        tags = {
                @Tag(name = "Backup & Restore", description = "Database Specific DBaaS Backup Controller V 2"),
                @Tag(name = "Common DBaaS adapter operations", description = "Allows to manage database."),
                @Tag(name = "User & Resource management", description = "Endpoints to manage resources and users."),
                @Tag(name = "Physical database manage", description = "Endpoints to manage physical database"),
                @Tag(name = "Support endpoints", description = "Endpoints to check adapter health and supported features")
        })
@Retry(delay = 1, delayUnit = ChronoUnit.SECONDS, maxRetries = 5,
        retryOn = {SocketTimeoutException.class, NoStackTraceTimeoutException.class})
public interface DbaasAdapterRestClientV2 extends AutoCloseable {

    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Support endpoints")
    @Operation(summary = "Health check",
            description = "Returns health status of the adapter.")
    @ApiResponse(responseCode = "200", description = "Adapter health status",
            content = @Content(schema = @Schema(implementation = AdapterHealthStatus.class)))
    AdapterHealthStatus getHealth();

    @GET
    @Path("/api/v2/dbaas/adapter/{type}/supports")
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Support endpoints")
    @Operation(summary = "Support info. V2",
            description = "This API describes what features are supported by the adapter.")
    @ApiResponse(responseCode = "200", description = "Adapter supports supporting endpoint, provided values would be used instead defaults",
            content = @Content(schema = @Schema(type = "object",
                    additionalProperties = Schema.AdditionalPropertiesValue.USE_ADDITIONAL_PROPERTIES_ANNOTATION,
                    additionalPropertiesSchema = Boolean.class)))
    Map<String, Boolean> supports(@Parameter(description = "Type of the database") @PathParam("type") String type);

    @GET
    @Path("/api/v2/dbaas/adapter/{type}/physical_database")
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Physical database manage")
    @Operation(summary = "Physical database information. V2",
            description = "Adapter belongs only one database cluster and sends own physical database information.")
    @ApiResponse(responseCode = "200", description = "Own physical database information",
            content = @Content(schema = @Schema(implementation = HandshakeResponse.class)))
    @ApiResponse(responseCode = "404", description = "Identifier is not specified")
    Response handshake(@Parameter(description = "Type of the database") @PathParam("type") String type);

    @GET
    @Path("/api/v2/adapter/physical_database")
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Physical database manage")
    @Operation(summary = "Get physical database information. V2",
            description = "Returns the physical database descriptor from the adapter.")
    @ApiResponse(responseCode = "200", description = "Physical database descriptor returned successfully.",
            content = @Content(schema = @Schema(implementation = PhysicalDatabaseInformation.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized.")
    @ApiResponse(responseCode = "404", description = "The adapter does not implement this endpoint." +
            " It returns 404 for every request to this path, so a 404 reports a missing endpoint.")
    @ApiResponse(responseCode = "503", description = "The adapter is temporarily unavailable.")
    Response getPhysicalDatabaseInformation();

    @GET
    @Path("/api/v2/dbaas/adapter/physical_database/force_registration")
    @Tag(name = "Physical database manage")
    @Operation(summary = "Force physical database registration. V2",
            description = "Force this adapter to immediately register itself in DBaaS-aggregator. Adapter initiates background task " +
                    "that tries to register physical database in DBaaS-aggregator, and responds with status 202 " +
                    "before the background task finishes.")
    @ApiResponse(responseCode = "202", description = "if physical database registration process has been started successfully.",
            content = @Content(schema = @Schema(type = "object")))
    Response forceRegistration();

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/backups/{backupId}/restoration")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Backup & Restore")
    @Operation(summary = "Restore backup. V2",
            description = "Requests database backup daemon to restore specified backup with specified databases info")
    @ApiResponse(responseCode = "202", description = "Restore requested",
            content = @Content(schema = @Schema(implementation = TrackedAction.class)))
    @ApiResponse(responseCode = "500", description = "Unknown error")
    @ApiResponse(responseCode = "501", description = "Cannot restore backup without explicitly specified list of databases in it")
    TrackedAction restoreBackup(@Parameter(description = "Type of the database") @PathParam("type") String type,
                                @Parameter(description = "Backup identifier") @PathParam("backupId") String backupId,
                                @RequestBody(required = true, description = "Request with the list of databases to restore",
                                        content = @Content(schema = @Schema(implementation = RestoreRequest.class),
                                                examples = @ExampleObject(value = RestoreRequest.EXAMPLE_REQUEST)))
                                RestoreRequest restoreRequest);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/backups/{backupId}/restore")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Backup & Restore")
    @Operation(summary = "Restore backup. V2", deprecated = true,
            description = "Requests database backup daemon to restore specified backup. " +
                    "Deprecated, please use /restoration endpoint instead")
    @ApiResponse(responseCode = "202", description = "Restore requested",
            content = @Content(schema = @Schema(implementation = TrackedAction.class)))
    @ApiResponse(responseCode = "500", description = "Unknown error")
    @ApiResponse(responseCode = "501", description = "Cannot restore backup without explicitly specified list of databases in it")
    TrackedAction restoreBackup(@Parameter(description = "Type of the database") @PathParam("type") String type,
                                @Parameter(description = "Backup identifier") @PathParam("backupId") String backupId,
                                @Parameter(description = "Defines if adapter should generate names for each restoring database, and restore databases under new names, " +
                                        "which would effectively \"clone\" databases from backup. This action MUST NOT affect any of \"source\" databases " +
                                        "whether they are present in cluster or not.")
                                @QueryParam("regenerateNames") boolean regenerateNames,
                                @RequestBody(description = "List of databases to restore",
                                        content = @Content(schema = @Schema(implementation = String[].class),
                                                examples = @ExampleObject(value = """
                                                        [
                                                            "inventory", "orders"
                                                        ]
                                                        """)))
                                List<String> databases);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/backups/collect")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Backup & Restore")
    @Operation(summary = "Collect backup. V2",
            description = "Requests database backup daemon to collect backup for specified databases")
    @ApiResponse(responseCode = "202", description = "Backup in progress",
            content = @Content(schema = @Schema(implementation = TrackedAction.class)))
    @ApiResponse(responseCode = "500", description = "Unknown error")
    TrackedAction collectBackup(@Parameter(description = "Type of the database") @PathParam("type") String type,
                                @Parameter(description = "Allows to disable eviction on the adapter for the current backup")
                                @QueryParam("allowEviction") Boolean allowEviction,
                                @Parameter(description = "Retention period of the collected backup")
                                @QueryParam("keep") String keep,
                                @RequestBody(required = true, description = "List of databases to collect backup for",
                                        content = @Content(schema = @Schema(implementation = String[].class),
                                                examples = @ExampleObject(value = """
                                                        [
                                                            "inventory", "orders"
                                                        ]
                                                        """)))
                                List<String> databases);

    @GET
    @Path("/api/v2/dbaas/adapter/{type}/backups/track/{action}/{trackId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Backup & Restore")
    @Operation(summary = "Track backup or restore. V2",
            description = "Returns status of backup or restore task")
    @ApiResponse(responseCode = "200", description = "Task track returned",
            content = @Content(schema = @Schema(implementation = TrackedAction.class)))
    @ApiResponse(responseCode = "404", description = "Not found backup or restore")
    @ApiResponse(responseCode = "500", description = "Unknown error")
    TrackedAction trackBackup(@Parameter(description = "Type of the database") @PathParam("type") String type,
                              @Parameter(description = "Type of action: backup or restore") @PathParam("action") String action,
                              @Parameter(description = "Track identifier of the backup or restore task") @PathParam("trackId") String trackId);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/backups/backup")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Backup & Restore")
    @Operation(summary = "Create logical backup. V2",
            description = "Requests the adapter to create a logical backup for the databases listed in the request")
    @ApiResponse(responseCode = "200", description = "Logical backup requested, response contains the backup status",
            content = @Content(schema = @Schema(implementation = LogicalBackupAdapterResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unknown error")
    LogicalBackupAdapterResponse backupV2(
            @Parameter(description = "Type of the database") @PathParam("type") String type,
            @RequestBody(required = true, description = "Request to create a logical backup",
                    content = @Content(schema = @Schema(implementation = BackupAdapterRequest.class),
                            examples = @ExampleObject(value = BackupAdapterRequest.EXAMPLE_REQUEST)))
            BackupAdapterRequest backupAdapterRequest);

    @GET
    @Path("/api/v2/dbaas/adapter/{type}/backups/backup/{backupId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Backup & Restore")
    @Operation(summary = "Track logical backup. V2",
            description = "Returns the status of a logical backup")
    @ApiResponse(responseCode = "200", description = "Logical backup status returned",
            content = @Content(schema = @Schema(implementation = LogicalBackupAdapterResponse.class)))
    @ApiResponse(responseCode = "404", description = "Backup not found")
    @ApiResponse(responseCode = "500", description = "Unknown error")
    LogicalBackupAdapterResponse trackBackupV2(
            @Parameter(description = "Type of the database") @PathParam("type") String type,
            @Parameter(description = "Logical backup name in the adapter") @PathParam("backupId") String logicalBackupName,
            @Parameter(description = "Storage name of the backup") @QueryParam("storageName") String storageName,
            @Parameter(description = "Path of the backup in the storage") @QueryParam("blobPath") String blobPath
    );

    @DELETE
    @Path("/api/v2/dbaas/adapter/{type}/backups/backup/{backupId}")
    @Tag(name = "Backup & Restore")
    @Operation(summary = "Delete logical backup. V2",
            description = "Deletes a logical backup")
    @ApiResponse(responseCode = "200", description = "Logical backup deleted")
    @ApiResponse(responseCode = "404", description = "Backup not found")
    @ApiResponse(responseCode = "500", description = "Unknown error")
    void deleteBackupV2(@Parameter(description = "Type of the database") @PathParam("type") String type,
                        @Parameter(description = "Logical backup name in the adapter") @PathParam("backupId") String logicalBackupName,
                        @Parameter(description = "Path of the backup in the storage") @QueryParam("blobPath") String blobPath);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/backups/backup/{backupId}/restore")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Backup & Restore")
    @Operation(summary = "Restore logical backup. V2",
            description = "Requests the adapter to restore a logical backup. " +
                    "With dryRun=true the restore is validated without applying changes")
    @ApiResponse(responseCode = "200", description = "Restore requested, response contains the restore status",
            content = @Content(schema = @Schema(implementation = LogicalRestoreAdapterResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unknown error")
    LogicalRestoreAdapterResponse restoreV2(
            @Parameter(description = "Type of the database") @PathParam("type") String type,
            @Parameter(description = "Logical backup name in the adapter") @PathParam("backupId") String logicalRestoreName,
            @Parameter(description = "Validate the restore without applying changes") @QueryParam("dryRun") Boolean dryRun,
            @RequestBody(required = true, description = "Request to restore a logical backup",
                    content = @Content(schema = @Schema(implementation = RestoreAdapterRequest.class),
                            examples = @ExampleObject(value = RestoreAdapterRequest.EXAMPLE_REQUEST)))
            RestoreAdapterRequest restoreAdapterRequest);

    @GET
    @Path("/api/v2/dbaas/adapter/{type}/backups/restore/{restoreId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Backup & Restore")
    @Operation(summary = "Track logical restore. V2",
            description = "Returns the status of a logical restore")
    @ApiResponse(responseCode = "200", description = "Logical restore status returned",
            content = @Content(schema = @Schema(implementation = LogicalRestoreAdapterResponse.class)))
    @ApiResponse(responseCode = "404", description = "Restore not found")
    @ApiResponse(responseCode = "500", description = "Unknown error")
    LogicalRestoreAdapterResponse trackRestoreV2(
            @Parameter(description = "Type of the database") @PathParam("type") String type,
            @Parameter(description = "Logical restore name in the adapter") @PathParam("restoreId") String logicalBackupName,
            @Parameter(description = "Storage name of the backup") @QueryParam("storageName") String storageName,
            @Parameter(description = "Path of the backup in the storage") @QueryParam("blobPath") String blobPath
    );

    @DELETE
    @Path("/api/v2/dbaas/adapter/{type}/backups/{backupId}")
    @Tag(name = "Backup & Restore")
    @Operation(summary = "Delete backup. V2",
            description = "Returns deletion status")
    @ApiResponse(responseCode = "200", description = "Successful delete",
            content = @Content(schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "404", description = "Not found backup")
    @ApiResponse(responseCode = "500", description = "Unknown error")
    String deleteBackup(@Parameter(description = "Type of the database") @PathParam("type") String type,
                        @Parameter(description = "Backup identifier") @PathParam("backupId") String backupId);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/resources/bulk-drop")
    @Consumes(MediaType.APPLICATION_JSON)
    @Tag(name = "User & Resource management")
    @Operation(summary = "Drop created resources. V2",
            description = "Can drop any previously created resources such as user or database")
    @ApiResponse(responseCode = "200", description = "Drop successful")
    Response dropResources(@Parameter(description = "Type of the database") @PathParam("type") String type,
                           @RequestBody(required = true, description = "List of resources to drop",
                                   content = @Content(schema = @Schema(implementation = DbResource[].class),
                                           examples = @ExampleObject(value = """
                                                   [
                                                     { "kind": "database", "name": "inventory" },
                                                     { "kind": "user", "name": "svc_inventory_rw" }
                                                   ]""")))
                           List<DbResource> resources);

    @PUT
    @Path("/api/v2/dbaas/adapter/{type}/users/{username}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(hidden = true)
    @Tag(name = "User & Resource management")
    EnsuredUser ensureUser(@PathParam("type") String type, @PathParam("username") String username,
                           UserEnsureRequest request);

    @PUT
    @Path("/api/v2/dbaas/adapter/{type}/users/{username}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "User & Resource management")
    @Operation(summary = "Create user. V2",
            description = "Creates new user for specified database and returns it with connection information, " +
                    "or returns already created user if it exists.")
    @ApiResponse(responseCode = "201", description = "User created",
            content = @Content(schema = @Schema(implementation = EnsuredUser.class)))
    @ApiResponse(responseCode = "500", description = "Error occurred while user creation.")
    EnsuredUser ensureUser(@Parameter(description = "Type of the database") @PathParam("type") String type,
                           @Parameter(description = "The username for creating user to database") @PathParam("username") String username,
                           @RequestBody(required = true, description = "Info about user to ensure",
                                   content = @Content(schema = @Schema(oneOf = {UserEnsureRequest.class, UserEnsureRequestV3.class}),
                                           examples = @ExampleObject(value = UserEnsureRequestV3.EXAMPLE_REQUEST)))
                           UserEnsureRequestV3 request);

    @PUT
    @Path("/api/v2/dbaas/adapter/{type}/users")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "User & Resource management")
    @Operation(summary = "Create user. V2",
            description = "Creates new user for specified database and returns it with connection information, " +
                    "or returns already created user if it exists. " +
                    "Adapter should generate new username and create new user with it.")
    @ApiResponse(responseCode = "201", description = "User created",
            content = @Content(schema = @Schema(implementation = EnsuredUser.class)))
    @ApiResponse(responseCode = "500", description = "Error occurred while user creation.")
    EnsuredUser ensureUser(@Parameter(description = "Type of the database") @PathParam("type") String type,
                           @RequestBody(required = true, description = "Info about user to create",
                                   content = @Content(schema = @Schema(oneOf = {UserEnsureRequestV3.class, GetOrCreateUserAdapterRequest.class}),
                                           examples = @ExampleObject(value = GetOrCreateUserAdapterRequest.EXAMPLE_REQUEST)))
                           UserEnsureRequestV3 request);

    @PUT
    @Path("/api/v2/dbaas/adapter/{type}/users")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(hidden = true)
    @Tag(name = "User & Resource management")
    EnsuredUser createUser(@PathParam("type") String type,
                           GetOrCreateUserAdapterRequest request);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/users/restore-password")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "User & Resource management")
    @Operation(summary = "Restore users passwords. V2",
            description = "Starts passwords restoration process.")
    @ApiResponse(responseCode = "202", description = "Password restoration process has started",
            content = @Content(schema = @Schema(type = "string")))
    Response restorePassword(@Parameter(description = "Type of the database") @PathParam("type") String type,
                             @RequestBody(required = true, description = "Request to restore passwords",
                                     content = @Content(schema = @Schema(implementation = RestorePasswordsAdapterRequest.class),
                                             examples = @ExampleObject(value = RestorePasswordsAdapterRequest.EXAMPLE_REQUEST)))
                             RestorePasswordsAdapterRequest request);

    @PUT
    @Path("/api/v2/dbaas/adapter/{type}/databases/{dbName}/metadata")
    @Consumes(MediaType.APPLICATION_JSON)
    @Tag(name = "Common DBaaS adapter operations")
    @Operation(summary = "Update database metadata. V2",
            description = "Changes metadata saved in database")
    @ApiResponse(responseCode = "200", description = "Update metadata successful",
            content = @Content(schema = @Schema(type = "object")))
    @ApiResponse(responseCode = "500", description = "Error occurred while update metadata.")
    void changeMetaData(@Parameter(description = "Type of the database") @PathParam("type") String type,
                        @Parameter(description = "Name of the database") @PathParam("dbName") String dbName,
                        @RequestBody(required = true, description = "Metadata to save in database",
                                content = @Content(schema = @Schema(type = "object",
                                        additionalProperties = Schema.AdditionalPropertiesValue.USE_ADDITIONAL_PROPERTIES_ANNOTATION,
                                        additionalPropertiesSchema = Object.class),
                                        examples = @ExampleObject(value = """
                                                {
                                                  "classifier": {
                                                    "microserviceName": "inventory-service",
                                                    "namespace": "payments"
                                                  },
                                                  "microserviceName": "inventory-service"
                                                }""")))
                        Map<String, Object> metadata);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/describe/databases")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Common DBaaS adapter operations")
    @Operation(summary = "Describe databases. V2",
            description = "Returns info about requested databases, this is optional API")
    @ApiResponse(responseCode = "200", description = "Map databases names to description",
            content = @Content(schema = @Schema(type = "object",
                    additionalProperties = Schema.AdditionalPropertiesValue.USE_ADDITIONAL_PROPERTIES_ANNOTATION,
                    additionalPropertiesSchema = DescribedDatabase.class)))
    @ApiResponse(responseCode = "500", description = "Error occurred while databases describe.")
    Map<String, DescribedDatabase> describeDatabases(
            @Parameter(description = "Type of the database") @PathParam("type") String type,
            @Parameter(description = "If present - should return connection properties of each requested database")
            @QueryParam("connectionProperties") boolean connectionProperties,
            @Parameter(description = "If present - should return resources of each requested database")
            @QueryParam("resources") boolean resources,
            @RequestBody(required = true, description = "List of names of databases to describe",
                    content = @Content(schema = @Schema(implementation = String[].class),
                            examples = @ExampleObject(value = """
                                        [
                                            "inventory", "orders"
                                        ]""")))
            Collection<String> databases);

    @GET
    @Path("/api/v2/dbaas/adapter/{type}/databases")
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Common DBaaS adapter operations")
    @Operation(summary = "List databases. V2",
            description = "Returns list with names of databases")
    @ApiResponse(responseCode = "200", description = "Databases listed",
            content = @Content(schema = @Schema(implementation = String[].class)))
    @ApiResponse(responseCode = "500", description = "Error occurred while databases listing.")
    Set<String> getDatabases(@Parameter(description = "Type of the database") @PathParam("type") String type);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/databases")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = "Common DBaaS adapter operations")
    @Operation(summary = "Create database. V2",
            description = "Creates database and user with permissions to read and write to the database. " +
                    "The operation returns connection parameters including credentials.")
    @ApiResponse(responseCode = "201", description = "Database created",
            content = @Content(schema = @Schema(implementation = CreatedDatabaseV3.class)))
    @ApiResponse(responseCode = "400", description = "Provided namePrefix does not meet the requirements")
    CreatedDatabaseV3 createDatabase(@Parameter(description = "Type of the database") @PathParam("type") String type,
                                     @RequestBody(required = true, description = "Request to create database",
                                             content = @Content(schema = @Schema(implementation = AdapterDatabaseCreateRequest.class),
                                                     examples = @ExampleObject(value = AdapterDatabaseCreateRequest.EXAMPLE_REQUEST)))
                                     AdapterDatabaseCreateRequest createRequest);

    @PUT
    @Path("/api/v2/dbaas/adapter/{type}/databases/{dbName}/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Tag(name = "Common DBaaS adapter operations")
    @Operation(summary = "Update database settings. V2",
            description = "Returns information about update status.")
    @ApiResponse(responseCode = "200", description = "Settings updated",
            content = @Content(schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "404", description = "Old settings for db with provided dbName not found")
    String updateSettings(@Parameter(description = "Type of the database") @PathParam("type") String type,
                          @Parameter(description = "Name of the database") @PathParam("dbName") String dbName,
                          @RequestBody(required = true, description = "Contains current and new settings",
                                  content = @Content(schema = @Schema(implementation = UpdateSettingsAdapterRequest.class),
                                          examples = @ExampleObject(value = UpdateSettingsAdapterRequest.EXAMPLE_REQUEST)))
                          UpdateSettingsAdapterRequest request);
}
