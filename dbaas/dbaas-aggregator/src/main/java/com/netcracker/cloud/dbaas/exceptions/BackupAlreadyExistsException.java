package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.dbaas.dto.Source;

public class BackupAlreadyExistsException extends ValidationException {
    public BackupAlreadyExistsException(String backupName, Source source) {
        super(ErrorCodes.CORE_DBAAS_4046, ErrorCodes.CORE_DBAAS_4046.getDetail(backupName), source);
    }
}
