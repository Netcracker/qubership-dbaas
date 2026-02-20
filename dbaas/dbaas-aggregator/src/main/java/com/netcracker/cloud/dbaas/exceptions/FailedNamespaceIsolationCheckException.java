package com.netcracker.cloud.dbaas.exceptions;

public class FailedNamespaceIsolationCheckException extends ForbiddenException {
    public FailedNamespaceIsolationCheckException() {
        super(ErrorCodes.CORE_DBAAS_4055, ErrorCodes.CORE_DBAAS_4055.getDetail());
    }
}
