package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.dbaas.dto.Source;

public class ExternalDbEmptyConnectionPropertiesException extends ValidationException {
    public ExternalDbEmptyConnectionPropertiesException() {
        super(ErrorCodes.CORE_DBAAS_4025, ErrorCodes.CORE_DBAAS_4025.getDetail(), Source.builder().build());
    }
}
