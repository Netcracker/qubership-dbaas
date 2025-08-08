package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

@Getter
public class DBCreateValidationException extends ValidationException {

    public DBCreateValidationException(Source source, String detail) {
        super(ErrorCodes.CORE_DBAAS_4005, ErrorCodes.CORE_DBAAS_4005.getDetail(detail), source);
    }
}
