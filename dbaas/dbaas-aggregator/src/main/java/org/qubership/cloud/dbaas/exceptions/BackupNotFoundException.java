package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

import java.util.UUID;

@Getter
public class BackupNotFoundException extends ValidationException {

    public BackupNotFoundException(UUID backupId, Source source) {
        super(ErrorCodes.CORE_DBAAS_4012, ErrorCodes.CORE_DBAAS_4012.getDetail(String.valueOf(backupId)), source);
    }
}
