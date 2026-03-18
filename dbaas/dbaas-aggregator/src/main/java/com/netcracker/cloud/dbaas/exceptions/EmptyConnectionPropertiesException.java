package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.dbaas.dto.Source;


public class EmptyConnectionPropertiesException extends ValidationException {
    public EmptyConnectionPropertiesException() {
        super(ErrorCodes.CORE_DBAAS_4025, ErrorCodes.CORE_DBAAS_4025.getDetail(), Source.builder().build());
    }
}
