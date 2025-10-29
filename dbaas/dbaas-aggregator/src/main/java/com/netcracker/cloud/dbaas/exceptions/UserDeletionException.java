package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.dbaas.dto.Source;

public class UserDeletionException extends ValidationException {
    public UserDeletionException(Integer status, String detail) {
        super(ErrorCodes.CORE_DBAAS_4032, ErrorCodes.CORE_DBAAS_4032.getDetail(detail), Source.builder().build());
        super.setStatus(status);
    }
}
