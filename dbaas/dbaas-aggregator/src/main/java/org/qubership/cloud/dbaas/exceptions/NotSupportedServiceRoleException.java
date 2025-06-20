package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;

public class NotSupportedServiceRoleException extends ErrorCodeException {
    public NotSupportedServiceRoleException(String role) {
        super(ErrorCodes.CORE_DBAAS_4023, ErrorCodes.CORE_DBAAS_4023.getDetail(role));
    }

    public NotSupportedServiceRoleException() {
        super(ErrorCodes.CORE_DBAAS_4023, ErrorCodes.CORE_DBAAS_4023.getDetail(""));
    }
}
