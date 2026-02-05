package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;
import lombok.Getter;

@Getter
public class ForbiddenDeleteOperationException extends ForbiddenException {

    public ForbiddenDeleteOperationException() {
        super(ErrorCodes.CORE_DBAAS_4003, ErrorCodes.CORE_DBAAS_4003.getDetail());
    }
}
