package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;

import static org.qubership.cloud.dbaas.exceptions.ErrorCodes.CORE_DBAAS_4039;

public class BgDomainNotFoundException extends ErrorCodeException {
    public BgDomainNotFoundException(String detail) {
        super(CORE_DBAAS_4039, ErrorCodes.CORE_DBAAS_4039.getDetail(detail));
    }
}
