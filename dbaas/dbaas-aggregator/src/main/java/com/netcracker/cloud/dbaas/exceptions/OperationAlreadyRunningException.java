package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class OperationAlreadyRunningException extends ErrorCodeException {
    public OperationAlreadyRunningException(String operation) {
        super(ErrorCodes.CORE_DBAAS_4053, ErrorCodes.CORE_DBAAS_4053.getDetail(operation));
    }
}
