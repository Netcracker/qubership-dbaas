package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;

public class PhysicalDatabaseRegistrationConflictException extends ErrorCodeException {

    public PhysicalDatabaseRegistrationConflictException(String detail) {
        super(ErrorCodes.CORE_DBAAS_4011, ErrorCodes.CORE_DBAAS_4011.getDetail(detail));
    }
}
