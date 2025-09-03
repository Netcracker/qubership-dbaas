package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class UnknownErrorCodeException extends ErrorCodeException {
    public UnknownErrorCodeException(Throwable cause) {
        super(ErrorCodes.CORE_DBAAS_2000, cause.getMessage(), cause);
    }
}
