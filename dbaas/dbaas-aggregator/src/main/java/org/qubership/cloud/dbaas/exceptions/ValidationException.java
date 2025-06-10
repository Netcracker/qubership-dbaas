package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCode;
import org.qubership.cloud.core.error.runtime.ErrorCodeException;
import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;
import lombok.Setter;

@Getter
public abstract class ValidationException extends ErrorCodeException {
    @Getter
    @Setter
    private Integer status;
    private final Source source;

    protected ValidationException(ErrorCode errorCode, String detail, Source source) {
        super(errorCode, detail);
        this.source = source;
    }
}
