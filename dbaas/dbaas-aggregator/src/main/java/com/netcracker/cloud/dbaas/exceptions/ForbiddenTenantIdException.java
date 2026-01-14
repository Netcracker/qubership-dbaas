package com.netcracker.cloud.dbaas.exceptions;

public class ForbiddenTenantIdException extends ForbiddenException {
    public ForbiddenTenantIdException() {
        super(ErrorCodes.CORE_DBAAS_4053, ErrorCodes.CORE_DBAAS_4053.getDetail());
    }
}
