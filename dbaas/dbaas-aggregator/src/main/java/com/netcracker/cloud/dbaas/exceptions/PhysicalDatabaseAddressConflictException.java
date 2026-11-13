package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class PhysicalDatabaseAddressConflictException extends ErrorCodeException {

    public PhysicalDatabaseAddressConflictException(String detail) {
        super(ErrorCodes.CORE_DBAAS_4057, ErrorCodes.CORE_DBAAS_4057.getDetail(detail));
    }
}
