package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCode;
import com.netcracker.cloud.dbaas.dto.Source;

public class RequestValidationException extends ValidationException {
    public RequestValidationException(ErrorCode errorCode, String detail, Source source) {
        super(errorCode, detail, source);
    }
}
