package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;

public class OnMicroserviceBalancingRuleException extends ErrorCodeException {
    public OnMicroserviceBalancingRuleException(String errorMessage) {
        super(ErrorCodes.CORE_DBAAS_4029, errorMessage);
    }
}