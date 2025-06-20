package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

@Getter
public class UserNotFoundException extends ValidationException {

    public UserNotFoundException(Integer status, Source source, String detail) {
        super(ErrorCodes.CORE_DBAAS_4031, ErrorCodes.CORE_DBAAS_4031.getDetail(detail), source);
        super.setStatus(status);
    }
}