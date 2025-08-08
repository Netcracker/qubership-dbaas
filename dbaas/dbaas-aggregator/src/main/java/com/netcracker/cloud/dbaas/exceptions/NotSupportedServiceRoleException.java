package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class NotSupportedServiceRoleException extends ErrorCodeException {
    public NotSupportedServiceRoleException(String role) {
        super(ErrorCodes.CORE_DBAAS_4023, ErrorCodes.CORE_DBAAS_4023.getDetail(role));
    }

    public NotSupportedServiceRoleException() {
        super(ErrorCodes.CORE_DBAAS_4023, ErrorCodes.CORE_DBAAS_4023.getDetail(""));
    }
}
