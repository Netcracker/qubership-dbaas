package com.netcracker.cloud.dbaas.exceptions;

public class ForbiddenTenantIdException extends ForbiddenException {
    public ForbiddenTenantIdException(String idFromClassifier, String idFromRequest) {
        super(ErrorCodes.CORE_DBAAS_4054, ErrorCodes.CORE_DBAAS_4054.getDetail(idFromClassifier, idFromRequest));
    }
}
