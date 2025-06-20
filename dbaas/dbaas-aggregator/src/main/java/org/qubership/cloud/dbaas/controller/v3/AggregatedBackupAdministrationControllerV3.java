package org.qubership.cloud.dbaas.controller.v3;

import org.qubership.cloud.context.propagation.core.ContextManager;
import org.qubership.cloud.dbaas.dto.NamespaceBackupDTO;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import org.qubership.cloud.dbaas.dto.backup.NamespaceBackupDeletion;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceRestoration;
import org.qubership.cloud.dbaas.exceptions.*;
import org.qubership.cloud.dbaas.repositories.dbaas.BackupsDbaasRepository;
import org.qubership.cloud.dbaas.service.AsyncOperations;
import org.qubership.cloud.dbaas.service.DBBackupsService;
import org.qubership.cloud.dbaas.service.DbaasAdapter;
import org.qubership.cloud.framework.contexts.xrequestid.XRequestIdContextObject;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.qubership.cloud.dbaas.Constants.BACKUP_MANAGER;
import static org.qubership.cloud.dbaas.DbaasApiPath.BACKUPS_PATH_V3;
import static org.qubership.cloud.dbaas.DbaasApiPath.DBAAS_PATH_V3;
import static org.qubership.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;
import static org.qubership.cloud.framework.contexts.xrequestid.XRequestIdContextObject.X_REQUEST_ID;

@Slf4j
@Path(BACKUPS_PATH_V3)
@Produces(MediaType.APPLICATION_JSON)

@Tag(name = "Backups administration", description = "Allows to get a list of available backups, triggers " +
        "backup collector and restores some specific backup. All backup management is per namespace.")
@RolesAllowed(BACKUP_MANAGER)
public class AggregatedBackupAdministrationControllerV3 {

    public static final String DBAAS_PATH = DBAAS_PATH_V3;
    private static final String BACKUPS = "/backups";

    @ConfigProperty(name = "backup.aggregator.async.await.seconds", defaultValue = "10")
    int awaitOperationSeconds;

    @Inject
    AsyncOperations asyncOperations;
    @Inject
    BackupsDbaasRepository backupsDbaasRepository;
    @Inject
    DBBackupsService dbBackupsService;

    @Operation(summary = "V3. Get all backups of namespace",
            description = "Lists all backups prepared for specified namespace")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Successfully returned backups.")})
    @GET
    public Response getAllBackupsInNamespace(@PathParam(NAMESPACE_PARAMETER) String namespace) {
        return Response.ok(backupsDbaasRepository.findByNamespace(namespace)).build();
    }

