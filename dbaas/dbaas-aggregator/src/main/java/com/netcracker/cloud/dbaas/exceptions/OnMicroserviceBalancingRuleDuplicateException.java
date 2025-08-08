package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class OnMicroserviceBalancingRuleDuplicateException extends ErrorCodeException {
    public OnMicroserviceBalancingRuleDuplicateException(String errorMessage) {
        super(ErrorCodes.CORE_DBAAS_4034, errorMessage);
    }
}
