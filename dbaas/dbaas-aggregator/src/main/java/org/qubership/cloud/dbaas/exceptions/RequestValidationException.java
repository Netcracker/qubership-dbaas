package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCode;
import org.qubership.cloud.dbaas.dto.Source;

public class RequestValidationException extends ValidationException {
    public RequestValidationException(ErrorCode errorCode, String detail, Source source) {
        super(errorCode, detail, source);
    }
}
