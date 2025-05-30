package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;

public class InvalidOriginServiceException extends ErrorCodeException {
    public InvalidOriginServiceException() {
        super(ErrorCodes.CORE_DBAAS_4022, ErrorCodes.CORE_DBAAS_4022.getDetail());
    }
}
