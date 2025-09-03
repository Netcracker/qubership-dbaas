package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class OnMicroserviceBalancingRuleException extends ErrorCodeException {
    public OnMicroserviceBalancingRuleException(String errorMessage) {
        super(ErrorCodes.CORE_DBAAS_4029, errorMessage);
    }
}
