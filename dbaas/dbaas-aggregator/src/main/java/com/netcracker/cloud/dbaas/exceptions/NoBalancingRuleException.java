package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCode;
import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class NoBalancingRuleException extends ErrorCodeException {
    public NoBalancingRuleException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}
