package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
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
