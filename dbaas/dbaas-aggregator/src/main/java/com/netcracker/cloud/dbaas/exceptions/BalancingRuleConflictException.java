package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class BalancingRuleConflictException extends ErrorCodeException {
    public BalancingRuleConflictException(String type, Long order, String existingRule) {
        super(ErrorCodes.CORE_DBAAS_4008, ErrorCodes.CORE_DBAAS_4008.getDetail(type, order, existingRule));
    }
}
