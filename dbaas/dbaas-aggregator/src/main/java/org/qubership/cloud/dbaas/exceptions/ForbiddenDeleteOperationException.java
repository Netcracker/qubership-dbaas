package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;
import lombok.Getter;

@Getter
public class ForbiddenDeleteOperationException extends ErrorCodeException {

    public ForbiddenDeleteOperationException() {
        super(ErrorCodes.CORE_DBAAS_4003, ErrorCodes.CORE_DBAAS_4003.getDetail());
    }
}
