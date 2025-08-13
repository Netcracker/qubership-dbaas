package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;
import lombok.Getter;

@Getter
public class ForbiddenDeleteBackupOperationException extends ErrorCodeException {

    public ForbiddenDeleteBackupOperationException(String detail) {
        super(ErrorCodes.CORE_DBAAS_4013, ErrorCodes.CORE_DBAAS_4013.getDetail(detail));
    }
}
