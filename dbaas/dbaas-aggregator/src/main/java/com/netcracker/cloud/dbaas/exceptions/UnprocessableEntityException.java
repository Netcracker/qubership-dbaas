package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.dbaas.dto.Source;

public class UnprocessableEntityException extends ValidationException {

    public UnprocessableEntityException(String resourceName, String reason, Source source) {
        super(ErrorCodes.CORE_DBAAS_4049, ErrorCodes.CORE_DBAAS_4049.getDetail(resourceName, reason), source);
    }
}
