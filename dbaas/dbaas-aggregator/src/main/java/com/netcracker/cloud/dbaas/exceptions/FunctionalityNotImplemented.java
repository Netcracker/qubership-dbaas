package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class FunctionalityNotImplemented extends ErrorCodeException {
    public FunctionalityNotImplemented(String reason) {
        super(ErrorCodes.CORE_DBAAS_4050, ErrorCodes.CORE_DBAAS_4050.getDetail(reason));
    }
}