    @Operation(summary = "V3. Restore namespace",
            description = "Restores database within the initial namespace which was used during backup or to another namespace")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Namespace restoration started, return backup id to track status"),
            @APIResponse(responseCode = "400", description = "Selected backup cannot be restored"),
            @APIResponse(responseCode = "404", description = "Selected backup not found"),
            @APIResponse(responseCode = "200", description = "Backup successfully restored")})
    @Path("/{backupId}/{parameter: restorations|restore}")
    @POST
    public Response restoreBackupInNamespace(
            @Parameter(description = "This parameter specifies namespace where backup was made", required = true)
            @PathParam(NAMESPACE_PARAMETER) String namespace,
            @Parameter(description = "Backup identifier", required = true)
            @PathParam("backupId") UUID backupId,
            @Parameter(description = "This parameter specifies namespace for restoring to another project. " +
                    "This parameter is needed if backup and restore projects are different.")
            @QueryParam("targetNamespace") String targetNamespace) {
        log.info("Request to restore backup {}", backupId);
        Optional<NamespaceBackup> backupOpt = backupsDbaasRepository.findById(backupId);
        if (!backupOpt.isPresent()) {
            log.error("Cannot find such backup {}", backupId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        NamespaceBackup backup = backupOpt.get();
        namespace = !StringUtils.isEmpty(namespace) ? namespace : backup.getNamespace();
        log.info("Found requested backup {} to restore. The backup has been collected in {}", backup, namespace);

        if (!backup.getNamespace().equals(namespace)) {
            throw new ForbiddenNamespaceException(namespace, backup.getNamespace(), Source.builder().pointer("/namespace").build());
        }
        if (Objects.equals(namespace, targetNamespace)) {
            log.warn("Request to restore backup with target equals source namespace {}", namespace);
        }
        if (!backup.canRestore()) {
            throw new DBBackupValidationException(Source.builder().build(),
                    String.format("Cannot restore backup '%s', probably it had been removed or failed to be collected", backup.getId()));
        }
        if (!dbBackupsService.validateBackup(backup)) {
            throw new DBBackupValidationException(Source.builder().build(),
                    String.format("Failed to validate backup '%s'. Probably it had been removed or failed to be collected", backup.getId()));
        }
        UUID restorationId = UUID.randomUUID();
        log.info("Restoration identifier generated: {}", restorationId);
        try {
            backupsDbaasRepository.detach(backup);

            var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();

            NamespaceRestoration result = asyncOperations.getBackupPool().submit(() -> {
                ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));

                return dbBackupsService.restore(backup, restorationId, targetNamespace, false, null);
            })
            .get(awaitOperationSeconds, TimeUnit.SECONDS);

            return tryLocateResponse(result, Response.Status.OK,
                    DBAAS_PATH + "/" +
                            namespace +
                            BACKUPS + "/" + backupId +
                            "/restorations/" + restorationId);
        } catch (TimeoutException | InterruptedException e) {
            log.info("Failed to wait for backup restore {}, go to background: {}", backupId, e.getMessage());
            return tryLocateResponse(restorationId, Response.Status.ACCEPTED, DBAAS_PATH + BACKUPS + "/" + backupId + "/restorations/" + restorationId);
        } catch (ExecutionException e) {
            URI location = null;
            try {
                location = new URI(DBAAS_PATH + BACKUPS + "/" + backupId + "/restorations/" + restorationId);
            } catch (URISyntaxException se) {
                log.warn("Failed to build URI, error: {}", se.getMessage());
            }
            throw new BackupExecutionException(location, String.format("Backup restore %s execution failed", restorationId), e);
        }
    }

    @Operation(summary = "V3. Validate backup",
            description = "Validates backup of the specified namespace")
    @APIResponses({
            @APIResponse(responseCode = "500", description = "Selected backup cannot be restored, probably it had been removed or failed to be collected"),
            @APIResponse(responseCode = "404", description = "Selected backup not found"),
            @APIResponse(responseCode = "200", description = "Backup is validated and can be restored")})
    @Path("/{backupId}/validate")
    @GET
    public Response validateBackupInNamespace(
            @Parameter(description = "Identifier of backup", required = true)
            @PathParam("backupId") UUID backupId,
            @Parameter(description = "Namespace where backup was made", required = true)
            @PathParam(NAMESPACE_PARAMETER) String namespace) {
        log.info("Request to validate backup {} in {}", backupId, namespace);
        Optional<NamespaceBackup> backupOptional = backupsDbaasRepository.findById(backupId);
        if (!backupOptional.isPresent()) {
            throw new BackupNotFoundException(backupId, Source.builder().pointer("/backupId/validate").build());
        } else {
            log.info("Found requested backup {} to validate", backupId);
        }
        NamespaceBackup backup = backupOptional.get();
        if (!backup.getNamespace().equals(namespace)) {
            throw new ForbiddenNamespaceException(namespace, backup.getNamespace(), Source.builder().pointer("/namespace").build());
        }
        if (!backup.canRestore()) {
            log.error("Tried to validate backup which cannot be restored: {}", backup);
            throw new DBBackupValidationException(Source.builder().build(),
                    String.format("Cannot restore backup '%s', probably it had been removed or failed to be collected", backup.getId()), 500);
        }
        if (!dbBackupsService.validateBackup(backup)) {
            log.error("Failed to validate backup: {}", backup);
            throw new DBBackupValidationException(Source.builder().build(),
                    String.format("Failed to validate backup '%s'. Probably it had been removed or failed to be collected", backup.getId()), 500);
        }
        return Response.ok().build();
    }

    @Operation(summary = "V3. Get restoration info",
            description = "Returns restoration info")
    @APIResponses({
            @APIResponse(responseCode = "404", description = "Selected backup or restoration not found"),
            @APIResponse(responseCode = "200", description = "Restoration info was successfully collected", content = @Content(schema = @Schema(implementation = NamespaceBackup.class)))})
    @Path("/{backupId}/restorations/{restorationId}")
    @GET
    public Response getRestorationOfBackupInNamespace(
            @Parameter(description = "Identifier of backup process", required = true)
            @PathParam("backupId") UUID backupId,
            @Parameter(description = "Identifier of restore process", required = true)
            @PathParam("restorationId") UUID restorationId,
            @Parameter(description = "Namespace where backup was made", required = true)
            @PathParam(NAMESPACE_PARAMETER) String namespace) {
        log.info("Request to get info on backup {} restoration {} in {}", backupId, restorationId, namespace);
        Optional<NamespaceBackup> backupOptional = backupsDbaasRepository.findById(backupId);
        if (!backupOptional.isPresent()) {
            throw new BackupNotFoundException(backupId, Source.builder().pointer("/backupId").build());
        }
        NamespaceBackup backup = backupOptional.get();
        if (!backup.getNamespace().equals(namespace)) {
            throw new ForbiddenNamespaceException(namespace, backup.getNamespace(), Source.builder().pointer("/namespace").build());
        }
        NamespaceRestoration restoration = backup.getRestorations().stream().filter(it -> Objects.equals(it.getId(), restorationId))
                .findFirst().orElseThrow(() -> new NotFoundException("Restoration not found " + restorationId));
        return Response.ok(restoration).build();
    }

    @Operation(summary = "V3. Get backup info",
            description = "Returns backup info")
    @APIResponses({
            @APIResponse(responseCode = "404", description = "Selected backup not found"),
            @APIResponse(responseCode = "200", description = "Backup info was successfully collected", content = @Content(schema = @Schema(implementation = NamespaceBackup.class)))})
    @Path("/{backupId}")
    @GET
    public Response getBackupInNamespace(@Parameter(description = "Identifier of backup", required = true)
                                         @PathParam("backupId") UUID backupId,
                                         @Parameter(description = "Namespace where backup was made", required = true)
                                         @PathParam(NAMESPACE_PARAMETER) String namespace) {
        log.info("Request to get info on backup {} in {}", backupId, namespace);
        Optional<NamespaceBackup> backupOptional = backupsDbaasRepository.findById(backupId);
        if (!backupOptional.isPresent()) {
            throw new BackupNotFoundException(backupId, Source.builder().pointer("/backupId").build());
        } else {
            log.info("Found requested backup {} to get info", backupId);
        }
        NamespaceBackup backup = backupOptional.get();
        if (!backup.getNamespace().equals(namespace)) {
            throw new ForbiddenNamespaceException(namespace, backup.getNamespace(), Source.builder().pointer("/namespace").build());
        }
        return Response.ok(backup).build();
    }

    @Operation(summary = "V3. Add new backup info",
            description = "Adds new backup info to specified backup id")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Information was added successfully"),
            @APIResponse(responseCode = "403", description = "Backup namespace and specified namespace are different")})
    @Path("/{backupId}")
    @PUT
    public Response addBackupInNamespace(@Parameter(description = "The object for saving backup information")
                                                 NamespaceBackupDTO backupDTO,
                                         @Parameter(description = "Namespace of databases in backup time", required = true)
                                         @PathParam(NAMESPACE_PARAMETER) String namespace,
                                         @Parameter(description = "Identifier of backup process", required = true)
                                         @PathParam("backupId") UUID backupId) {
        log.info("Request to add info on new backup {} in {}", backupId, namespace);
        if (!backupDTO.getNamespace().equals(namespace)) {
            log.warn("Forbid to get info on backup {} from namespace {} using namespace {}", backupId, backupDTO.getNamespace(), namespace);
            throw new ForbiddenNamespaceException(namespace, backupDTO.getNamespace(), Source.builder().pointer("/namespace").build());
        }
        backupDTO.setId(backupId);
        NamespaceBackup backup = new NamespaceBackup(backupDTO);
        backupsDbaasRepository.save(backup);
        return Response.ok().build();
    }

    @Operation(summary = "V3. Backup namespace",
            description = "Start backup collection process for specified namespace")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Backup of namespace databases started, return backup id to track status", content = @Content(schema = @Schema(implementation = UUID.class))),
            @APIResponse(responseCode = "201", description = "Backup successfully collected", content = @Content(schema = @Schema(implementation = NamespaceBackup.class))),
            @APIResponse(responseCode = "501", description = "Some backup adapters do not support backup operation", content = @Content(schema = @Schema(implementation = String.class)))})
    @Path("/collect")
    @POST
    public Response collectBackupInNamespace(@Parameter(description = "Namespace of the database needs to be saved", required = true)
                                             @PathParam(NAMESPACE_PARAMETER) String namespace,
                                             @Parameter(description = "The parameter enables(by default)/disables validating of DBaaS adapters on the supported backup procedure")
                                             @QueryParam(value = "ignoreNotBackupableDatabases")  @DefaultValue("false") Boolean ignoreNotBackupableDatabases,
                                             @Parameter(description = "Allows to disable eviction on adapters for current backup")
                                             @QueryParam(value = "allowEviction")  @DefaultValue("true") Boolean allowEviction) {
        log.info("Request to collect backup in {}, ignoreNotBackupableDatabases parameter={} with allowEviction={}", namespace, ignoreNotBackupableDatabases, allowEviction);
        UUID id = UUID.randomUUID();
        try {
            if (!ignoreNotBackupableDatabases) {
                checkDatabasesAreBackupable(namespace);
            }

            var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();

            Future<NamespaceBackup> futureBackup = asyncOperations.getBackupPool().submit(() -> {
                ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));

                return dbBackupsService.collectBackup(namespace, id, allowEviction);
            });
            NamespaceBackup backup = futureBackup.get(awaitOperationSeconds, TimeUnit.SECONDS);
            log.info("Backup have been successfully done. Backup id={}", id);
            return tryLocateResponse(backup, Response.Status.CREATED, DBAAS_PATH + "/" + namespace + BACKUPS + "/" + id);
        } catch (TimeoutException | InterruptedException e) {
            log.info("Failed to wait for backup {}, go to background: {}", id, e.getMessage());
            return tryLocateResponse(id, Response.Status.ACCEPTED, DBAAS_PATH + "/" + namespace + BACKUPS + "/" + id);
        } catch (ExecutionException e) {
            log.error("Backup {} execution failed", id, e);
            return tryLocateResponse("Some error happened during backup", Response.Status.INTERNAL_SERVER_ERROR, DBAAS_PATH + "/" + namespace + BACKUPS + "/" + id);
        }
    }

    @Operation(summary = "V3. Delete backup",
            description = "Start delete backup process for specified namespace")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Backup successfully deleted", content = @Content(schema = @Schema(implementation = NamespaceBackupDeletion.class))),
            @APIResponse(responseCode = "403", description = "Backup deletion is forbidden", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "404", description = "Backup is not found", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "500", description = "The following backup can not be deleted", content = @Content(schema = @Schema(implementation = String.class)))})
    @Path("/{backupId}")
    @DELETE
    public Response deleteBackup(@Parameter(description = "Namespace of the database backup", required = true)
                                 @PathParam(NAMESPACE_PARAMETER) String namespace,
                                 @Parameter(description = "Identifier of backup needs to be deleted ", required = true)
                                 @PathParam("backupId") UUID backupId) {
        log.info("Request to delete backup {} with id {}", namespace, backupId);
        Optional<NamespaceBackup> optionBackupToDelete = backupsDbaasRepository.findById(backupId);
        if (!optionBackupToDelete.isPresent()) {
            throw new BackupNotFoundException(backupId, Source.builder().pathVariable("backupId").build());
        }
        NamespaceBackup backupToDelete = optionBackupToDelete.get();
        if (!backupToDelete.getNamespace().equals(namespace)) {
            throw new ForbiddenNamespaceException(namespace, backupToDelete.getNamespace(),
                    "It's forbidden to get info of backup from namespace %s using namespace %s",
                    Source.builder().pathVariable("/backupId").build());
        }
        if (!backupToDelete.canBeDeleted()) {
            throw new ForbiddenDeleteBackupOperationException("Cannot delete backup, probably it had been failed to be collected or processing");
        }
        NamespaceBackupDeletion backup = dbBackupsService.deleteBackup(backupToDelete);
        return Response.ok(backup).build();
    }

    private void checkDatabasesAreBackupable(String namespace) {
        List<DbaasAdapter> dbaasAdapters = dbBackupsService.checkAdaptersOnBackupOperation(namespace);
        if (!CollectionUtils.isEmpty(dbaasAdapters)) {
            List<String> idOfUnsupportedBackupAdapters = dbaasAdapters.stream().map(DbaasAdapter::identifier).collect(Collectors.toList());
            List<String> adaptersIdentifier = dbBackupsService.getDatabasesForBackup(namespace).stream().filter(database -> idOfUnsupportedBackupAdapters.contains(database.getAdapterId()))
                    .map(DatabaseRegistry::getAdapterId).collect(Collectors.toList());
            StringBuilder message = new StringBuilder();
            for (DbaasAdapter dbaasAdapter : dbaasAdapters) {
                Long countDB = adaptersIdentifier.stream().filter(adapterIdentifier -> adapterIdentifier.equals(dbaasAdapter.identifier())).count();
                message.append(countDB).append(" lbdbs in adapter with id=").append(dbaasAdapter.identifier()).append(" and address=").append(dbaasAdapter.adapterAddress()).append(", ");
            }
            String messageString = message.toString().replaceAll(", $", "");
            log.info("Backup request does not pass verify operation, some dbaas adapters are not support backup operations. Result validate operation: {}.", messageString);
            throw new DBNotSupportedValidationException(Source.builder().parameter("ignoreNotBackupableDatabases").build(), "Some adapters do not supported backup operation. " +
                    "To skip databases which does not support backup send the same request with parameter \"ignoreNotBackupableDatabases=true\"" + messageString);
        }
    }

    private Response tryLocateResponse(Object body, Response.Status status, String location) {
        try {
            return Response.status(status)
                    .header("Location", new URI(location).toASCIIString()).entity(body).build();
        } catch (URISyntaxException e) {
            log.error("Failed to create url for response, skip url build");
            return Response.status(status).entity(body).build();
        }
    }
}

