package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class DigestCalculationException extends ErrorCodeException {
    public DigestCalculationException(String message) {
        super(ErrorCodes.CORE_DBAAS_4051, message);
    }
}
