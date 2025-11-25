package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class AdapterException extends ErrorCodeException {
    public AdapterException(String detail) {
        super(ErrorCodes.CORE_DBAAS_4053, ErrorCodes.CORE_DBAAS_4053.getDetail(detail));
    }
}
