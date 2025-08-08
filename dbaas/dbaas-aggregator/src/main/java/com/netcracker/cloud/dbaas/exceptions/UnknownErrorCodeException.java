package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;

public class UnknownErrorCodeException extends ErrorCodeException {
    public UnknownErrorCodeException(Throwable cause) {
        super(ErrorCodes.CORE_DBAAS_2000, cause.getMessage(), cause);
    }
}
