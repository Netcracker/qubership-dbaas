package org.qubership.cloud.dbaas.controller.v3;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.dto.backupV2.BackupDto;
import org.qubership.cloud.dbaas.exceptions.ErrorCodes;
import org.qubership.cloud.dbaas.exceptions.RequestValidationException;
import org.qubership.cloud.dbaas.service.DbBackupV2Service;

import static org.qubership.cloud.dbaas.Constants.BACKUP_MANAGER;


@Slf4j
@Path("/api/backups/v1/operation/backup")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(BACKUP_MANAGER)
public class DatabaseBackupV2Controller {

    private final DbBackupV2Service dbBackupV2Service;

    @Inject
    public DatabaseBackupV2Controller(DbBackupV2Service dbBackupV2Service) {
        this.dbBackupV2Service = dbBackupV2Service;
    }

    @POST
    public Response getBackupByNamespace(@Parameter(required = true) @Valid BackupDto backupDto) {
        if (validBackupDtoInput(backupDto))
            dbBackupV2Service.backup(backupDto.getNamespace(), backupDto.getBackupName());
        return Response.ok().build();
    }


    private boolean validBackupDtoInput(BackupDto backupDto) {
        if (backupDto != null) {
            if (backupDto.getBackupName() == null || backupDto.getNamespace() == null)
                throw new RequestValidationException(ErrorCodes.CORE_DBAAS_4043,
                        ErrorCodes.CORE_DBAAS_4043.getDetail("backup name or namespace"), Source.builder().pointer("/" + "backup name or namespace").build());
        } else
            throw new RequestValidationException(ErrorCodes.CORE_DBAAS_4043,
                    ErrorCodes.CORE_DBAAS_4043.getDetail("dto"), Source.builder().pointer("/" + "backupDto").build());

        return true;
    }
}
