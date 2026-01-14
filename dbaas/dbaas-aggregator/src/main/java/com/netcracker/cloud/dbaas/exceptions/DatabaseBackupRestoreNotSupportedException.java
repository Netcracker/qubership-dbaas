package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.dbaas.dto.Source;

public class DatabaseBackupRestoreNotSupportedException extends ValidationException
{
    public DatabaseBackupRestoreNotSupportedException(String message, Source source) {
        super(ErrorCodes.CORE_DBAAS_4047, ErrorCodes.CORE_DBAAS_4047.getDetail(message), source);
    }
}
