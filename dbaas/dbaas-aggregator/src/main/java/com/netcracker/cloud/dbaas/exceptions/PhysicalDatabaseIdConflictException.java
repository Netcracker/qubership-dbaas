package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class PhysicalDatabaseIdConflictException extends ErrorCodeException {

    public PhysicalDatabaseIdConflictException(String detail) {
        super(ErrorCodes.CORE_DBAAS_4011, ErrorCodes.CORE_DBAAS_4011.getDetail(detail));
    }
}
