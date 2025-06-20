package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

@Getter
public class DBNotSupportedValidationException extends ValidationException {

    public DBNotSupportedValidationException(Source source, String detail) {
        super(ErrorCodes.CORE_DBAAS_4030, ErrorCodes.CORE_DBAAS_4030.getDetail(detail), source);
    }
}
