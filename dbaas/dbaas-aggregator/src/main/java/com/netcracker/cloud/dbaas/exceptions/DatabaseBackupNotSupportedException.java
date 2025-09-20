package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.dbaas.dto.Source;

public class DatabaseBackupNotSupportedException extends ValidationException
{
    public DatabaseBackupNotSupportedException(String message, Source source) {
        super(ErrorCodes.CORE_DBAAS_4047, ErrorCodes.CORE_DBAAS_4047.getDetail(message), source);
    }
}
