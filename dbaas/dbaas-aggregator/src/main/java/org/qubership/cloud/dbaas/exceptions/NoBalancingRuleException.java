package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCode;
import org.qubership.cloud.core.error.runtime.ErrorCodeException;

public class NoBalancingRuleException extends ErrorCodeException {
    public NoBalancingRuleException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}
