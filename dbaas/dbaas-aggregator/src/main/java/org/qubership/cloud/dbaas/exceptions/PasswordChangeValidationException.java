package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

@Getter
public class PasswordChangeValidationException extends ValidationException {

    public PasswordChangeValidationException(String detail, Source source) {
        super(ErrorCodes.CORE_DBAAS_4007, ErrorCodes.CORE_DBAAS_4007.getDetail(detail), source);
    }
}
