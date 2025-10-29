package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class InvalidMicroserviceRuleSizeException extends ErrorCodeException {
    public InvalidMicroserviceRuleSizeException(String errorMessage) {
        super(ErrorCodes.CORE_DBAAS_4028, errorMessage);
    }
}
