package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;


public class EmptyConnectionPropertiesException extends ErrorCodeException {
    public EmptyConnectionPropertiesException() {
        super(ErrorCodes.CORE_DBAAS_4025, ErrorCodes.CORE_DBAAS_4025.getDetail());
    }
}
