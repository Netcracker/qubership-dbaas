package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

import static com.netcracker.cloud.dbaas.exceptions.ErrorCodes.CORE_DBAAS_4039;

public class BgDomainNotFoundException extends ErrorCodeException {
    public BgDomainNotFoundException(String detail) {
        super(CORE_DBAAS_4039, ErrorCodes.CORE_DBAAS_4039.getDetail(detail));
    }
}
