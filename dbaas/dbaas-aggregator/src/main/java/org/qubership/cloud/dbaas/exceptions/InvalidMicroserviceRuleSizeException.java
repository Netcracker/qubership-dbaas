package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;

public class InvalidMicroserviceRuleSizeException extends ErrorCodeException {
    public InvalidMicroserviceRuleSizeException(String errorMessage) {
        super(ErrorCodes.CORE_DBAAS_4028, errorMessage);
    }
}
