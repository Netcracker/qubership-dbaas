package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;
import lombok.Getter;

@Getter
public class DBCreationConflictException extends ErrorCodeException {

    public DBCreationConflictException(String detail) {
        super(ErrorCodes.CORE_DBAAS_4002, ErrorCodes.CORE_DBAAS_4002.getDetail(detail));
    }
}
