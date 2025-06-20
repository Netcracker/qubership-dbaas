package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;
import lombok.Getter;

@Getter
public class ForbiddenDeleteBackupOperationException extends ErrorCodeException {

    public ForbiddenDeleteBackupOperationException(String detail) {
        super(ErrorCodes.CORE_DBAAS_4013, ErrorCodes.CORE_DBAAS_4013.getDetail(detail));
    }
}
