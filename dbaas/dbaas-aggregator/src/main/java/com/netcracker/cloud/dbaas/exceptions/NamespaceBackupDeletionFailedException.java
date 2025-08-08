package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;
import com.netcracker.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import lombok.Getter;

import java.util.UUID;

@Getter
public class NamespaceBackupDeletionFailedException extends ErrorCodeException {
    private final NamespaceBackup backupToDelete;
    public NamespaceBackupDeletionFailedException(UUID backupId, Long subdeletions, NamespaceBackup backupToDelete) {
        super(ErrorCodes.CORE_DBAAS_4014, ErrorCodes.CORE_DBAAS_4014.getDetail(backupId, subdeletions));
        this.backupToDelete = backupToDelete;
    }

}
