package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.dbaas.dto.Source;

public class OperationAlreadyRunningException extends ValidationException {
    public OperationAlreadyRunningException(String operation, Source source) {
        super(ErrorCodes.CORE_DBAAS_4053, ErrorCodes.CORE_DBAAS_4053.getDetail(operation), source);
    }
}
