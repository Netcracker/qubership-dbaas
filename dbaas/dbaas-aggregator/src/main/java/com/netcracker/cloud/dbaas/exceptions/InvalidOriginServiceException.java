package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class InvalidOriginServiceException extends ErrorCodeException {
    public InvalidOriginServiceException() {
        super(ErrorCodes.CORE_DBAAS_4022, ErrorCodes.CORE_DBAAS_4022.getDetail());
    }
}
