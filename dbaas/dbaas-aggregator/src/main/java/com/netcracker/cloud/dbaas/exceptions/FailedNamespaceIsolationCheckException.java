package com.netcracker.cloud.dbaas.exceptions;

public class FailedNamespaceIsolationCheckException extends ForbiddenException {
    public FailedNamespaceIsolationCheckException() {
        super(ErrorCodes.CORE_DBAAS_4047, ErrorCodes.CORE_DBAAS_4047.getDetail());
    }
}
