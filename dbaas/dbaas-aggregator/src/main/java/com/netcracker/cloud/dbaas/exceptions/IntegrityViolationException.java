package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.dbaas.dto.Source;

public class IntegrityViolationException extends ValidationException {
    public IntegrityViolationException(String detail, Source source) {
        super(ErrorCodes.CORE_DBAAS_4052, ErrorCodes.CORE_DBAAS_4052.getDetail(detail), source);
    }
}
