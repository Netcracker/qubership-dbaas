package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;

public class OnMicroserviceBalancingRuleDuplicateException extends ErrorCodeException {
    public OnMicroserviceBalancingRuleDuplicateException(String errorMessage) {
        super(ErrorCodes.CORE_DBAAS_4034, errorMessage);
    }
}