package com.netcracker.cloud.dbaas.exceptions;

public class ForbiddenTenantIdException extends ForbiddenException {
    public ForbiddenTenantIdException() {
        super(ErrorCodes.CORE_DBAAS_4046, ErrorCodes.CORE_DBAAS_4046.getDetail());
    }
}
