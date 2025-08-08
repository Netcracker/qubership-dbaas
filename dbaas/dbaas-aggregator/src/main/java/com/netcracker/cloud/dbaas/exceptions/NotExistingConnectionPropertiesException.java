package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class NotExistingConnectionPropertiesException extends ErrorCodeException {
    public NotExistingConnectionPropertiesException(String role) {
        super(ErrorCodes.CORE_DBAAS_4021, ErrorCodes.CORE_DBAAS_4021.getDetail(role));
    }
}
