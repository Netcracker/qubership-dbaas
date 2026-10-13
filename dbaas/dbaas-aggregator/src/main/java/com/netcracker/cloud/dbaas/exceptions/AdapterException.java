package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;
import lombok.Getter;

@Getter
public class AdapterException extends ErrorCodeException {
    private final int httpCode;
    private final String errorMessage;

    public AdapterException(int httpCode, String errorMessage) {
        super(ErrorCodes.CORE_DBAAS_4056, ErrorCodes.CORE_DBAAS_4056.getDetail(errorMessage));
        this.httpCode = httpCode;
        this.errorMessage = errorMessage;
    }
}
