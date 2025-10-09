package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCode;
import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class DigestCalculationException extends ErrorCodeException {
    public DigestCalculationException(String details) {
        super(ErrorCodes.CORE_DBAAS_4051, details);
    }
}
