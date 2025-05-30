package org.qubership.cloud.dbaas.controller.v3;

import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceRestoration;
import org.qubership.cloud.dbaas.exceptions.BackupNotFoundException;
import org.qubership.cloud.dbaas.exceptions.BackupRestorationNotFoundException;
import org.qubership.cloud.dbaas.exceptions.ForbiddenDeleteBackupOperationException;
import org.qubership.cloud.dbaas.exceptions.ForbiddenDeleteOperationException;
import org.qubership.cloud.dbaas.repositories.dbaas.BackupsDbaasRepository;
import org.qubership.cloud.dbaas.service.DBBackupsService;
import org.qubership.cloud.dbaas.service.DbaaSHelper;
import org.apache.commons.collections4.CollectionUtils;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.annotations.Separator;

import java.text.MessageFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.qubership.cloud.dbaas.Constants.BACKUP_MANAGER;
import static org.qubership.cloud.dbaas.DbaasApiPath.BACKUPS_BULK_PATH_V3;

@Slf4j
@Path(BACKUPS_BULK_PATH_V3)
@Tag(name = "Backups administration", description = "Allows to get list of available backups, trigger " +
        "backup collector and restore some specific backup. All backup management is per namespace.")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(BACKUP_MANAGER)
public class AggregatedBackupRestoreControllerV3 {

    @Inject
    BackupsDbaasRepository backupsDbaasRepository;

    @Inject
    AggregatedBackupAdministrationControllerV3 backupAdministrationControllerV3;

    @Inject
    DBBackupsService dbBackupsService;

    @Inject
    DbaaSHelper dbaaSHelper;

    @Operation(summary = "V3. Restore database",
            description = "Restores backup for specific only backup id")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Namespace restoration started, return backup id to track status"),
            @APIResponse(responseCode = "400", description = "Selected backup cannot be restored"),
            @APIResponse(responseCode = "404", description = "Selected backup not found"),
            @APIResponse(responseCode = "200", description = "Backup successfully restored")})
    @Path("/{backupId}/{parameter: restorations|restore}")
    @POST
    @Transactional
    public Response restoreBackup(@Parameter(description = "Identifier of backup process", required = true)
                                  @PathParam("backupId") UUID backupId,
                                  @Parameter(description = "This parameter specifies namespace for restoring to another project. " +
                                          "This parameter is needed to use if backup and restore projects are different.")
                                  @QueryParam("targetNamespace") String targetNamespace) {
        log.info("Request to restore backup without namespace. Backup has id = {} ", backupId);
        return backupAdministrationControllerV3.restoreBackupInNamespace(null, backupId, targetNamespace);
    }

    @Operation(summary = "V3. Get restoration info",
            description = "Returns restoration info")
    @APIResponses({
            @APIResponse(responseCode = "404", description = "Selected backup or restoration not found"),
            @APIResponse(responseCode = "200", description = "Backup validated and can be restored", content = @Content(schema = @Schema(implementation = NamespaceBackup.class)))})
    @Path("/{backupId}/restorations/{restorationId}")
    @GET
    public Response getRestorationOfBackupInNamespace(@Parameter(description = "Identifier of backup process", required = true)
                                                      @PathParam("backupId") UUID backupId,
                                                      @Parameter(description = "Identifier of restore process", required = true)
                                                      @PathParam("restorationId") UUID restorationId) {
        log.info("Request to get info on backup {} restoration {}", backupId, restorationId);
        Optional<NamespaceBackup> backup = backupsDbaasRepository.findById(backupId);
        if (!backup.isPresent()) {
            throw new BackupNotFoundException(backupId, Source.builder().pathVariable("backupId").build());
        }
        NamespaceRestoration restoration = backup.map(NamespaceBackup::getRestorations)
                .flatMap(restorations -> restorations.stream().filter(it -> Objects.equals(it.getId(), restorationId))
                        .findFirst())
                .orElseThrow(() -> new BackupRestorationNotFoundException(restorationId, Source.builder().pathVariable("restorationId").build()));
        return Response.ok(restoration).build();
    }

    @Operation(summary = "V3. Find namespace backups in namespaces",
        description = "Lists namespace backups prepared for specified namespaces",
        hidden = true
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Successfully returned namespace backups.")})
    @GET
    public Response findNamespaceBackupsInNamespaces(@Parameter(description = "List of names of namespaces where namespace backups have to be deleted from")
                                                     @QueryParam("namespaces") @Separator(",") Set<String> namespaces,
                                                     @Parameter(description = "Integer value to specify how many namespace backups have to be skipped")
                                                     @QueryParam("offset") @DefaultValue("0") Integer offset,
                                                     @Parameter(description = "Integer value to specify how many namespace backups have to be returned")
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

        return Response.ok(backupsDbaasRepository.findByNamespacesWithOffsetBasedPagination(namespaces, offset, limit)).build();
    }

    @Operation(summary = "V3. Clean all namespace backups in namespaces",
        description = "Drops all namespace backups for the specified namespaces",
        hidden = true
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Namespace backups were successfully dropped in specified namespaces", content = @Content(schema = @Schema(implementation = String.class))),
        @APIResponse(responseCode = "403", description = "Dbaas is working in PROD mode. Deleting namespace backups is prohibited", content = @Content(schema = @Schema(implementation = String.class)))
    })
    @Path("/deleteall")
    @DELETE
    @Transactional
    public Response dropAllNamespaceBackupsInNamespaces(@Parameter(description = "Boolean value to force remove or not remove namespace backups in namespaces if its can not be deleted")
                                                        @QueryParam("forceRemoveNotDeletableBackups") @DefaultValue("false") Boolean forceRemoveNotDeletableBackups,
                                                        @Parameter(description = "Namespaces to clean, operation would drop all namespace backups in that cloud project", required = true)
                                                        @QueryParam("namespaces") @Separator(",") Set<String> namespaces) {
        if (CollectionUtils.isEmpty(namespaces)) {
            throw new BadRequestException("Query parameter 'namespaces' must be not empty list of strings");
        }

        log.info("Received request to drop all namespace backups in {} namespaces {}", namespaces.size(), namespaces);

        if (dbaaSHelper.isProductionMode()) {
            throw new ForbiddenDeleteOperationException();
        }

        var namespaceBackupsAmount = backupsDbaasRepository.countByNamespaces(namespaces);

        if (namespaceBackupsAmount == 0) {

            log.info("Namespaces {} are empty, dropping is not needed", namespaces);

            return Response.ok(String.format("All %s namespaces %s do not contain any namespace backups", namespaces.size(), namespaces)).build();
        }

        if (!Boolean.TRUE.equals(forceRemoveNotDeletableBackups)) {

            var notDeletableNamespaceBackupIds = backupsDbaasRepository.findAllNotDeletableBackupIdsByNamespaces(namespaces);

            if (CollectionUtils.isNotEmpty(notDeletableNamespaceBackupIds)) {

                throw new ForbiddenDeleteBackupOperationException(MessageFormat.format(
                    "It is forbidden to delete {0} namespace backups with ids {1} because its can not be deleted, probably its had been failed to be collected or processing",
                    notDeletableNamespaceBackupIds.size(), notDeletableNamespaceBackupIds
                ));
            }
        }

        dbBackupsService.asyncDeleteAllNamespaceBackupsInNamespacesByPortions(namespaces, forceRemoveNotDeletableBackups);

        return Response.ok(String.format("Started async deletion %s namespace backups in %s namespaces %s", namespaceBackupsAmount, namespaces.size(), namespaces)).build();
    }
}

